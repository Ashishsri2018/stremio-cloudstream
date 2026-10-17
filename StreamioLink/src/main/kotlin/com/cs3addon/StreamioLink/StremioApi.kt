package com.cs3addon.StreamioLink

import com.lagradost.api.Log
import com.lagradost.cloudstream3.app
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * Thin HTTP layer for the Stremio addon protocol.
 * Every call is fail-soft: network errors return null/empty instead of throwing,
 * except coroutine cancellation which is always rethrown.
 */
object StremioApi {

    private const val TAG = "StreamioLink"
    private const val MANIFEST_TTL_MS = 30 * 60 * 1000L
    private const val MANIFEST_CACHE_MAX = 32

    /** Timeout for manifest/catalog/meta requests — these are expected to be fast. */
    const val DEFAULT_TIMEOUT_MS = 20_000L

    /**
     * Timeout for stream/subtitle requests. Addons such as PenguPlay aggregate many
     * providers server-side and can legitimately take tens of seconds; a short cap here
     * caused slow addons to be silently skipped.
     */
    const val STREAM_TIMEOUT_MS = 90_000L

    /**
     * After a request fails, don't retry that exact URL for this long. Prevents a down
     * addon from being hammered once per screen while the user browses the app.
     */
    private const val FAIL_BACKOFF_MS = 30_000L

    /** Cap on remembered failing URLs; expired entries are evicted on the next failure. */
    private const val FAIL_CACHE_MAX = 256

    private data class CachedManifest(val manifest: StremioManifest, val fetchedAt: Long)

    private val manifestCache = mutableMapOf<String, CachedManifest>()
    private val cacheMutex = Mutex()

    /** Keys of URLs that recently failed, mapped to the timestamp they may be retried at. */
    private val failedUntil = ConcurrentHashMap<String, Long>()

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

    // ---------------------------------------------------------------- failure back-off

    private fun isBackingOff(key: String): Boolean {
        val until = failedUntil[key] ?: return false
        if (until <= System.currentTimeMillis()) {
            failedUntil.remove(key, until)
            return false
        }
        return true
    }

    private fun markFailure(key: String) {
        val now = System.currentTimeMillis()
        failedUntil.entries.removeAll { it.value <= now }
        if (failedUntil.size >= FAIL_CACHE_MAX) {
            failedUntil.entries.minByOrNull { it.value }?.let { failedUntil.remove(it.key, it.value) }
        }
        failedUntil[key] = now + FAIL_BACKOFF_MS
    }

    private fun clearFailure(key: String) {
        failedUntil.remove(key)
    }

    // ---------------------------------------------------------------- manifest

    suspend fun getManifest(rawUrl: String?): StremioManifest? {
        val base = StremioMapping.normalizeAddonUrl(rawUrl) ?: return null
        val now = System.currentTimeMillis()

        cacheMutex.withLock {
            val cached = manifestCache[base]
            if (cached != null && now - cached.fetchedAt < MANIFEST_TTL_MS) {
                return cached.manifest
            }
        }
        if (isBackingOff(base)) return staleManifest(base)

        return try {
            val json = request("$base/manifest.json", DEFAULT_TIMEOUT_MS)
            if (json == null) {
                markFailure(base)
                return staleManifest(base)
            }
            val manifest = StremioMapping.parseManifest(json)
            clearFailure(base)
            cacheMutex.withLock {
                pruneManifestsLocked(now)
                manifestCache[base] = CachedManifest(manifest, now)
            }
            manifest
        } catch (t: CancellationException) {
            throw t
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to load manifest for $base: ${t.message}")
            markFailure(base)
            staleManifest(base)
        }
    }

    private suspend fun staleManifest(base: String): StremioManifest? =
        cacheMutex.withLock { manifestCache[base]?.manifest }

