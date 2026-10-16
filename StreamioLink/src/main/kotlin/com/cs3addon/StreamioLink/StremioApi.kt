package com.cs3addon.StreamioLink

import com.lagradost.api.Log
import com.lagradost.cloudstream3.app
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * Thin HTTP layer for the Stremio addon protocol.
 * Every call is fail-soft: network errors return null/empty instead of throwing.
 */
object StremioApi {

    private const val TAG = "StreamioLink"
    private const val MANIFEST_TTL_MS = 30 * 60 * 1000L

    /** Timeout for manifest/catalog/meta requests — these are expected to be fast. */
    const val DEFAULT_TIMEOUT_MS = 20_000L

    /**
     * Timeout for stream/subtitle requests. Addons such as PenguPlay aggregate many
     * providers server-side and can legitimately take tens of seconds; a short cap here
     * caused slow addons to be silently skipped.
     */
    const val STREAM_TIMEOUT_MS = 90_000L

    private data class CachedManifest(val manifest: StremioManifest, val fetchedAt: Long)

    private val manifestCache = mutableMapOf<String, CachedManifest>()
    private val cacheMutex = Mutex()

    /**
     * Streams are the slowest and heaviest addon call (aggregators take 10-30 s server-side).
     * A short-lived in-memory cache means re-opening an episode or retrying a link does not
     * hammer the addons again. Only successful, non-empty results are cached so a transient
     * failure is not remembered.
     */
    private const val STREAM_CACHE_TTL_MS = 5 * 60 * 1000L
    private const val STREAM_CACHE_MAX = 64

    private data class CachedStreams(val streams: List<StremioStream>, val fetchedAt: Long)

    private val streamCache = ConcurrentHashMap<String, CachedStreams>()

    suspend fun getManifest(rawUrl: String?): StremioManifest? {
        val base = StremioMapping.normalizeAddonUrl(rawUrl) ?: return null
        val now = System.currentTimeMillis()

        cacheMutex.withLock {
            val cached = manifestCache[base]
            if (cached != null && now - cached.fetchedAt < MANIFEST_TTL_MS) {
                return cached.manifest
            }
        }

        return try {
            val json = request("$base/manifest.json", DEFAULT_TIMEOUT_MS) ?: return staleManifest(base)
            val manifest = StremioMapping.parseManifest(json)
            cacheMutex.withLock { manifestCache[base] = CachedManifest(manifest, now) }
            manifest
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to load manifest for $base: ${t.message}")
            staleManifest(base)
        }
    }

    private suspend fun staleManifest(base: String): StremioManifest? =
        cacheMutex.withLock { manifestCache[base]?.manifest }

    suspend fun getCatalog(base: String, type: String, catalogId: String, extra: String? = null): List<StremioMetaPreview> =
        try {
            request(StremioMapping.buildCatalogUrl(base, type, catalogId, extra), DEFAULT_TIMEOUT_MS)
                ?.let { StremioMapping.parseCatalog(it) }
                .orEmpty()
        } catch (t: Throwable) {
            Log.e(TAG, "Catalog $catalogId failed: ${t.message}")
            emptyList()
        }

    suspend fun getMeta(base: String, type: String, id: String): StremioMetaDetails? =
        try {
            request(StremioMapping.buildMetaUrl(base, type, id), DEFAULT_TIMEOUT_MS)
                ?.let { StremioMapping.parseMeta(it) }
        } catch (t: Throwable) {
            Log.e(TAG, "Meta $id failed: ${t.message}")
            null
        }

    suspend fun getStreams(base: String, type: String, id: String): List<StremioStream> {
        val key = "$base|$type|$id"
        val now = System.currentTimeMillis()
        streamCache[key]
            ?.takeIf { now - it.fetchedAt < STREAM_CACHE_TTL_MS }
            ?.let { return it.streams }

        return try {
            val streams = request(StremioMapping.buildStreamUrl(base, type, id), STREAM_TIMEOUT_MS)
                ?.let { StremioMapping.parseStreams(it) }
                .orEmpty()
            if (streams.isNotEmpty()) {
                if (streamCache.size >= STREAM_CACHE_MAX) streamCache.clear()
                streamCache[key] = CachedStreams(streams, now)
            }
            streams
        } catch (t: Throwable) {
            Log.e(TAG, "Streams for $id failed on $base: ${t.message}")
            emptyList()
        }
    }

    suspend fun getSubtitles(
        base: String,
        type: String,
        id: String,
        videoHash: String? = null,
        videoSize: Long? = null,
    ): List<StremioSubtitle> =
        try {
            request(StremioMapping.buildSubtitlesUrl(base, type, id, videoHash, videoSize), STREAM_TIMEOUT_MS)
                ?.let { StremioMapping.parseSubtitles(it) }
                .orEmpty()
        } catch (t: Throwable) {
            Log.e(TAG, "Subtitles for $id failed on $base: ${t.message}")
            emptyList()
        }

    /**
     * Cloudstream's `app` is a NiceHttp `Requests` whose default timeout is 0, which
     * leaves OkHttp's 10 s read timeout in place. Slow aggregator addons (PenguPlay etc.)
     * routinely need longer than that to produce a first byte, so they must be given an
     * explicit per-request timeout. NiceHttp takes the timeout in seconds.
     */
    private suspend fun request(url: String, timeoutMs: Long): String? =
        kotlinx.coroutines.withTimeoutOrNull(timeoutMs + 1_000L) {
            app.get(url, timeout = (timeoutMs / 1000L).coerceAtLeast(1L)).text
        }
}
