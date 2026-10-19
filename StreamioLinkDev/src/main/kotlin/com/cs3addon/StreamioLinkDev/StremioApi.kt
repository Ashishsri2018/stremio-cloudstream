package com.cs3addon.StreamioLinkDev

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
    private const val STREAM_CACHE_TTL_MS = 60_000L
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
        val base = StremioMapping.normalizeAddonUrl(rawUrl) ?: run {
            StremioDebug.w("manifest SKIP invalid url=${StremioDebug.clip(rawUrl, 200)}")
            return null
        }
        val now = System.currentTimeMillis()

        cacheMutex.withLock {
            val cached = manifestCache[base]
            if (cached != null && now - cached.fetchedAt < MANIFEST_TTL_MS) {
                StremioDebug.d(
                    "manifest CACHE base=$base age=${now - cached.fetchedAt}ms name=${cached.manifest.name}"
                )
                return cached.manifest
            }
        }
        if (isBackingOff(base)) {
            val stale = staleManifest(base)
            StremioDebug.w("manifest BACKOFF base=$base stale=${stale != null}")
            return stale
        }

        val startedAt = System.currentTimeMillis()
        return try {
            val json = request(
                "$base/manifest.json",
                DEFAULT_TIMEOUT_MS,
                "manifest(${StremioDebug.host(base)})",
            )
            if (json == null) {
                StremioDebug.w("manifest FAIL base=$base dur=${StremioDebug.ms(startedAt)}ms")
                markFailure(base)
                return staleManifest(base)
            }
            val manifest = StremioMapping.parseManifest(json)
            clearFailure(base)
            cacheMutex.withLock {
                pruneManifestsLocked(now)
                manifestCache[base] = CachedManifest(manifest, now)
            }
            StremioDebug.d(
                "manifest OK base=$base name=${manifest.name} version=${manifest.version} " +
                    "resources=${StremioDebug.join(StremioMapping.manifestResourceNames(manifest))} " +
                    "types=${StremioDebug.join(manifest.types)} " +
                    "idPrefixes=${StremioDebug.join(manifest.idPrefixes)} " +
                    "catalogs=${manifest.catalogs?.size ?: 0} dur=${StremioDebug.ms(startedAt)}ms"
            )
            manifest
        } catch (t: CancellationException) {
            throw t
        } catch (t: Throwable) {
            StremioDebug.e(
                "manifest PARSE/ERR base=$base ${t::class.java.simpleName}: ${t.message}"
            )
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
        if (isBackingOff(url)) {
            StremioDebug.w("catalog BACKOFF id=$catalogId url=$url")
            return emptyList()
        }
        val startedAt = System.currentTimeMillis()
        return try {
            val json = request(url, DEFAULT_TIMEOUT_MS, "catalog($catalogId)")
            if (json == null) {
                StremioDebug.w("catalog FAIL id=$catalogId dur=${StremioDebug.ms(startedAt)}ms")
                markFailure(url)
                emptyList()
            } else {
                clearFailure(url)
                val items = StremioMapping.parseCatalog(json)
                StremioDebug.d(
                    "catalog OK id=$catalogId items=${items.size} dur=${StremioDebug.ms(startedAt)}ms"
                )
                items
            }
        } catch (t: CancellationException) {
            throw t
        } catch (t: Throwable) {
            StremioDebug.e(
                "catalog PARSE/ERR id=$catalogId ${t::class.java.simpleName}: ${t.message}"
            )
            markFailure(url)
            emptyList()
        }
    }

    suspend fun getMeta(base: String, type: String, id: String): StremioMetaDetails? {
        val url = StremioMapping.buildMetaUrl(base, type, id)
        if (isBackingOff(url)) {
            StremioDebug.w("meta BACKOFF id=$id url=$url")
            return null
        }
        val startedAt = System.currentTimeMillis()
        return try {
            val json = request(url, DEFAULT_TIMEOUT_MS, "meta($id@${StremioDebug.host(base)})")
            if (json == null) {
                StremioDebug.w("meta FAIL id=$id dur=${StremioDebug.ms(startedAt)}ms")
                markFailure(url)
                null
            } else {
                clearFailure(url)
                val meta = StremioMapping.parseMeta(json)
                StremioDebug.d(
                    "meta OK id=$id name=${meta?.name} videos=${meta?.videos?.size ?: 0} " +
                        "dur=${StremioDebug.ms(startedAt)}ms"
                )
                meta
            }
        } catch (t: CancellationException) {
            throw t
        } catch (t: Throwable) {
            StremioDebug.e("meta PARSE/ERR id=$id ${t::class.java.simpleName}: ${t.message}")
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
        val startedAt = System.currentTimeMillis()
        val candidates = StremioMapping.buildSubtitlesUrlCandidates(base, type, id, videoHash, videoSize)
        StremioDebug.d(
            "subs START id=$id type=$type base=${StremioDebug.host(base)} " +
                "hash=$videoHash size=$videoSize candidates=${candidates.size}"
        )
        // Try the canonical hint-bearing URL first, then the extra-less one. Addons such as
        // PlavioHD implement only `/subtitles/{type}/{id}.json` and answer any path extra
        // with an HTML 404; NiceHttp does not throw on that status, so the body reaches the
        // parser and fails, which is the signal to retry the plain shape.
        for ((index, url) in candidates.withIndex()) {
            val label = "subs(${StremioDebug.host(base)}#$index)"
            if (isBackingOff(url)) {
                StremioDebug.w("$label BACKOFF url=$url")
                continue
            }
            val json = try {
                request(url, STREAM_TIMEOUT_MS, label)
            } catch (t: CancellationException) {
                throw t
            } catch (t: Throwable) {
                StremioDebug.e("$label ERROR ${t::class.java.simpleName}: ${t.message}")
                null
            }
            // A null body is a transport failure/timeout: do not spend another full timeout
            // on the alternate shape, just record it and stop.
            if (json == null) {
                StremioDebug.w("$label TRANSPORT null -> break (alternate shape not tried)")
                markFailure(url)
                break
            }
            val subtitles = try {
                StremioMapping.parseSubtitles(json)
            } catch (t: Throwable) {
                null
            }
            if (subtitles != null) {
                clearFailure(url)
                StremioDebug.d(
                    "$label OK count=${subtitles.size} " +
                        "langs=${StremioDebug.join(subtitles.map { StremioMapping.subtitleLanguage(it) })} " +
                        "dur=${StremioDebug.ms(startedAt)}ms"
                )
                if (subtitles.isEmpty() && index < candidates.lastIndex) {
                    StremioDebug.w("$label EMPTY -> stopping (alternate shape not tried)")
                }
                return subtitles
            }
            // Non-JSON body (e.g. an HTML 404): back off this shape so later episodes skip
            // straight to the next candidate, then try it.
            StremioDebug.w("$label PARSE FAIL body=${StremioDebug.preview(json)} -> next shape")
            markFailure(url)
        }
        StremioDebug.w(
            "subs NONE id=$id base=${StremioDebug.host(base)} dur=${StremioDebug.ms(startedAt)}ms"
        )
        return emptyList()
    }

    // ---------------------------------------------------------------- streams

    suspend fun getStreams(base: String, type: String, id: String): List<StremioStream> {
        val key = "$base|$type|$id"
        val now = System.currentTimeMillis()
        streamCache[key]?.let { cached ->
            val age = now - cached.fetchedAt
            if (age < STREAM_CACHE_TTL_MS) {
                StremioDebug.d(
                    "streams CACHE HIT id=$id base=${StremioDebug.host(base)} age=${age}ms " +
                        "count=${cached.streams.size}"
                )
                return cached.streams
            }
            StremioDebug.d(
                "streams CACHE STALE id=$id base=${StremioDebug.host(base)} age=${age}ms"
            )
        }

        val url = StremioMapping.buildStreamUrl(base, type, id)
        if (isBackingOff(url)) {
            StremioDebug.w(
                "streams BACKOFF id=$id base=${StremioDebug.host(base)} url=$url"
            )
            return emptyList()
        }

        val startedAt = System.currentTimeMillis()
        return try {
            val json = request(url, STREAM_TIMEOUT_MS, "streams($id@${StremioDebug.host(base)})")
            if (json == null) {
                StremioDebug.w(
                    "streams FAIL id=$id base=${StremioDebug.host(base)} " +
                        "dur=${StremioDebug.ms(startedAt)}ms"
                )
                markFailure(url)
                emptyList()
            } else {
                clearFailure(url)
                val streams = try {
                    StremioMapping.parseStreams(json)
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    StremioDebug.e(
                        "streams PARSE FAIL id=$id base=${StremioDebug.host(base)} " +
                            "${t::class.java.simpleName}: ${t.message} body=${StremioDebug.preview(json)}"
                    )
                    markFailure(url)
                    return emptyList()
                }
                if (streams.isNotEmpty()) {
                    pruneStreams(now)
                    streamCache[key] = CachedStreams(streams, now)
                }
                StremioDebug.d(
                    "streams OK id=$id base=${StremioDebug.host(base)} count=${streams.size} " +
                        "cached=${streams.isNotEmpty()} dur=${StremioDebug.ms(startedAt)}ms " +
                        "body=${StremioDebug.preview(json)}"
                )
                streams
            }
        } catch (t: CancellationException) {
            throw t
        } catch (t: Throwable) {
            StremioDebug.e(
                "streams ERROR id=$id base=${StremioDebug.host(base)} " +
                    "${t::class.java.simpleName}: ${t.message}"
            )
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
     *
     * [label] is diagnostics-only: every request logs status, size, content type and
     * duration, so a first-play vs reload difference is visible in one capture.
     */
    private suspend fun request(url: String, timeoutMs: Long, label: String): String? {
        val startedAt = System.currentTimeMillis()
        StremioDebug.d("$label REQ url=$url timeout=${timeoutMs}ms")
        return try {
            kotlinx.coroutines.withTimeoutOrNull(timeoutMs + 1_000L) {
                val response = app.get(url, timeout = (timeoutMs / 1000L).coerceAtLeast(1L))
                val body = response.text
                val contentType = response.headers["content-type"].orEmpty()
                StremioDebug.d(
                    "$label RESP http=${response.code} bytes=${body.length} ct=$contentType " +
                        "dur=${StremioDebug.ms(startedAt)}ms final=${response.url}"
                )
                if (response.code !in 200..299) {
                    StremioDebug.w(
                        "$label NON-2XX http=${response.code} body=${StremioDebug.clip(body, 200)}"
                    )
                } else if (body.isBlank()) {
                    StremioDebug.w("$label EMPTY body http=${response.code} url=$url")
                } else {
                    val trimmed = body.trimStart()
                    if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) {
                        StremioDebug.w(
                            "$label NON-JSON body=${StremioDebug.clip(body, 200)}"
                        )
                    }
                }
                body
            } ?: run {
                StremioDebug.w("$label TIMEOUT dur=${StremioDebug.ms(startedAt)}ms url=$url")
                null
            }
        } catch (t: CancellationException) {
            throw t
        } catch (t: Throwable) {
            StremioDebug.e(
                "$label ERROR dur=${StremioDebug.ms(startedAt)}ms " +
                    "${t::class.java.simpleName}: ${t.message} url=$url"
            )
            null
        }
    }
}
