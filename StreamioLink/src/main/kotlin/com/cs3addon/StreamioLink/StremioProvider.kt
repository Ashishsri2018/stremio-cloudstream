package com.cs3addon.StreamioLink

import com.lagradost.api.Log
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageData
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.ShowStatus
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TrailerData
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addDate
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newLiveSearchResponse
import com.lagradost.cloudstream3.newLiveStreamLoadResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Cloudstream provider that plays content through Stremio addons.
 *
 * - Every enabled catalog addon contributes home rows; search queries all of them plus the
 *   configured metadata addon. The first enabled catalog addon (default: Cinemeta) remains the
 *   primary addon for home ordering and metadata.
 * - Every configured source addon (plus catalog addons whose per-entry "use as source" is on)
 *   is queried for streams.
 */
class StremioProvider : MainAPI() {
    override var mainUrl = StremioSettings.DEFAULT_CATALOG_URL
    override var name = "StreamioLink"
    override var lang = "en"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Others, TvType.Live, TvType.Torrent)
    override val loadLinksTimeoutMs = 180_000L

    /** A stuck catalog must not hold the whole home load for the default 120 s. */
    override val getMainPageTimeoutMs = 90_000L
    override val searchTimeoutMs = 60_000L

    private companion object {
        const val TAG = "StreamioLink"
        const val DEFAULT_PAGE_SIZE = 20
        const val MAX_LINKS = 500

        /**
         * Catalogs are fetched with bounded concurrency. A catalog addon can advertise dozens
         * of rows (Nuvio: 27 loadable catalogs), and firing all of them at once opened as many
         * sockets; a small window keeps the device and the addon calm without adding much latency.
         */
        const val MAX_CONCURRENT_CATALOGS = 6
    }

    private val catalogSemaphore = Semaphore(MAX_CONCURRENT_CATALOGS)

    private data class LinkCandidate(
        val addonName: String,
        val stream: StremioStream,
        val url: String,
        val headers: Map<String, String>,
        val referer: String,
        val quality: Int,
        val label: String,
        /** True when [url] is a magnet URI (infoHash-only stream). */
        val torrent: Boolean,
    )

    /** One loadable catalog row, together with the addon it came from. */
    private data class CatalogSource(
        val base: String,
        val catalog: StremioCatalog,
    )

    /** Single anonymous tab used until the catalog manifest has been read once. */
    private val placeholderPage = listOf(MainPageData("", "", false))

    /** Catalog tabs for the current catalog addons, populated on the first home load. */
    @Volatile private var catalogTabs: List<MainPageData> = emptyList()

    @Volatile private var catalogTabsBase: String? = null

    /** Per-catalog item offset, advanced by the number of items the addon actually returned. */
    private val catalogOffsets = ConcurrentHashMap<String, Int>()

    /** Identifies the catalog addon set; any URL/order change invalidates the cached tabs. */
    private fun catalogSettingsKey(): String = StremioSettings.catalogUrls().joinToString("|")

    override val mainPage: List<MainPageData>
        get() {
            // Tabs are only valid for the catalog addons they were fetched from; a URL change
            // falls back to the single placeholder tab so the next home load refetches them.
            if (catalogTabsBase != catalogSettingsKey()) return placeholderPage
            return catalogTabs.ifEmpty { placeholderPage }
        }

    // ---------------------------------------------------------------- home page

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val ref = StremioMapping.decodeCatalogRef(request.data)
        // Cloudstream expands a row by asking for one catalog (encoded in request.data).
        // The initial load has no ref and returns every catalog from every catalog addon.
        return if (ref != null) singleCatalogPage(ref, request.name, page)
        else allCatalogsPage(page)
    }

    private suspend fun allCatalogsPage(page: Int): HomePageResponse? {
        val catalogBases = StremioSettings.catalogUrls()
        val manifests = catalogBases.mapNotNull { base ->
            StremioApi.getManifest(base)?.let { base to it }
        }
        if (manifests.isEmpty()) return newHomePageResponse(emptyList(), false)

        val settingsKey = catalogSettingsKey()
        if (catalogTabsBase != settingsKey) {
            catalogOffsets.clear()
        }

        // Rows ordered primary addon first, then each additional catalog addon in settings
        // order. Titles are resolved per addon (movie/series duplicates) and then globally so
        // two addons can never collapse into the same Cloudstream row name.
        val sources = ArrayList<CatalogSource>()
        val baseTitles = ArrayList<String>()
        val suffixes = ArrayList<String>()
        manifests.forEach { (base, manifest) ->
            val addonName = manifest.name?.takeIf { it.isNotBlank() } ?: hostOf(base)
            val catalogs = manifest.catalogs.orEmpty().filter { StremioMapping.catalogCanLoadHome(it) }
            StremioMapping.catalogTitles(catalogs, addonName).forEach { (catalog, title) ->
                sources.add(CatalogSource(base, catalog))
                baseTitles.add(title)
                suffixes.add(addonName)
            }
        }
        if (sources.isEmpty()) return newHomePageResponse(emptyList(), false)
        val titles = StremioMapping.disambiguateTitles(baseTitles, suffixes)

        catalogTabs = sources.mapIndexedNotNull { index, source ->
            val type = source.catalog.type ?: return@mapIndexedNotNull null
            val id = source.catalog.id ?: return@mapIndexedNotNull null
            MainPageData(
                name = titles[index],
                data = StremioMapping.encodeCatalogRef(type, id, source.base),
                horizontalImages = false,
            )
        }
        // Publish the tabs before the key so a concurrent [mainPage] read never pairs the new
        // key with the previous addon set's tabs.
        catalogTabsBase = settingsKey

        val rows = coroutineScope {
            sources.mapIndexed { index, source ->
                async {
                    catalogSemaphore.withPermit { fetchCatalogRow(source, page, titles[index]) }
                }
            }.awaitAll().filterNotNull()
        }
        val hasNext = sources.any { StremioMapping.catalogSupportsSkip(it.catalog) }
        return newHomePageResponse(rows, hasNext)
    }

    private suspend fun singleCatalogPage(
        ref: StremioCatalogRef,
        requestName: String,
        page: Int,
    ): HomePageResponse? {
        val catalogBase = StremioMapping.normalizeAddonUrl(ref.base) ?: StremioSettings.catalogUrl()
        val key = StremioMapping.encodeCatalogRef(ref.type, ref.id, catalogBase)
        val manifest = StremioApi.getManifest(catalogBase)
        val catalog = manifest?.catalogs.orEmpty()
            .firstOrNull { it.id == ref.id && it.type.equals(ref.type, ignoreCase = true) }
        val skipSupported = catalog?.let { StremioMapping.catalogSupportsSkip(it) } == true
        // A catalog that cannot paginate returns its first page for every `skip`, so asking
        // for page 2+ would only duplicate rows the app has no way to dedupe.
        if (page > 1 && !skipSupported) return newHomePageResponse(emptyList(), false)

        // page 1 is a fresh (re)load, restart from the top; later pages continue the offset.
        val offset = if (page <= 1) 0 else (catalogOffsets[key] ?: ((page - 1) * DEFAULT_PAGE_SIZE))
        val extra = StremioMapping.catalogExtra(
            catalog?.let { StremioMapping.catalogPresetExtra(it) },
            if (skipSupported) offset else null,
        )
        val items = try {
            StremioApi.getCatalog(catalogBase, ref.type, ref.id, extra)
        } catch (t: CancellationException) {
            throw t
        } catch (t: Throwable) {
            Log.e(TAG, "Catalog ${ref.id} page $page failed: ${t.message}")
            emptyList()
        }

        if (skipSupported) catalogOffsets[key] = offset + items.size

        val searches = items.mapNotNull { toSearchResponse(it, ref.type, catalogBase) }
        if (searches.isEmpty()) return newHomePageResponse(emptyList(), false)
        val addonName = manifest?.name?.takeIf { it.isNotBlank() } ?: name
        val title = requestName.takeIf { it.isNotBlank() }
            ?: (catalog?.let { StremioMapping.catalogBaseTitle(it, addonName) })
            ?: ref.id
        return newHomePageResponse(listOf(HomePageList(title, searches)), skipSupported)
    }

    private suspend fun fetchCatalogRow(
        source: CatalogSource,
        page: Int,
        title: String,
    ): HomePageList? {
        val type = source.catalog.type ?: return null
        val id = source.catalog.id ?: return null
        val key = StremioMapping.encodeCatalogRef(type, id, source.base)
        val offset = if (page > 1) (catalogOffsets[key] ?: ((page - 1) * DEFAULT_PAGE_SIZE)) else 0
        val extra = StremioMapping.catalogExtra(StremioMapping.catalogPresetExtra(source.catalog), offset)
        val items = try {
            StremioApi.getCatalog(source.base, type, id, extra)
        } catch (t: CancellationException) {
            throw t
        } catch (t: Throwable) {
            Log.e(TAG, "Home catalog $id failed: ${t.message}")
            return null
        }
        if (StremioMapping.catalogSupportsSkip(source.catalog)) catalogOffsets[key] = offset + items.size
        val searches = items.mapNotNull { toSearchResponse(it, type, source.base) }
        if (searches.isEmpty()) return null
        return HomePageList(title, searches)
    }

    private fun toSearchResponse(
        meta: StremioMetaPreview,
        fallbackType: String,
        base: String,
    ): SearchResponse? {
        val id = meta.id ?: return null
        val metaType = meta.type?.takeIf { it.isNotBlank() } ?: fallbackType
        // Keep the live catalog type in the payload when the meta is live-ish but declares a
        // generic `movie`; otherwise load() would lose the live shape on the next round-trip.
        val type = if (StremioMapping.isLiveType(fallbackType) && !StremioMapping.usesEpisodes(metaType)) {
            fallbackType
        } else {
            metaType
        }
        val payload = StremioMapping.encodePayload(id, type, base)
        val title = meta.name?.takeIf { it.isNotBlank() } ?: id
        val poster = StremioMapping.previewPoster(meta, fallbackType)
        val score = scoreFrom10(meta.imdbRating)
        return when (
            StremioMapping.mediaShape(type, isLiveHint = meta.behaviorHints?.isLive == true)
        ) {
            StremioMapping.MediaShape.Series -> newTvSeriesSearchResponse(title, payload) {
                this.posterUrl = poster
                this.year = StremioMapping.yearFromReleaseInfo(meta.releaseInfo)
                this.score = score
            }

            StremioMapping.MediaShape.Live -> newLiveSearchResponse(title, payload) {
                this.posterUrl = poster
                this.score = score
            }

            StremioMapping.MediaShape.Movie -> newMovieSearchResponse(title, payload) {
                this.posterUrl = poster
                this.year = StremioMapping.yearFromReleaseInfo(meta.releaseInfo)
                this.score = score
            }
        }
    }

    /** CloudStream rating (0..10) from an addon rating string, or null when unavailable. */
    private fun scoreFrom10(value: String?): Score? =
        Score.from10(StremioMapping.ratingFrom10(value))

    // ---------------------------------------------------------------- search

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        val encoded = StremioMapping.encodeQueryValue(query)

        // Search every enabled catalog addon, primary first, then the separately configured
        // metadata addon (skipped when it is already in the catalog list). Addons are queried
        // concurrently, while each addon's catalogs share the bounded catalog semaphore.
        val bases = LinkedHashSet<String>()
        StremioSettings.catalogUrls().forEach { url ->
            StremioMapping.normalizeAddonUrl(url)?.let { bases.add(it) }
        }
        StremioMapping.normalizeAddonUrl(StremioSettings.metaUrl())?.let { bases.add(it) }
        if (bases.isEmpty()) return emptyList()

        return supervisorScope {
            bases.map { base ->
                async {
                    try {
                        searchAddon(base, encoded)
                    } catch (t: CancellationException) {
                        throw t
                    } catch (t: Throwable) {
                        Log.e(TAG, "Search addon $base failed: ${t.message}")
                        emptyList()
                    }
                }
            }.awaitAll().flatten()
        }
    }

    private suspend fun searchAddon(base: String, encodedQuery: String): List<SearchResponse> {
        val manifest = StremioApi.getManifest(base) ?: return emptyList()
        val searchable = manifest.catalogs.orEmpty().filter { StremioMapping.catalogSupportsSearch(it) }
        if (searchable.isEmpty()) return emptyList()

        return coroutineScope {
            searchable.map { catalog ->
                async {
                    val type = catalog.type ?: return@async emptyList()
                    val id = catalog.id ?: return@async emptyList()
                    try {
                        catalogSemaphore.withPermit {
                            val extra = StremioMapping.catalogExtra(
                                StremioMapping.catalogPresetExtra(catalog),
                                search = encodedQuery,
                            )
                            StremioApi.getCatalog(base, type, id, extra)
                                .mapNotNull { toSearchResponse(it, type, base) }
                        }
                    } catch (t: CancellationException) {
                        throw t
                    } catch (t: Throwable) {
                        Log.e(TAG, "Search catalog $id failed: ${t.message}")
                        emptyList()
                    }
                }
            }.awaitAll().flatten().distinctBy { it.url }
        }
    }

    // ---------------------------------------------------------------- load

    override suspend fun load(url: String): LoadResponse? {
        val payload = StremioMapping.decodePayload(url) ?: return null
        val meta = fetchMeta(payload) ?: return null
        val title = meta.name?.takeIf { it.isNotBlank() } ?: payload.id
        // Catalog types such as `anime` (or items without a type) only reveal whether they hold
        // episodes once the meta response is in; trust meta.type for the response shape and for
        // the episode payloads, so series are not loaded as movies. Live `tv`/`live` channels
        // have no episode list and load as a single live stream; `behaviorHints.isLive` is an
        // additional live signal used by addons whose type stays `movie`/`channel`.
        val type = meta.type?.takeIf { it.isNotBlank() } ?: payload.type
        val shape = StremioMapping.mediaShape(
            type,
            meta.videos,
            isLiveHint = meta.behaviorHints?.isLive == true ||
                StremioMapping.isLiveType(payload.type),
        )

        return when (shape) {
            StremioMapping.MediaShape.Series -> {
                val episodes = meta.videos.orEmpty().mapNotNull { toEpisode(type, payload.base, it) }
                newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                    this.posterUrl = meta.poster
                    this.backgroundPosterUrl = meta.background
                    this.logoUrl = meta.logo
                    this.plot = meta.description
                    this.year = StremioMapping.yearFromReleaseInfo(meta.releaseInfo)
                    this.duration = StremioMapping.runtimeMinutes(meta.runtime)
                    this.score = scoreFromMeta(meta)
                    this.tags = meta.genres
                    this.contentRating = contentRatingFrom(meta)
                    this.actors = actorsFrom(meta)
                    this.trailers = trailersFrom(meta)
                    this.showStatus = when (StremioMapping.showStateFrom(meta.status)) {
                        StremioMapping.ShowState.Completed -> ShowStatus.Completed
                        StremioMapping.ShowState.Ongoing -> ShowStatus.Ongoing
                        null -> null
                    }
                }
            }

            StremioMapping.MediaShape.Live -> newLiveStreamLoadResponse(title, url, url) {
                this.posterUrl = meta.poster
                this.backgroundPosterUrl = meta.background
                this.logoUrl = meta.logo
                this.plot = meta.description
                this.year = StremioMapping.yearFromReleaseInfo(meta.releaseInfo)
                this.duration = StremioMapping.runtimeMinutes(meta.runtime)
                this.score = scoreFromMeta(meta)
                this.tags = meta.genres
                this.contentRating = contentRatingFrom(meta)
                this.actors = actorsFrom(meta)
                this.trailers = trailersFrom(meta)
            }

            StremioMapping.MediaShape.Movie -> newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = meta.poster
                this.backgroundPosterUrl = meta.background
                this.logoUrl = meta.logo
                this.plot = meta.description
                this.year = StremioMapping.yearFromReleaseInfo(meta.releaseInfo)
                this.duration = StremioMapping.runtimeMinutes(meta.runtime)
                this.score = scoreFromMeta(meta)
                this.tags = meta.genres
                this.contentRating = contentRatingFrom(meta)
                this.actors = actorsFrom(meta)
                this.trailers = trailersFrom(meta)
            }
        }
    }

    private fun scoreFromMeta(meta: StremioMetaDetails): Score? =
        scoreFrom10(meta.imdbRating)
            ?: scoreFrom10(meta.appExtras?.ratings?.imdb?.rating)
            ?: scoreFrom10(meta.appExtras?.ratings?.tmdb?.rating)

    private fun contentRatingFrom(meta: StremioMetaDetails): String? =
        meta.ageRating?.trim()?.takeIf { it.isNotEmpty() }
            ?: meta.appExtras?.certification?.trim()?.takeIf { it.isNotEmpty() }

    private fun actorsFrom(meta: StremioMetaDetails): List<ActorData> {
        val extras = meta.appExtras?.cast.orEmpty().mapNotNull { member ->
            val name = member.name?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            ActorData(
                Actor(name, member.photo?.trim()?.takeIf { it.isNotEmpty() }),
                roleString = member.character?.trim()?.takeIf { it.isNotEmpty() },
            )
        }
        if (extras.isNotEmpty()) return extras
        return meta.cast.orEmpty().mapNotNull { name ->
            name.trim().takeIf { it.isNotEmpty() }?.let { ActorData(Actor(it, null)) }
        }
    }

    private fun trailersFrom(meta: StremioMetaDetails): MutableList<TrailerData> {
        val ids = LinkedHashSet<String>()
        meta.trailerStreams.orEmpty().forEach { trailer ->
            trailer.ytId?.trim()?.takeIf { it.isNotEmpty() }?.let { ids.add(it) }
        }
        meta.trailers.orEmpty().forEach { trailer ->
            val id = trailer.ytId?.trim()?.takeIf { it.isNotEmpty() }
                ?: trailer.source?.trim()?.takeIf { it.isNotEmpty() }
            id?.let { ids.add(it) }
        }
        return ids.mapNotNull { id ->
            StremioMapping.trailerUrl(id)?.let { TrailerData(it, null, false) }
        }.toMutableList()
    }

    private suspend fun fetchMeta(payload: StremioPayload): StremioMetaDetails? {
        // The item's own catalog addon knows its ids best; then the primary catalog addon
        // (legacy payloads without a base), then the configured metadata fallback.
        val candidates = linkedSetOf<String>()
        StremioMapping.normalizeAddonUrl(payload.base)?.let { candidates.add(it) }
        candidates.add(StremioSettings.catalogUrl())
        StremioMapping.normalizeAddonUrl(StremioSettings.metaUrl())?.let { candidates.add(it) }

        for (base in candidates) {
            // Live catalogs are inconsistent about the type advertised for their meta
            // resource; resolve it through the cached manifest before building the URL.
            val manifest = StremioApi.getManifest(base)
            val metaType = manifest?.let {
                StremioMapping.resolveResourceType(it, "meta", payload.type)
            } ?: payload.type
            val meta = StremioApi.getMeta(base, metaType, payload.id) ?: continue
            // Some addons return a stub meta (name == id, no videos) for ids they only
            // stream; skip those instead of breaking a series that a later addon knows.
            val hasTitle = !meta.name.isNullOrBlank()
            val hasEpisodes = !StremioMapping.usesEpisodes(meta.type ?: payload.type, meta.videos) ||
                !meta.videos.isNullOrEmpty()
            if (hasTitle && hasEpisodes) return meta
        }
        return null
    }

    private fun toEpisode(parentType: String, base: String?, video: StremioVideo): Episode? {
        val videoId = video.id ?: return null
        val parsedIndex = StremioMapping.episodeIndexFromId(videoId)
        return newEpisode(StremioMapping.encodePayload(videoId, parentType, base)) {
            this.name = StremioMapping.episodeName(video) ?: videoId
            this.season = video.season ?: parsedIndex?.first ?: 1
            this.episode = video.episode ?: video.number ?: parsedIndex?.second ?: 1
            this.posterUrl = video.thumbnail
            this.description = video.overview
            this.score = scoreFrom10(video.rating)
            this.runTime = StremioMapping.runtimeMinutes(video.runtime)
            video.released?.let { addDate(it) }
        }
    }

    // ---------------------------------------------------------------- links

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val payload = StremioMapping.decodePayload(data) ?: return false

        val addonBases = StremioSettings.streamAddonUrls()
            .mapNotNull { StremioMapping.normalizeAddonUrl(it) }
            .distinct()
        if (addonBases.isEmpty()) return false

        val type = payload.type.lowercase()
        val manifests = ConcurrentHashMap<String, StremioManifest>()
        val found = AtomicBoolean(false)
        val emitted = AtomicInteger(0)
        val subtitleProbeHash = AtomicReference<String?>(null)
        val subtitleProbeSize = AtomicReference<Long?>(null)
        val seenSubtitleUrls = ConcurrentHashMap.newKeySet<String>()

        // Fan out per addon. Each addon emits its links as soon as it responds so a slow
        // addon never blocks the fast ones and is still waited for (up to STREAM_TIMEOUT_MS).
        // supervisorScope keeps one addon's failure from cancelling the others.
        //
        // Subtitles are fetched concurrently rather than strictly after every stream addon:
        // once the fastest addon that actually supports streams has answered, the subtitle
        // addons are queried. Previously a single slow (90 s) stream addon delayed all
        // subtitles for the whole session.
        //
        // Two gates guard the subtitle fan-out. `firstAddonDone` is released only by a
        // stream-capable addon, so the probe hints exist before querying. `manifestDone` has one
        // signal per addon, released when that addon's manifest fetch has settled; the fan-out
        // reads the shared `manifests` map, so an addon whose manifest has not arrived yet would
        // be silently dropped and its external subtitles lost. A manifest is a single 20 s
        // round-trip (vs. the 90 s stream path), so waiting for all of them keeps the latency
        // goal while making the queried addon set complete.
        //
        // A catalog/meta-only addon (e.g. Cinemeta, first in the list by default) must not
        // release `firstAddonDone`; it would otherwise fire before any probe hint is known.
        // If no addon supports streams, the fallback releases it once every addon has settled.
        val firstAddonDone = CompletableDeferred<Unit>()
        supervisorScope {
            val manifestDone = addonBases.associateWith { CompletableDeferred<Unit>() }
            val addonJobs = addonBases.map { base ->
                launch {
                    var streamAnswered = false
                    try {
                        val manifest = StremioApi.getManifest(base) ?: return@launch
                        manifests[base] = manifest

                        val streamType = StremioMapping.resolveResourceType(manifest, "stream", type)
                            ?: return@launch
                        if (!StremioMapping.idMatchesPrefixes(
                                payload.id,
                                StremioMapping.resourceIdPrefixesFor(manifest, "stream")
                            )
                        ) return@launch

                        val addonName = manifest.name?.takeIf { it.isNotBlank() } ?: hostOf(base)
                        val streams = StremioApi.getStreams(base, streamType, payload.id)
                        // Embedded subtitles travel on the stream itself; emit them as soon as
                        // this addon answers rather than after all addons settle, matching the
                        // streaming semantics of the links above.
                        emitSubtitles(
                            base,
                            StremioMapping.embeddedSubtitles(streams),
                            seenSubtitleUrls,
                            subtitleCallback,
                        )
                        val publicTrackers = if (streams.any { StremioMapping.needsInfoHashMagnet(it) }) {
                            StremioApi.getPublicTrackers()
                        } else {
                            emptyList()
                        }
                        for (candidate in candidatesFor(
                            addonName,
                            streams,
                            subtitleProbeHash,
                            subtitleProbeSize,
                            publicTrackers,
                        )) {
                            // YouTube page URLs are HTML, not media files: handing them to
                            // ExoPlayer as VIDEO always fails. Delegate to CloudStream's YouTube
                            // extractors and drop the link when extraction fails.
                            if (StremioMapping.isYouTubeUrl(candidate.url)) {
                                if (emitYouTube(candidate, subtitleCallback, callback)) {
                                    emitted.incrementAndGet()
                                    found.set(true)
                                }
                                continue
                            }
                            if (emitted.incrementAndGet() > MAX_LINKS) break
                            callback.invoke(
                                newExtractorLink(
                                    candidate.addonName,
                                    candidate.label,
                                    candidate.url,
                                    linkTypeFor(candidate),
                                ) {
                                    this.quality = candidate.quality
                                    this.referer = candidate.referer
                                    this.headers = candidate.headers
                                }
                            )
                            found.set(true)
                        }
                        streamAnswered = true
                    } catch (t: Throwable) {
                        if (t is CancellationException) throw t
                        Log.e(TAG, "Addon $base failed: ${t.message}")
                    } finally {
                        manifestDone.getValue(base).complete(Unit)
                        if (streamAnswered) firstAddonDone.complete(Unit)
                    }
                }
            }
            launch {
                addonJobs.joinAll()
                firstAddonDone.complete(Unit)
            }
            launch {
                firstAddonDone.await()
                // Wait for every manifest so no subtitle-capable addon is left out of the
                // `manifests` snapshot below. Each signal is completed in the addon's `finally`,
                // so an early return or failure still releases it.
                manifestDone.values.forEach { it.await() }
                emitSubtitleLinks(
                    payload,
                    manifests,
                    subtitleProbeHash.get(),
                    subtitleProbeSize.get(),
                    seenSubtitleUrls,
                    subtitleCallback,
                )
            }
        }

        return found.get()
    }

    private fun candidatesFor(
        addonName: String,
        streams: List<StremioStream>,
        subtitleHash: AtomicReference<String?>,
        subtitleSize: AtomicReference<Long?>,
        publicTrackers: List<String>,
    ): List<LinkCandidate> {
        val candidates = ArrayList<LinkCandidate>(streams.size)
        for (stream in streams) {
            // Debrid/direct addons carry a `url`; plain torrent addons only an `infoHash`.
            // The latter becomes a magnet, which Cloudstream's bundled torrent server plays.
            val playbackUrl = StremioMapping.playbackUrlFor(stream)
                ?: StremioMapping.torrentUrlFor(stream, publicTrackers)
                ?: continue
            val headers = StremioMapping.requestHeadersFor(stream)
            val qualityValue = StremioMapping.parseQuality(
                stream.title, stream.name, stream.behaviorHints?.filename
            )
            val displayName = StremioMapping.streamDisplayName(stream).orEmpty().ifBlank { "Stream" }
            val baseLabel = if (displayName.contains(addonName, ignoreCase = true)) displayName
            else "[$addonName] $displayName"
            // Cloudstream's ExtractorLink has no language field, so the addon-derived language
            // tag is prefixed onto the visible link name instead.
            val label = StremioMapping.languageTag(stream)?.let { "$it $baseLabel" } ?: baseLabel
            candidates.add(
                LinkCandidate(
                    addonName = addonName,
                    stream = stream,
                    url = playbackUrl,
                    headers = headers,
                    referer = StremioMapping.extractReferer(headers),
                    quality = if (qualityValue > 0) qualityValue else Qualities.Unknown.value,
                    label = label,
                    torrent = StremioMapping.isTorrentUrl(playbackUrl),
                )
            )
            if (subtitleHash.get() == null || subtitleSize.get() == null) {
                stream.behaviorHints?.videoHash?.takeIf { it.isNotBlank() }?.let {
                    subtitleHash.compareAndSet(null, it)
                }
                stream.behaviorHints?.videoSize?.takeIf { it > 0 }?.let {
                    subtitleSize.compareAndSet(null, it)
                }
            }
        }
        return candidates
    }

    private suspend fun emitSubtitleLinks(
        payload: StremioPayload,
        manifests: Map<String, StremioManifest>,
        videoHash: String?,
        videoSize: Long?,
        seen: MutableSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
    ) {
        val type = payload.type.lowercase()
        val subtitleAddons = manifests.mapNotNull { (base, manifest) ->
            val subtitleType = StremioMapping.resolveResourceType(manifest, "subtitles", type)
                ?: return@mapNotNull null
            if (!StremioMapping.idMatchesPrefixes(
                    payload.id,
                    StremioMapping.resourceIdPrefixesFor(manifest, "subtitles")
                )
            ) return@mapNotNull null
            base to subtitleType
        }

        // Emit each addon's subtitles as soon as that addon answers, mirroring the stream
        // fan-out above. Batching with `awaitAll()` held fast addons' subtitles until the
        // slowest one returned (up to STREAM_TIMEOUT_MS). `seen` is a concurrent set and the
        // callback is already invoked from several stream coroutines, so this is safe.
        coroutineScope {
            subtitleAddons.forEach { (base, subtitleType) ->
                launch {
                    val subtitles = StremioApi.getSubtitles(base, subtitleType, payload.id, videoHash, videoSize)
                    emitSubtitles(base, subtitles, seen, subtitleCallback)
                }
            }
        }
    }

    /** Emits [subtitles], resolving relative URLs against [base] and deduping in [seen]. */
    private suspend fun emitSubtitles(
        base: String?,
        subtitles: List<StremioSubtitle>,
        seen: MutableSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
    ) {
        for (subtitle in subtitles) {
            val rawUrl = subtitle.url?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            val url = StremioMapping.resolveResourceUrl(base, rawUrl)
            if (!seen.add(url)) continue
            subtitleCallback.invoke(newSubtitleFile(StremioMapping.subtitleLanguage(subtitle), url))
        }
    }

    /** Hands a YouTube stream to CloudStream's extractor; false when it produced nothing. */
    private suspend fun emitYouTube(
        candidate: LinkCandidate,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean = try {
        loadExtractor(candidate.url, subtitleCallback, callback)
    } catch (t: CancellationException) {
        throw t
    } catch (t: Throwable) {
        Log.e(TAG, "YouTube extraction failed for ${candidate.url}: ${t.message}")
        false
    }

    /**
     * Link type is derived purely from the URL/filename: no network probe. Probing every
     * candidate with a HEAD request was a large, mostly wasted cost on the device (the
     * tested hosts answer HEAD with 403/404, so the probe almost always fell back to
     * `VIDEO` anyway) and delayed emission by up to `PROBE_TIMEOUT_MS` per candidate,
     * serialized per addon. Magnets are handed to Cloudstream's torrent engine.
     */
    private fun linkTypeFor(candidate: LinkCandidate): ExtractorLinkType {
        if (candidate.torrent) return ExtractorLinkType.MAGNET
        return when (StremioMapping.guessStreamKind(candidate.url, candidate.stream.behaviorHints?.filename)) {
            StremioMapping.StreamKind.HLS -> ExtractorLinkType.M3U8
            StremioMapping.StreamKind.DASH -> ExtractorLinkType.DASH
            StremioMapping.StreamKind.VIDEO -> ExtractorLinkType.VIDEO
        }
    }

    private fun hostOf(base: String): String =
        base.substringAfter("://").substringBefore('/').ifBlank { base }
}
