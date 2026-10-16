package com.cs3addon.StreamioLink

import com.lagradost.api.Log
import com.lagradost.cloudstream3.app
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
    const val STREAM_TIMEOUT_MS = 60_000L

    private data class CachedManifest(val manifest: StremioManifest, val fetchedAt: Long)

    private val manifestCache = mutableMapOf<String, CachedManifest>()
    private val cacheMutex = Mutex()

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

    suspend fun getStreams(base: String, type: String, id: String): List<StremioStream> =
        try {
            request(StremioMapping.buildStreamUrl(base, type, id), STREAM_TIMEOUT_MS)
                ?.let { StremioMapping.parseStreams(it) }
                .orEmpty()
        } catch (t: Throwable) {
            Log.e(TAG, "Streams for $id failed on $base: ${t.message}")
            emptyList()
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

    private suspend fun request(url: String, timeoutMs: Long): String? =
        kotlinx.coroutines.withTimeoutOrNull(timeoutMs) {
            app.get(url).text
        }
}
