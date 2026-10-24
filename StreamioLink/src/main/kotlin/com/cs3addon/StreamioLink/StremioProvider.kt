package com.cs3addon.StreamioLink

import com.lagradost.api.Log
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageData
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addDate
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
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
 * - One catalog addon (default: Cinemeta) is used for the home page, search and metadata.
 * - Every configured source addon (plus the catalog addon when enabled) is queried for streams.
 */
class StremioProvider : MainAPI() {
    override var mainUrl = StremioSettings.DEFAULT_CATALOG_URL
    override var name = "StreamioLink"
    override var lang = "en"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Others)
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

    /** Single anonymous tab used until the catalog manifest has been read once. */
    private val placeholderPage = listOf(MainPageData("", "", false))

    /** Catalog tabs for the current catalog addon, populated on the first home load. */
    @Volatile private var catalogTabs: List<MainPageData> = emptyList()

    @Volatile private var catalogTabsBase: String? = null

    /** Per-catalog item offset, advanced by the number of items the addon actually returned. */
    private val catalogOffsets = ConcurrentHashMap<String, Int>()

    override val mainPage: List<MainPageData>
        get() {
            // Tabs are only valid for the catalog addon they were fetched from; a URL change
            // falls back to the single placeholder tab so the next home load refetches them.
            if (catalogTabsBase != StremioSettings.catalogUrl()) return placeholderPage
            return catalogTabs.ifEmpty { placeholderPage }
        }

    // ---------------------------------------------------------------- home page

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val catalogBase = StremioSettings.catalogUrl()
        val ref = StremioMapping.decodeCatalogRef(request.data)
        // Cloudstream expands a row by asking for one catalog (encoded in request.data).
        // The initial load has no ref and returns every catalog.
        return if (ref != null) singleCatalogPage(catalogBase, ref, request.name, page)
        else allCatalogsPage(catalogBase, page)
    }

    private suspend fun allCatalogsPage(catalogBase: String, page: Int): HomePageResponse? {
        val manifest = StremioApi.getManifest(catalogBase) ?: return newHomePageResponse(emptyList(), false)
        val catalogs = manifest.catalogs.orEmpty().filter { StremioMapping.catalogCanLoadHome(it) }
        if (catalogs.isEmpty()) return newHomePageResponse(emptyList(), false)

        if (catalogTabsBase != catalogBase) {
            catalogOffsets.clear()
        }
        val addonName = manifest.name?.takeIf { it.isNotBlank() } ?: name
        // Titles are computed once and reused for both the tabs and the rows. Identical names
        // across media types get a "(Movies)/(Series)" suffix; without it Cloudstream's
        // name-keyed row map drops the earlier (movie) row in favour of the later series one.
        val titled = StremioMapping.catalogTitles(catalogs, addonName)
        catalogTabs = titled.mapNotNull { (catalog, title) ->
            val type = catalog.type ?: return@mapNotNull null
            val id = catalog.id ?: return@mapNotNull null
            MainPageData(
                name = title,
                data = StremioMapping.encodeCatalogRef(type, id),
                horizontalImages = false,
            )
        }
        // Publish the tabs before the base so a concurrent [mainPage] read never pairs the new
        // base with the previous addon's tabs.
        catalogTabsBase = catalogBase

        val rows = coroutineScope {
            titled.map { (catalog, title) ->
                async { catalogSemaphore.withPermit { fetchCatalogRow(catalogBase, catalog, page, title) } }
            }.awaitAll().filterNotNull()
        }
        val hasNext = catalogs.any { StremioMapping.catalogSupportsSkip(it) }
        return newHomePageResponse(rows, hasNext)
    }

    private suspend fun singleCatalogPage(
        catalogBase: String,
        ref: StremioCatalogRef,
        requestName: String,
        page: Int,
    ): HomePageResponse? {
        val key = StremioMapping.encodeCatalogRef(ref.type, ref.id)
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

        val searches = items.mapNotNull { toSearchResponse(it, ref.type) }
        if (searches.isEmpty()) return newHomePageResponse(emptyList(), false)
        val addonName = manifest?.name?.takeIf { it.isNotBlank() } ?: name
        val title = requestName.takeIf { it.isNotBlank() }
            ?: (catalog?.let { StremioMapping.catalogBaseTitle(it, addonName) })
            ?: ref.id
        return newHomePageResponse(listOf(HomePageList(title, searches)), skipSupported)
    }

    private suspend fun fetchCatalogRow(
        catalogBase: String,
        catalog: StremioCatalog,
        page: Int,
        title: String,
    ): HomePageList? {
        val type = catalog.type ?: return null
        val id = catalog.id ?: return null
        val key = StremioMapping.encodeCatalogRef(type, id)
        val offset = if (page > 1) (catalogOffsets[key] ?: ((page - 1) * DEFAULT_PAGE_SIZE)) else 0
        val extra = StremioMapping.catalogExtra(StremioMapping.catalogPresetExtra(catalog), offset)
        val items = try {
            StremioApi.getCatalog(catalogBase, type, id, extra)
        } catch (t: CancellationException) {
            throw t
        } catch (t: Throwable) {
            Log.e(TAG, "Home catalog $id failed: ${t.message}")
            return null
        }
        if (StremioMapping.catalogSupportsSkip(catalog)) catalogOffsets[key] = offset + items.size
        val searches = items.mapNotNull { toSearchResponse(it, type) }
        if (searches.isEmpty()) return null
        return HomePageList(title, searches)
    }

    private fun toSearchResponse(meta: StremioMetaPreview, fallbackType: String): SearchResponse? {
        val id = meta.id ?: return null
        val type = meta.type ?: fallbackType
        val payload = StremioMapping.encodePayload(id, type)
        val title = meta.name?.takeIf { it.isNotBlank() } ?: id
        return if (StremioMapping.usesEpisodes(type)) {
            newTvSeriesSearchResponse(title, payload) {
                this.posterUrl = meta.poster
                this.year = StremioMapping.yearFromReleaseInfo(meta.releaseInfo)
            }
        } else {
            newMovieSearchResponse(title, payload) {
                this.posterUrl = meta.poster
                this.year = StremioMapping.yearFromReleaseInfo(meta.releaseInfo)
            }
        }
    }

    // ---------------------------------------------------------------- search

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        val encoded = StremioMapping.encodeQueryValue(query)

        val fromCatalog = searchAddon(StremioSettings.catalogUrl(), encoded)
        if (fromCatalog.isNotEmpty()) return fromCatalog

        val metaBase = StremioSettings.metaUrl()
        if (StremioMapping.normalizeAddonUrl(metaBase) != StremioMapping.normalizeAddonUrl(StremioSettings.catalogUrl())) {
            return searchAddon(metaBase, encoded)
        }
        return emptyList()
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
                                .mapNotNull { toSearchResponse(it, type) }
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
        // the episode payloads, so series are not loaded as movies. Live `tv` channels have no
        // videos and are movie-like single streams.
        val type = meta.type?.takeIf { it.isNotBlank() } ?: payload.type

        return if (StremioMapping.usesEpisodes(type)) {
            val episodes = meta.videos.orEmpty().mapNotNull { toEpisode(type, it) }
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = meta.poster
                this.backgroundPosterUrl = meta.background
                this.plot = meta.description
                this.year = StremioMapping.yearFromReleaseInfo(meta.releaseInfo)
                this.tags = meta.genres
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = meta.poster
                this.backgroundPosterUrl = meta.background
                this.plot = meta.description
                this.year = StremioMapping.yearFromReleaseInfo(meta.releaseInfo)
                this.tags = meta.genres
            }
        }
    }

    private suspend fun fetchMeta(payload: StremioPayload): StremioMetaDetails? {
        val catalogBase = StremioSettings.catalogUrl()
        StremioApi.getMeta(catalogBase, payload.type, payload.id)?.let { return it }

        val metaBase = StremioSettings.metaUrl()
        if (StremioMapping.normalizeAddonUrl(metaBase) != StremioMapping.normalizeAddonUrl(catalogBase)) {
            return StremioApi.getMeta(metaBase, payload.type, payload.id)
        }
        return null
    }

    private fun toEpisode(parentType: String, video: StremioVideo): Episode? {
        val videoId = video.id ?: return null
        return newEpisode(StremioMapping.encodePayload(videoId, parentType)) {
            this.name = StremioMapping.episodeName(video) ?: videoId
            this.season = video.season ?: 1
            this.episode = video.episode ?: video.number ?: 1
            this.posterUrl = video.thumbnail
            this.description = video.overview
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

                        val supportsStream = StremioMapping.manifestSupportsResource(manifest, "stream") &&
                            type in StremioMapping.resourceTypesFor(manifest, "stream").map { it.lowercase() } &&
                            StremioMapping.idMatchesPrefixes(
                                payload.id,
                                StremioMapping.resourceIdPrefixesFor(manifest, "stream")
                            )
                        if (!supportsStream) return@launch

                        val addonName = manifest.name?.takeIf { it.isNotBlank() } ?: hostOf(base)
                        val streams = StremioApi.getStreams(base, type, payload.id)
                        // Embedded subtitles travel on the stream itself; emit them as soon as
                        // this addon answers rather than after all addons settle, matching the
                        // streaming semantics of the links above.
                        emitSubtitles(
                            StremioMapping.embeddedSubtitles(streams),
                            seenSubtitleUrls,
                            subtitleCallback,
                        )
                        for (candidate in candidatesFor(addonName, streams, subtitleProbeHash, subtitleProbeSize)) {
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
    ): List<LinkCandidate> {
        val candidates = ArrayList<LinkCandidate>(streams.size)
        for (stream in streams) {
            // Debrid/direct addons carry a `url`; plain torrent addons only an `infoHash`.
            // The latter becomes a magnet, which Cloudstream's bundled torrent server plays.
            val playbackUrl = StremioMapping.playbackUrlFor(stream)
                ?: StremioMapping.torrentUrlFor(stream)
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
        val subtitleAddons = manifests.filter { (_, manifest) ->
            StremioMapping.manifestSupportsResource(manifest, "subtitles") &&
                type in StremioMapping.resourceTypesFor(manifest, "subtitles").map { it.lowercase() } &&
                StremioMapping.idMatchesPrefixes(payload.id, StremioMapping.resourceIdPrefixesFor(manifest, "subtitles"))
        }

        // Emit each addon's subtitles as soon as that addon answers, mirroring the stream
        // fan-out above. Batching with `awaitAll()` held fast addons' subtitles until the
        // slowest one returned (up to STREAM_TIMEOUT_MS). `seen` is a concurrent set and the
        // callback is already invoked from several stream coroutines, so this is safe.
        coroutineScope {
            subtitleAddons.forEach { (base, _) ->
                launch {
                    val subtitles = StremioApi.getSubtitles(base, type, payload.id, videoHash, videoSize)
                    emitSubtitles(subtitles, seen, subtitleCallback)
                }
            }
        }
    }

    /** Emits [subtitles], deduping by URL against the shared [seen] set. */
    private suspend fun emitSubtitles(
        subtitles: List<StremioSubtitle>,
        seen: MutableSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
    ) {
        for (subtitle in subtitles) {
            val url = subtitle.url?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            if (!seen.add(url)) continue
            subtitleCallback.invoke(newSubtitleFile(StremioMapping.subtitleLanguage(subtitle), url))
        }
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
