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

    private const val TRACKER_LIST_URL =
        "https://raw.githubusercontent.com/ngosang/trackerslist/master/trackers_best.txt"
    private const val TRACKER_LIST_TTL_MS = 12 * 60 * 60 * 1000L
    private const val TRACKER_TIMEOUT_MS = 5_000L

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

    private val trackerMutex = Mutex()

    @Volatile
    private var publicTrackers: List<String> = emptyList()

    @Volatile
    private var publicTrackersFetchedAt = 0L

    /**
     * Streams are the slowest and heaviest addon call (aggregators take 10-30 s server-side).
     * A short-lived in-memory cache means re-opening an episode or retrying a link does not
     * hammer the addons again. Only successful, non-empty results are cached so a transient
     * failure is not remembered.
     */
    private const val STREAM_CACHE_TTL_MS = 60_000L
    private const val STREAM_CACHE_MAX = 64

    private data class CachedStreams(val streams: List<StremioStream>, val fetchedAt: Long)

    private val streamCache = ConcurrentHashMap<String, CachedStreams>()
    private val streamCacheLock = Any()

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

    // ---------------------------------------------------------------- public trackers

    /**
     * Trackers merged into infoHash magnets. Fetched lazily (only when a torrent link is being
     * produced) and cached in memory for 12 h. A failed refresh keeps the stale list and is
     * retried no sooner than the regular failure back-off.
     */
    suspend fun getPublicTrackers(): List<String> {
        val now = System.currentTimeMillis()
        if (publicTrackers.isNotEmpty() && now - publicTrackersFetchedAt < TRACKER_LIST_TTL_MS) {
            return publicTrackers
        }
        return trackerMutex.withLock {
            val lockedNow = System.currentTimeMillis()
            if (publicTrackers.isNotEmpty() &&
                lockedNow - publicTrackersFetchedAt < TRACKER_LIST_TTL_MS
            ) {
                return@withLock publicTrackers
            }
            if (isBackingOff(TRACKER_LIST_URL)) return@withLock publicTrackers
            try {
                val text = request(TRACKER_LIST_URL, TRACKER_TIMEOUT_MS)
                val parsed = text?.let { StremioMapping.parsePublicTrackers(it) }.orEmpty()
                if (parsed.isEmpty()) {
                    markFailure(TRACKER_LIST_URL)
                    publicTrackers
                } else {
                    publicTrackers = parsed
                    publicTrackersFetchedAt = lockedNow
                    clearFailure(TRACKER_LIST_URL)
                    Log.d(TAG, "Loaded ${parsed.size} public trackers")
                    parsed
                }
            } catch (t: CancellationException) {
                throw t
            } catch (t: Throwable) {
                Log.e(TAG, "Public tracker list failed: ${t.message}")
                markFailure(TRACKER_LIST_URL)
                publicTrackers
            }
        }
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
            if (!StremioMapping.isUsableManifest(manifest)) {
                // e.g. `{"error":"Service Unavailable"}` parsed into an all-null manifest.
                Log.e(TAG, "Ignoring empty manifest for $base")
                markFailure(base)
                return staleManifest(base)
            }
            clearFailure(base)
            // Stamp after the request: a slow (but successful) fetch must not arrive already
            // older than MANIFEST_TTL_MS.
            val storedAt = System.currentTimeMillis()
            cacheMutex.withLock {
                pruneManifestsLocked(storedAt)
                manifestCache[base] = CachedManifest(manifest, storedAt)
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
        // Try the canonical hint-bearing URL first, then the extra-less one. Addons such as
        // PlavioHD implement only `/subtitles/{type}/{id}.json` and answer any path extra
        // with a 404 (HTML or a JSON error body), so a client error means "try the plain shape".
        for (url in StremioMapping.buildSubtitlesUrlCandidates(base, type, id, videoHash, videoSize)) {
            if (isBackingOff(url)) continue
            val result = try {
                fetch(url, STREAM_TIMEOUT_MS)
            } catch (t: CancellationException) {
                throw t
            } catch (t: Throwable) {
                Log.e(TAG, "Subtitles for $id failed on $base: ${t.message}")
                null
            }
            // Transport failure/timeout: do not spend another full timeout on the alternate
            // shape, just record it and stop.
            if (result == null) {
                markFailure(url)
                break
            }
            if (!result.isSuccessful) {
                markFailure(url)
                // 4xx usually means this shape is unsupported; try the next candidate.
                // 5xx/429 mean the addon is unhealthy and another shape will not help.
                if (result.code in 400..499) continue else break
            }
            val subtitles = try {
                StremioMapping.parseSubtitles(result.body)
            } catch (t: Throwable) {
                null
            }
            if (subtitles != null) {
                clearFailure(url)
                return subtitles
            }
            // 2xx but non-JSON body: back off this shape and try the next candidate.
            markFailure(url)
        }
        return emptyList()
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
                    // Stamp after the request so a slow (but successful) addon does not arrive
                    // already older than STREAM_CACHE_TTL_MS.
                    storeStreams(key, streams, System.currentTimeMillis())
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

    /**
     * Inserts [streams] and evicts expired/oldest entries under one lock. Insertion used to
     * happen outside the lock after pruning, so concurrent completions could exceed
     * [STREAM_CACHE_MAX].
     */
    private fun storeStreams(key: String, streams: List<StremioStream>, storedAt: Long) {
        synchronized(streamCacheLock) {
            streamCache.entries.removeAll { storedAt - it.value.fetchedAt >= STREAM_CACHE_TTL_MS }
            while (streamCache.size >= STREAM_CACHE_MAX) {
                val oldest = streamCache.entries.minByOrNull { it.value.fetchedAt }
                if (oldest == null) break
                streamCache.remove(oldest.key)
            }
            streamCache[key] = CachedStreams(streams, storedAt)
        }
    }

    /** HTTP response body plus status, so callers can distinguish 4xx/5xx from transport errors. */
    private data class HttpResult(val body: String, val code: Int) {
        val isSuccessful: Boolean get() = code in 200..299
    }

    /**
     * Cloudstream's `app` is a NiceHttp `Requests` whose default timeout is 0, which
     * leaves OkHttp's 10 s read timeout in place. Slow aggregator addons (PenguPlay etc.)
     * routinely need longer than that to produce a first byte, so they must be given an
     * explicit per-request timeout. NiceHttp takes the timeout in seconds.
     *
     * NiceHttp does not throw on non-2xx responses, so the status must be inspected here.
     * Otherwise a 5xx JSON body such as `{"error":"..."}` is parsed into an all-null model,
     * which used to be cached as a valid empty manifest or treated as a successful empty
     * subtitle list.
     */
    private suspend fun fetch(url: String, timeoutMs: Long): HttpResult? =
        kotlinx.coroutines.withTimeoutOrNull(timeoutMs + 1_000L) {
            try {
                val response = app.get(url, timeout = (timeoutMs / 1000L).coerceAtLeast(1L))
                HttpResult(response.text, response.code)
            } catch (t: CancellationException) {
                throw t
            } catch (t: Throwable) {
                Log.e(TAG, "Request failed for $url: ${t.message}")
                null
            }
        }

    /** Body of a 2xx response, or null for transport failures and non-2xx statuses. */
    private suspend fun request(url: String, timeoutMs: Long): String? {
        val result = fetch(url, timeoutMs) ?: return null
        if (!result.isSuccessful) {
            Log.w(TAG, "HTTP ${result.code} for $url")
            return null
        }
        return result.body
    }

    /**
     * Drops cached manifests/streams and the failure back-off. Called when settings change so
     * the pre-fetch uses the new addon set instead of serving stale data (or a recent back-off).
     */
    suspend fun invalidateCache() {
        cacheMutex.withLock { manifestCache.clear() }
        streamCache.clear()
        failedUntil.clear()
    }
}