    /** Caller holds [cacheMutex]. Drops expired manifests and keeps the cache bounded. */
    private fun pruneManifestsLocked(now: Long) {
        manifestCache.entries.removeAll { now - it.value.fetchedAt >= MANIFEST_TTL_MS }
        while (manifestCache.size >= MANIFEST_CACHE_MAX) {
            val oldest = manifestCache.entries.minByOrNull { it.value.fetchedAt } ?: return
            manifestCache.remove(oldest.key)
        }
    }

    // ---------------------------------------------------------------- catalog / meta / subtitles

    suspend fun getCatalog(base: String, type: String, catalogId: String, extra: String? = null): List<StremioMetaPreview> {
        val url = StremioMapping.buildCatalogUrl(base, type, catalogId, extra)
        if (isBackingOff(url)) return emptyList()
        return try {
            val json = request(url, DEFAULT_TIMEOUT_MS)
            if (json == null) {
                markFailure(url)
                emptyList()
            } else {
                clearFailure(url)
                StremioMapping.parseCatalog(json)
            }
        } catch (t: CancellationException) {
            throw t
        } catch (t: Throwable) {
            Log.e(TAG, "Catalog $catalogId failed: ${t.message}")
            markFailure(url)
            emptyList()
        }
    }

    suspend fun getMeta(base: String, type: String, id: String): StremioMetaDetails? {
        val url = StremioMapping.buildMetaUrl(base, type, id)
        if (isBackingOff(url)) return null
        return try {
            val json = request(url, DEFAULT_TIMEOUT_MS)
            if (json == null) {
                markFailure(url)
                null
            } else {
                clearFailure(url)
                StremioMapping.parseMeta(json)
            }
        } catch (t: CancellationException) {
            throw t
        } catch (t: Throwable) {
            Log.e(TAG, "Meta $id failed: ${t.message}")
            markFailure(url)
            null
        }
    }

    suspend fun getSubtitles(
        base: String,
        type: String,
        id: String,
        videoHash: String? = null,
        videoSize: Long? = null,
    ): List<StremioSubtitle> {
        val url = StremioMapping.buildSubtitlesUrl(base, type, id, videoHash, videoSize)
        if (isBackingOff(url)) return emptyList()
        return try {
            val json = request(url, STREAM_TIMEOUT_MS)
            if (json == null) {
                markFailure(url)
                emptyList()
            } else {
                clearFailure(url)
                StremioMapping.parseSubtitles(json)
            }
        } catch (t: CancellationException) {
            throw t
        } catch (t: Throwable) {
            Log.e(TAG, "Subtitles for $id failed on $base: ${t.message}")
            markFailure(url)
            emptyList()
        }
    }

    // ---------------------------------------------------------------- streams

    suspend fun getStreams(base: String, type: String, id: String): List<StremioStream> {
        val key = "$base|$type|$id"
        val now = System.currentTimeMillis()
        streamCache[key]
            ?.takeIf { now - it.fetchedAt < STREAM_CACHE_TTL_MS }
            ?.let { return it.streams }

        val url = StremioMapping.buildStreamUrl(base, type, id)
        if (isBackingOff(url)) return emptyList()

        return try {
            val json = request(url, STREAM_TIMEOUT_MS)
            if (json == null) {
                markFailure(url)
                emptyList()
            } else {
                clearFailure(url)
                val streams = StremioMapping.parseStreams(json)
                if (streams.isNotEmpty()) {
                    pruneStreams(now)
                    streamCache[key] = CachedStreams(streams, now)
                }
                streams
            }
        } catch (t: CancellationException) {
            throw t
        } catch (t: Throwable) {
            Log.e(TAG, "Streams for $id failed on $base: ${t.message}")
            markFailure(url)
            emptyList()
        }
    }

    /** Drops expired entries first, then the oldest, keeping [streamCache] bounded. */
    private fun pruneStreams(now: Long) {
        streamCache.entries.removeAll { now - it.value.fetchedAt >= STREAM_CACHE_TTL_MS }
        while (streamCache.size >= STREAM_CACHE_MAX) {
            val oldest = streamCache.entries.minByOrNull { it.value.fetchedAt } ?: return
            streamCache.remove(oldest.key)
        }
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
