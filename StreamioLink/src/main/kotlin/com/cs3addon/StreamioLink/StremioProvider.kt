package com.cs3addon.StreamioLink

import com.lagradost.api.Log
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addDate
import com.lagradost.cloudstream3.app
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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

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
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private companion object {
        const val TAG = "StreamioLink"
        const val PAGE_SIZE = 100
        const val MAX_PROBES = 15
        const val PROBE_CONCURRENCY = 5
        const val PROBE_TIMEOUT_MS = 3_500L
        const val MAX_LINKS = 120
    }

    private data class LinkCandidate(
        val addonName: String,
        val stream: StremioStream,
        val url: String,
        val headers: Map<String, String>,
        val referer: String,
        val quality: Int,
        val label: String,
    )

    private val linkTypeCache = ConcurrentHashMap<String, ExtractorLinkType>()

    // ---------------------------------------------------------------- home page

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val catalogBase = StremioSettings.catalogUrl()
        val manifest = StremioApi.getManifest(catalogBase) ?: return newHomePageResponse(emptyList(), false)
        val catalogs = manifest.catalogs.orEmpty().filter { StremioMapping.catalogCanLoadHome(it) }
        if (catalogs.isEmpty()) return newHomePageResponse(emptyList(), false)

        val extra = if (page > 1) "skip=${(page - 1) * PAGE_SIZE}" else null
        val rows = coroutineScope {
            catalogs.map { catalog ->
                async {
                    val type = catalog.type ?: return@async null
                    val catalogId = catalog.id ?: return@async null
                    try {
                        val items = StremioApi.getCatalog(catalogBase, type, catalogId, extra)
                            .mapNotNull { toSearchResponse(it, type) }
                        if (items.isEmpty()) null
                        else HomePageList(catalogTitle(manifest, catalog, name), items)
                    } catch (t: Throwable) {
                        Log.e(TAG, "Home catalog $catalogId failed: ${t.message}")
                        null
                    }
                }
            }.awaitAll().filterNotNull()
        }
        return newHomePageResponse(rows, false)
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
                        StremioApi.getCatalog(base, type, id, "search=$encodedQuery")
                            .mapNotNull { toSearchResponse(it, type) }
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
            this.name = video.name?.takeIf { it.isNotBlank() } ?: videoId
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

        val manifests = coroutineScope {
            addonBases.map { base ->
                async { base to StremioApi.getManifest(base) }
            }.awaitAll()
        }.mapNotNull { (base, manifest) -> manifest?.let { base to it } }.toMap()

        val type = payload.type.lowercase()
        val streamAddons = manifests.filter { (_, manifest) ->
            StremioMapping.manifestSupportsResource(manifest, "stream") &&
                type in StremioMapping.resourceTypesFor(manifest, "stream").map { it.lowercase() } &&
                StremioMapping.idMatchesPrefixes(payload.id, StremioMapping.resourceIdPrefixesFor(manifest, "stream"))
        }

        val streamsByAddon = coroutineScope {
            streamAddons.map { (base, manifest) ->
                async {
                    val addonName = manifest.name?.takeIf { it.isNotBlank() } ?: hostOf(base)
                    addonName to StremioApi.getStreams(base, type, payload.id)
                }
            }.awaitAll()
        }

        val candidates = mutableListOf<LinkCandidate>()
        var subtitleProbeHash: String? = null
        var subtitleProbeSize: Long? = null

        for ((addonName, streams) in streamsByAddon) {
            for (stream in streams) {
                if (candidates.size >= MAX_LINKS) break
                val playbackUrl = StremioMapping.playbackUrlFor(stream) ?: continue
                val headers = StremioMapping.requestHeadersFor(stream)
                val qualityValue = StremioMapping.parseQuality(
                    stream.title, stream.name, stream.behaviorHints?.filename
                )
                val displayName = StremioMapping.streamDisplayName(stream).orEmpty().ifBlank { "Stream" }
                val label = if (displayName.contains(addonName, ignoreCase = true)) displayName
                else "[$addonName] $displayName"
                candidates.add(
                    LinkCandidate(
                        addonName = addonName,
                        stream = stream,
                        url = playbackUrl,
                        headers = headers,
                        referer = StremioMapping.extractReferer(headers),
                        quality = if (qualityValue > 0) qualityValue else Qualities.Unknown.value,
                        label = label,
                    )
                )
                if (subtitleProbeHash == null && subtitleProbeSize == null) {
                    stream.behaviorHints?.videoHash?.takeIf { it.isNotBlank() }?.let { subtitleProbeHash = it }
                    stream.behaviorHints?.videoSize?.takeIf { it > 0 }?.let { subtitleProbeSize = it }
                }
            }
        }

        emitSubtitleLinks(payload, manifests, subtitleProbeHash, subtitleProbeSize, subtitleCallback)

        if (candidates.isEmpty()) return true

        val probeBudget = AtomicInteger(MAX_PROBES)
        val probeSemaphore = Semaphore(PROBE_CONCURRENCY)
        val resolved = coroutineScope {
            candidates.map { candidate ->
                async { candidate to resolveLinkType(candidate, probeSemaphore, probeBudget) }
            }.awaitAll()
        }

        var found = false
        for ((candidate, linkType) in resolved) {
            callback.invoke(
                newExtractorLink(candidate.addonName, candidate.label, candidate.url, linkType) {
                    this.quality = candidate.quality
                    this.referer = candidate.referer
                    this.headers = candidate.headers
                }
            )
            found = true
        }
        return found
    }

    private suspend fun emitSubtitleLinks(
        payload: StremioPayload,
        manifests: Map<String, StremioManifest>,
        videoHash: String?,
        videoSize: Long?,
        subtitleCallback: (SubtitleFile) -> Unit,
    ) {
        val type = payload.type.lowercase()
        val subtitleAddons = manifests.filter { (_, manifest) ->
            StremioMapping.manifestSupportsResource(manifest, "subtitles") &&
                type in StremioMapping.resourceTypesFor(manifest, "subtitles").map { it.lowercase() } &&
                StremioMapping.idMatchesPrefixes(payload.id, StremioMapping.resourceIdPrefixesFor(manifest, "subtitles"))
        }
        if (subtitleAddons.isEmpty()) return

        val subtitleLists = coroutineScope {
            subtitleAddons.map { (base, _) ->
                async { StremioApi.getSubtitles(base, type, payload.id, videoHash, videoSize) }
            }.awaitAll()
        }

        val seen = mutableSetOf<String>()
        for (subtitles in subtitleLists) {
            for (subtitle in subtitles) {
                val url = subtitle.url?.takeIf { it.isNotBlank() } ?: continue
                if (!seen.add(url)) continue
                val language = subtitle.lang?.takeIf { it.isNotBlank() } ?: "Unknown"
                subtitleCallback.invoke(newSubtitleFile(language, url))
            }
        }
    }

    private suspend fun resolveLinkType(
        candidate: LinkCandidate,
        probeSemaphore: Semaphore,
        probeBudget: AtomicInteger,
    ): ExtractorLinkType {
        linkTypeCache[candidate.url]?.let { return it }
        when (StremioMapping.guessStreamKind(candidate.url, candidate.stream.behaviorHints?.filename)) {
            StremioMapping.StreamKind.HLS -> return ExtractorLinkType.M3U8
            StremioMapping.StreamKind.DASH -> return ExtractorLinkType.DASH
            StremioMapping.StreamKind.VIDEO -> Unit
        }
        if (probeBudget.decrementAndGet() < 0) return ExtractorLinkType.VIDEO

        val linkType = probeSemaphore.withPermit {
            try {
                val response = withTimeoutOrNull(PROBE_TIMEOUT_MS) {
                    app.head(candidate.url, headers = candidate.headers, referer = candidate.referer)
                }
                val contentType = (
                    response?.headers?.get("Content-Type")
                        ?: response?.headers?.get("content-type")
                        ?: ""
                    ).lowercase()
                when {
                    contentType.contains("mpegurl") || contentType.contains("m3u8") -> ExtractorLinkType.M3U8
                    contentType.contains("dash") -> ExtractorLinkType.DASH
                    else -> ExtractorLinkType.VIDEO
                }
            } catch (t: Throwable) {
                ExtractorLinkType.VIDEO
            }
        }
        if (linkTypeCache.size < 256) linkTypeCache[candidate.url] = linkType
        return linkType
    }

    private fun hostOf(base: String): String =
        base.substringAfter("://").substringBefore('/').ifBlank { base }
}
