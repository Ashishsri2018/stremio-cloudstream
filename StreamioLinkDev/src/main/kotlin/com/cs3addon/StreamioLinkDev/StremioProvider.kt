package com.cs3addon.StreamioLinkDev

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
import java.util.concurrent.atomic.AtomicLong

/**
 * Cloudstream provider that plays content through Stremio addons.
 *
 * - One catalog addon (default: Cinemeta) is used for the home page, search and metadata.
 * - Every configured source addon (plus the catalog addon when enabled) is queried for streams.
 */
class StremioProvider : MainAPI() {
    override var mainUrl = StremioSettings.DEFAULT_CATALOG_URL
    override var name = "StreamioLink Dev"
    override var lang = "en"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Others)
    override val loadLinksTimeoutMs = 180_000L

    /** A stuck catalog must not hold the whole home load for the default 120 s. */
    override val getMainPageTimeoutMs = 90_000L
    override val searchTimeoutMs = 60_000L

    private companion object {
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

    /** Monotonic id for diagnostics: distinguishes the first Play from a later Reload mirrors. */
    private val loadLinksCalls = AtomicInteger(0)

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
            catalogTabsBase = catalogBase
        }
        catalogTabs = catalogs.mapNotNull { catalog ->
            val type = catalog.type ?: return@mapNotNull null
            val id = catalog.id ?: return@mapNotNull null
            MainPageData(
                name = catalogTitle(manifest, catalog, name),
                data = StremioMapping.encodeCatalogRef(type, id),
                horizontalImages = false,
            )
        }

        val rows = coroutineScope {
            catalogs.map { catalog ->
                async { catalogSemaphore.withPermit { fetchCatalogRow(catalogBase, manifest, catalog, page) } }
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
        val items = try {
            StremioApi.getCatalog(catalogBase, ref.type, ref.id, if (offset > 0) "skip=$offset" else null)
        } catch (t: CancellationException) {
            throw t
        } catch (t: Throwable) {
            StremioDebug.e("catalog ${ref.id} page $page failed: ${t.message}")
            emptyList()
        }

        if (skipSupported) catalogOffsets[key] = offset + items.size

        val searches = items.mapNotNull { toSearchResponse(it, ref.type) }
        if (searches.isEmpty()) return newHomePageResponse(emptyList(), false)
        val title = requestName.takeIf { it.isNotBlank() }
            ?: (manifest?.let { catalog?.let { c -> catalogTitle(it, c, name) } })
            ?: ref.id
        return newHomePageResponse(listOf(HomePageList(title, searches)), skipSupported)
    }

    private suspend fun fetchCatalogRow(
        catalogBase: String,
        manifest: StremioManifest,
        catalog: StremioCatalog,
        page: Int,
    ): HomePageList? {
        val type = catalog.type ?: return null
        val id = catalog.id ?: return null
        val key = StremioMapping.encodeCatalogRef(type, id)
        val offset = if (page > 1) (catalogOffsets[key] ?: ((page - 1) * DEFAULT_PAGE_SIZE)) else 0
        val items = try {
            StremioApi.getCatalog(catalogBase, type, id, if (offset > 0) "skip=$offset" else null)
        } catch (t: CancellationException) {
            throw t
        } catch (t: Throwable) {
            StremioDebug.e("home catalog $id failed: ${t.message}")
            return null
        }
        if (StremioMapping.catalogSupportsSkip(catalog)) catalogOffsets[key] = offset + items.size
        val searches = items.mapNotNull { toSearchResponse(it, type) }
        if (searches.isEmpty()) return null
        return HomePageList(catalogTitle(manifest, catalog, name), searches)
    }

    private fun catalogTitle(manifest: StremioManifest, catalog: StremioCatalog, providerName: String): String {
        catalog.name?.takeIf { it.isNotBlank() }?.let { return it }
        val addon = manifest.name?.takeIf { it.isNotBlank() } ?: providerName
        val type = catalog.type.orEmpty().replaceFirstChar { it.uppercase() }
        return "$addon $type".trim()
    }

    private fun toSearchResponse(meta: StremioMetaPreview, fallbackType: String): SearchResponse? {
        val id = meta.id ?: return null
        val type = meta.type ?: fallbackType
        val payload = StremioMapping.encodePayload(id, type)
        val title = meta.name?.takeIf { it.isNotBlank() } ?: id
        return if (type.equals("series", ignoreCase = true)) {
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
                            StremioApi.getCatalog(base, type, id, "search=$encodedQuery")
                                .mapNotNull { toSearchResponse(it, type) }
                        }
                    } catch (t: CancellationException) {
                        throw t
                    } catch (t: Throwable) {
                        StremioDebug.e("search catalog $id failed: ${t.message}")
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

        return if (payload.type.equals("series", ignoreCase = true)) {
            val episodes = meta.videos.orEmpty().mapNotNull { toEpisode(payload.type, it) }
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
        val callId = loadLinksCalls.incrementAndGet()
        val startedAt = System.currentTimeMillis()
        val payload = StremioMapping.decodePayload(data) ?: run {
            StremioDebug.w("[#$callId] loadLinks BAD payload data=${StremioDebug.clip(data, 300)}")
            return false
        }

        val rawAddonUrls = StremioSettings.streamAddonUrls()
        val addonBases = rawAddonUrls
            .mapNotNull { StremioMapping.normalizeAddonUrl(it) }
            .distinct()
        StremioDebug.d(
            "[#$callId] loadLinks START id=${payload.id} type=${payload.type} casting=$isCasting " +
                "rawAddons=${rawAddonUrls.size} addons=${addonBases.size} " +
                "hosts=${StremioDebug.join(addonBases.map { StremioDebug.host(it) })} data=$data"
        )
        if (addonBases.isEmpty()) {
            StremioDebug.w("[#$callId] loadLinks NO ADDONS configured")
            return false
        }

        val type = payload.type.lowercase()
        val manifests = ConcurrentHashMap<String, StremioManifest>()
        val found = AtomicBoolean(false)
        val emitted = AtomicInteger(0)
        val subtitleCount = AtomicInteger(0)
        val firstLinkMs = AtomicLong(-1L)
        val probe = ProbeHints()
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
                    val host = hostOf(base)
                    val addonStarted = System.currentTimeMillis()
                    var streamAnswered = false
                    try {
                        StremioDebug.d("[#$callId] addon[$host] manifest BEGIN")
                        val manifest = StremioApi.getManifest(base)
                        if (manifest == null) {
                            StremioDebug.w("[#$callId] addon[$host] manifest NULL -> skip")
                            return@launch
                        }
                        manifests[base] = manifest

                        val resourceNames = StremioMapping.manifestResourceNames(manifest)
                        val streamTypes = StremioMapping.resourceTypesFor(manifest, "stream")
                        val streamPrefixes = StremioMapping.resourceIdPrefixesFor(manifest, "stream")
                        val idMatches = StremioMapping.idMatchesPrefixes(payload.id, streamPrefixes)
                        val supportsStream = StremioMapping.manifestSupportsResource(manifest, "stream") &&
                            type in streamTypes.map { it.lowercase() } &&
                            idMatches
                        StremioDebug.d(
                            "[#$callId] addon[$host] manifest name=${manifest.name} " +
                                "resources=${StremioDebug.join(resourceNames)} " +
                                "streamTypes=${StremioDebug.join(streamTypes)} " +
                                "streamPrefixes=${StremioDebug.join(streamPrefixes)} " +
                                "idMatches=$idMatches supportsStream=$supportsStream"
                        )
                        if (!supportsStream) {
                            StremioDebug.d(
                                "[#$callId] addon[$host] STREAM SKIP (resource/type/prefix mismatch)"
                            )
                            return@launch
                        }

                        val addonName = manifest.name?.takeIf { it.isNotBlank() } ?: host
                        val streamsStarted = System.currentTimeMillis()
                        val streams = StremioApi.getStreams(base, type, payload.id)
                        val embedded = StremioMapping.embeddedSubtitles(streams)
                        StremioDebug.d(
                            "[#$callId] addon[$host] streams=${streams.size} " +
                                "embeddedSubs=${embedded.size} dur=${StremioDebug.ms(streamsStarted)}ms " +
                                "breakdown=${streamBreakdown(streams)}"
                        )
                        // Embedded subtitles travel on the stream itself; emit them as soon as
                        // this addon answers rather than after all addons settle, matching the
                        // streaming semantics of the links above.
                        val embeddedEmitted = emitSubtitles(
                            callId,
                            "embedded:${addonName}",
                            embedded,
                            seenSubtitleUrls,
                            subtitleCallback,
                        )
                        subtitleCount.addAndGet(embeddedEmitted)

                        val candidates = candidatesFor(callId, addonName, streams, probe)
                        StremioDebug.d(
                            "[#$callId] addon[$host] candidates=${candidates.size} " +
                                "skipped=${streams.size - candidates.size}"
                        )
                        val before = emitted.get()
                        for (candidate in candidates) {
                            val index = emitted.incrementAndGet()
                            if (index > MAX_LINKS) {
                                StremioDebug.w(
                                    "[#$callId] MAX_LINKS ($MAX_LINKS) reached, dropping rest"
                                )
                                break
                            }
                            val kind = linkTypeFor(candidate)
                            firstLinkMs.compareAndSet(-1L, StremioDebug.ms(startedAt))
                            StremioDebug.d(
                                "[#$callId] LINK #$index addon=$addonName kind=$kind " +
                                    "torrent=${candidate.torrent} q=${candidate.quality} " +
                                    "label=${StremioDebug.clip(candidate.label, 120)} " +
                                    "url=${StremioDebug.clip(candidate.url, 240)}"
                            )
                            callback.invoke(
                                newExtractorLink(
                                    candidate.addonName,
                                    candidate.label,
                                    candidate.url,
                                    kind,
                                ) {
                                    this.quality = candidate.quality
                                    this.referer = candidate.referer
                                    this.headers = candidate.headers
                                }
                            )
                            found.set(true)
                        }
                        StremioDebug.d(
                            "[#$callId] addon[$host] DONE emitted=${emitted.get() - before} " +
                                "addonDur=${StremioDebug.ms(addonStarted)}ms"
                        )
                        streamAnswered = true
                    } catch (t: Throwable) {
                        if (t is CancellationException) throw t
                        StremioDebug.e(
                            "[#$callId] addon[$host] FAIL ${t::class.java.simpleName}: ${t.message}"
                        )
                    } finally {
                        manifestDone.getValue(base).complete(Unit)
                        if (streamAnswered) firstAddonDone.complete(Unit)
                    }
                }
            }
            launch {
                addonJobs.joinAll()
                StremioDebug.d("[#$callId] all addon jobs settled")
                firstAddonDone.complete(Unit)
            }
            launch {
                firstAddonDone.await()
                // Wait for every manifest so no subtitle-capable addon is left out of the
                // `manifests` snapshot below. Each signal is completed in the addon's `finally`,
                // so an early return or failure still releases it.
                manifestDone.values.forEach { it.await() }
                val subs = emitSubtitleLinks(
                    callId,
                    payload,
                    manifests,
                    probe,
                    seenSubtitleUrls,
                    subtitleCallback,
                )
                subtitleCount.addAndGet(subs)
            }
        }

        val totalMs = StremioDebug.ms(startedAt)
        val firstLink = firstLinkMs.get()
        StremioDebug.d(
            "[#$callId] loadLinks END found=${found.get()} emitted=${emitted.get()} " +
                "subs=${subtitleCount.get()} total=${totalMs}ms " +
                "firstLink=${if (firstLink < 0) "none" else "${firstLink}ms"}"
        )
        if (!found.get()) {
            StremioDebug.w("[#$callId] loadLinks NO LINKS total=${totalMs}ms")
        }
        return found.get()
    }

    private fun candidatesFor(
        callId: Int,
        addonName: String,
        streams: List<StremioStream>,
        probe: ProbeHints,
    ): List<LinkCandidate> {
        val candidates = ArrayList<LinkCandidate>(streams.size)
        streams.forEachIndexed { index, stream ->
            // Debrid/direct addons carry a `url`; plain torrent addons only an `infoHash`.
            // The latter becomes a magnet, which Cloudstream's bundled torrent server plays.
            val playbackUrl = StremioMapping.playbackUrlFor(stream)
                ?: StremioMapping.torrentUrlFor(stream)
            if (playbackUrl == null) {
                StremioDebug.d(
                    "[#$callId] addon[$addonName] SKIP stream#$index " +
                        "url=${StremioDebug.clip(stream.url, 160)} " +
                        "infoHash=${StremioDebug.clip(stream.infoHash, 60)} " +
                        "ytId=${StremioDebug.clip(stream.ytId, 40)} (no playable url)"
                )
                return@forEachIndexed
            }
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

            val hash = stream.behaviorHints?.videoHash?.takeIf { it.isNotBlank() }
            val size = stream.behaviorHints?.videoSize?.takeIf { it > 0 }
            if (probe.offer(hash, size, "$addonName#$index")) {
                StremioDebug.d(
                    "[#$callId] probe updated hash=${probe.videoHash} (${probe.hashSource}) " +
                        "size=${probe.videoSize} (${probe.sizeSource})"
                )
            } else if (hash != null || size != null) {
                StremioDebug.d(
                    "[#$callId] probe ignored stream#$index hash=$hash size=$size " +
                        "(already have ${probe.videoHash}/${probe.videoSize})"
                )
            }
        }
        return candidates
    }

    /**
     * First non-empty `videoHash`/`videoSize` seen across all stream addons. Stremio's
     * subtitle extra expects a matching pair, so both the values and where they came from
     * are tracked and logged (the dev build uses this to diagnose subtitle 404s).
     */
    private class ProbeHints {
        @Volatile var videoHash: String? = null
            private set
        @Volatile var videoSize: Long? = null
            private set
        @Volatile var hashSource: String? = null
            private set
        @Volatile var sizeSource: String? = null
            private set

        /** Returns true when a still-missing value was supplied by this call. */
        @Synchronized
        fun offer(hash: String?, size: Long?, source: String): Boolean {
            var changed = false
            if (hash != null && videoHash == null) {
                videoHash = hash
                hashSource = source
                changed = true
            }
            if (size != null && videoSize == null) {
                videoSize = size
                sizeSource = source
                changed = true
            }
            return changed
        }
    }

    /** Counts how each addon's raw streams were classified, for the per-addon log line. */
    private fun streamBreakdown(streams: List<StremioStream>): String {
        var direct = 0
        var youtube = 0
        var magnet = 0
        var badUrl = 0
        var empty = 0
        for (stream in streams) {
            val playbackUrl = StremioMapping.playbackUrlFor(stream)
            if (playbackUrl != null) {
                if (playbackUrl.startsWith("https://www.youtube.com/", ignoreCase = true)) {
                    youtube++
                } else {
                    direct++
                }
            } else if (!stream.infoHash.isNullOrBlank()) {
                magnet++
            } else if (!stream.url.isNullOrBlank()) {
                badUrl++
            } else {
                empty++
            }
        }
        return "direct=$direct yt=$youtube magnet=$magnet badUrl=$badUrl noUrl=$empty"
    }

    private suspend fun emitSubtitleLinks(
        callId: Int,
        payload: StremioPayload,
        manifests: Map<String, StremioManifest>,
        probe: ProbeHints,
        seen: MutableSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
    ): Int {
        val type = payload.type.lowercase()
        StremioDebug.d(
            "[#$callId] SUBS fanout BEGIN id=${payload.id} manifests=${manifests.size} " +
                "probeHash=${probe.videoHash} (${probe.hashSource}) " +
                "probeSize=${probe.videoSize} (${probe.sizeSource})"
        )

        val subtitleAddons = manifests.filter { (base, manifest) ->
            val hasResource = StremioMapping.manifestSupportsResource(manifest, "subtitles")
            val types = StremioMapping.resourceTypesFor(manifest, "subtitles")
            val typeMatches = type in types.map { it.lowercase() }
            val prefixes = StremioMapping.resourceIdPrefixesFor(manifest, "subtitles")
            val idMatches = StremioMapping.idMatchesPrefixes(payload.id, prefixes)
            val included = hasResource && typeMatches && idMatches
            StremioDebug.d(
                "[#$callId] SUBS check addon[${hostOf(base)}] resource=$hasResource " +
                    "types=${StremioDebug.join(types)} typeMatches=$typeMatches " +
                    "prefixes=${StremioDebug.join(prefixes)} idMatches=$idMatches included=$included"
            )
            included
        }

        if (subtitleAddons.isEmpty()) {
            StremioDebug.w("[#$callId] SUBS NO CAPABLE ADDONS")
            return 0
        }
        StremioDebug.d(
            "[#$callId] SUBS addons=${StremioDebug.join(subtitleAddons.keys.map { hostOf(it) })}"
        )

        val total = AtomicInteger(0)
        // Emit each addon's subtitles as soon as that addon answers, mirroring the stream
        // fan-out above. Batching with `awaitAll()` held fast addons' subtitles until the
        // slowest one returned (up to STREAM_TIMEOUT_MS). `seen` is a concurrent set and the
        // callback is already invoked from several stream coroutines, so this is safe.
        coroutineScope {
            subtitleAddons.forEach { (base, _) ->
                launch {
                    val subtitles = StremioApi.getSubtitles(
                        base, type, payload.id, probe.videoHash, probe.videoSize
                    )
                    val emitted = emitSubtitles(
                        callId,
                        "ext:${hostOf(base)}",
                        subtitles,
                        seen,
                        subtitleCallback,
                    )
                    total.addAndGet(emitted)
                }
            }
        }
        StremioDebug.d("[#$callId] SUBS fanout END emitted=${total.get()}")
        return total.get()
    }

    /** Emits [subtitles], deduping by URL against the shared [seen] set. Returns the count. */
    private suspend fun emitSubtitles(
        callId: Int,
        source: String,
        subtitles: List<StremioSubtitle>,
        seen: MutableSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
    ): Int {
        var emitted = 0
        var duplicates = 0
        var dropped = 0
        for (subtitle in subtitles) {
            val url = subtitle.url?.trim()?.takeIf { it.isNotEmpty() }
            if (url == null) {
                dropped++
                StremioDebug.d("[#$callId] SUB DROP src=$source lang=${subtitle.lang} (no url)")
                continue
            }
            if (!seen.add(url)) {
                duplicates++
                StremioDebug.d("[#$callId] SUB DUP src=$source url=${StremioDebug.clip(url, 200)}")
                continue
            }
            val language = StremioMapping.subtitleLanguage(subtitle)
            StremioDebug.d(
                "[#$callId] SUB EMIT src=$source lang=$language id=${subtitle.id} " +
                    "url=${StremioDebug.clip(url, 240)}"
            )
            subtitleCallback.invoke(newSubtitleFile(language, url))
            emitted++
        }
        if (subtitles.isNotEmpty() || dropped > 0) {
            StremioDebug.d(
                "[#$callId] SUB src=$source raw=${subtitles.size} emitted=$emitted " +
                    "dup=$duplicates dropped=$dropped"
            )
        }
        return emitted
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
