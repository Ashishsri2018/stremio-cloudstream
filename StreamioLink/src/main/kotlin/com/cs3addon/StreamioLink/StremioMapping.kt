package com.cs3addon.StreamioLink

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.net.URLEncoder

/**
 * Pure Stremio protocol logic: parsing, URL building and mapping decisions.
 * This class must stay free of Android/Cloudstream dependencies so it can be unit tested on the JVM.
 */
object StremioMapping {

    val mapper = jacksonObjectMapper()

    val SUPPORTED_MEDIA_TYPES = setOf("movie", "series")

    /**
     * Stremio CDNs (e.g. the `hakunaymatata.com` hosts behind MovieBox/VidLink) return
     * HTTP 428 when the request carries a Mozilla/browser User-Agent, but 200 for
     * non-browser agents. [DEFAULT_STREAM_USER_AGENT] is applied to streams whose addon
     * does not specify its own User-Agent so Cloudstream does not fall back to its
     * global Mozilla UA.
     */
    const val DEFAULT_STREAM_USER_AGENT = "okhttp/4.12.0"

    private val manifestSuffixRegex = Regex("""/manifest\.json/?$""", RegexOption.IGNORE_CASE)
    private val yearRegex = Regex("""\b(19|20)\d{2}\b""")
    private val whitespaceRegex = Regex("""\s+""")
    private val whitespaceInUrlRegex = Regex("""\s""")

    private val qualityPatterns = listOf(
        2160 to Regex("""(?<![0-9])(2160p|4k|uhd)(?![0-9a-z])"""),
        1440 to Regex("""(?<![0-9])1440p?(?![0-9a-z])"""),
        1080 to Regex("""(?<![0-9])(1080p?|fhd|fullhd|full\shd)(?![0-9a-z])"""),
        720 to Regex("""(?<![0-9])(720p?|hd)(?![0-9a-z])"""),
        480 to Regex("""(?<![0-9])480p?(?![0-9a-z])"""),
        360 to Regex("""(?<![0-9])360p?(?![0-9a-z])"""),
        240 to Regex("""(?<![0-9])240p?(?![0-9a-z])"""),
        144 to Regex("""(?<![0-9])144p(?![0-9a-z])"""),
    )

    // ---------------------------------------------------------------- parsing

    fun parseManifest(json: String): StremioManifest =
        mapper.readValue(json, StremioManifest::class.java)

    fun parseCatalog(json: String): List<StremioMetaPreview> =
        mapper.readValue(json, StremioCatalogResponse::class.java).metas.orEmpty()

    fun parseMeta(json: String): StremioMetaDetails? =
        mapper.readValue(json, StremioMetaResponse::class.java).meta

    fun parseStreams(json: String): List<StremioStream> =
        mapper.readValue(json, StremioStreamsResponse::class.java).streams.orEmpty()

    fun parseSubtitles(json: String): List<StremioSubtitle> =
        mapper.readValue(json, StremioSubtitlesResponse::class.java).subtitles.orEmpty()

    // ---------------------------------------------------------------- payload

    fun encodePayload(id: String, type: String): String =
        mapper.writeValueAsString(StremioPayload(id = id, type = type))

    fun decodePayload(data: String): StremioPayload? = try {
        mapper.readValue(data, StremioPayload::class.java)
    } catch (t: Throwable) {
        null
    }

    fun encodeCatalogRef(type: String, catalogId: String): String =
        mapper.writeValueAsString(StremioCatalogRef(type = type, id = catalogId))

    fun decodeCatalogRef(data: String?): StremioCatalogRef? = try {
        data?.takeIf { it.isNotBlank() }?.let { mapper.readValue(it, StremioCatalogRef::class.java) }
    } catch (t: Throwable) {
        null
    }

    // ---------------------------------------------------------------- manifest helpers

    private fun JsonNode.textOrNull(): String? =
        if (isTextual) asText().takeIf { it.isNotBlank() } else null

    fun manifestResourceNames(manifest: StremioManifest): Set<String> =
        manifest.resources.orEmpty().mapNotNull { it.textOrNull() ?: it.get("name")?.textOrNull() }.toSet()

    fun manifestSupportsResource(manifest: StremioManifest, resource: String): Boolean =
        manifestResourceNames(manifest).any { it.equals(resource, ignoreCase = true) }

    private fun resourceNodeFor(manifest: StremioManifest, resource: String): JsonNode? =
        manifest.resources.orEmpty().firstOrNull { node ->
            (node.textOrNull() ?: node.get("name")?.textOrNull())?.equals(resource, ignoreCase = true) == true
        }

    fun resourceTypesFor(manifest: StremioManifest, resource: String): List<String> {
        val node = resourceNodeFor(manifest, resource)
        val objectTypes = node?.takeIf { it.isObject }?.get("types")
            ?.takeIf { it.isArray }?.mapNotNull { it.textOrNull() }
        return objectTypes?.takeIf { it.isNotEmpty() } ?: manifest.types.orEmpty()
    }

    fun resourceIdPrefixesFor(manifest: StremioManifest, resource: String): List<String> {
        val node = resourceNodeFor(manifest, resource)
        val objectPrefixes = node?.takeIf { it.isObject }?.get("idPrefixes")
            ?.takeIf { it.isArray }?.mapNotNull { it.textOrNull() }
        return objectPrefixes?.takeIf { it.isNotEmpty() } ?: manifest.idPrefixes.orEmpty()
    }

    fun idMatchesPrefixes(id: String, prefixes: List<String>): Boolean =
        prefixes.isEmpty() || prefixes.any { id.startsWith(it) }

    // ---------------------------------------------------------------- catalogs

    fun catalogCanLoadHome(catalog: StremioCatalog): Boolean {
        if (!SUPPORTED_MEDIA_TYPES.contains(catalog.type?.lowercase())) return false
        if (catalog.id.isNullOrBlank()) return false
        if (catalog.extra.orEmpty().any { it.isRequired == true }) return false
        if (!catalog.extraRequired.isNullOrEmpty()) return false
        return true
    }

    fun catalogSupportsSearch(catalog: StremioCatalog): Boolean {
        if (!SUPPORTED_MEDIA_TYPES.contains(catalog.type?.lowercase())) return false
        if (catalog.id.isNullOrBlank()) return false
        val names = mutableSetOf<String>()
        catalog.extra.orEmpty().forEach { it.name?.let { n -> names.add(n.lowercase()) } }
        catalog.extraSupported.orEmpty().forEach { names.add(it.lowercase()) }
        return "search" in names
    }

    /** True when the catalog advertises `skip`, meaning it can return more than one page. */
    fun catalogSupportsSkip(catalog: StremioCatalog): Boolean {
        if (catalog.extra.orEmpty().any { it.name?.equals("skip", ignoreCase = true) == true }) return true
        return catalog.extraSupported.orEmpty().any { it.equals("skip", ignoreCase = true) }
    }

    // ---------------------------------------------------------------- url building

    fun encodeQueryValue(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    fun normalizeAddonUrl(raw: String?): String? {
        var url = raw?.trim().orEmpty()
        if (url.isEmpty()) return null
        if (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)) {
            if (!url.contains(".")) return null
            url = "https://$url"
        }
        url = url.replace(manifestSuffixRegex, "")
        url = url.trimEnd('/')
        return url.ifEmpty { null }
    }

    fun buildCatalogUrl(base: String, type: String, catalogId: String, extra: String? = null): String {
        val extraSegment = extra?.takeIf { it.isNotBlank() }?.let { "/$it" }.orEmpty()
        return "$base/catalog/$type/$catalogId$extraSegment.json"
    }

    fun buildMetaUrl(base: String, type: String, id: String): String =
        "$base/meta/$type/$id.json"

    fun buildStreamUrl(base: String, type: String, id: String): String =
        "$base/stream/$type/$id.json"

    fun buildSubtitlesUrl(base: String, type: String, id: String, videoHash: String?, videoSize: Long?): String {
        val extra = mutableListOf<String>()
        if (!videoHash.isNullOrBlank()) extra.add("videoHash=$videoHash")
        if (videoSize != null && videoSize > 0) extra.add("videoSize=$videoSize")
        val extraSegment = if (extra.isEmpty()) "" else "/" + extra.joinToString("&")
        return "$base/subtitles/$type/$id$extraSegment.json"
    }

    // ---------------------------------------------------------------- stream helpers

    enum class StreamKind { HLS, DASH, VIDEO }

    fun guessStreamKind(url: String, filename: String? = null): StreamKind {
        val lower = url.lowercase()
        val path = lower.substringBefore('?').substringBefore('#')
        val name = filename?.lowercase().orEmpty()
        // Addons such as PenguPlay append metadata after the extension
        // (e.g. "movie.mkv|^|720p..."), so check for the marker anywhere
        // instead of only at the end of the string. The full URL is scanned last so
        // extension-less playlists behind a plain proxy are still recognised
        // (e.g. `.../proxy/bound?d=...%2F...m3u8`), and common `/hls/` paths are hints.
        val hls = path.contains(".m3u8") || name.contains(".m3u8") || lower.contains(".m3u8") ||
            path.contains("/hls") || path.contains("/playlist") || lower.contains("mpegurl")
        val dash = path.contains(".mpd") || name.contains(".mpd") || lower.contains(".mpd")
        return when {
            hls -> StreamKind.HLS
            dash -> StreamKind.DASH
            else -> StreamKind.VIDEO
        }
    }

    /**
     * True when [url] is an `http(s)`/`magnet:` URL with no embedded whitespace. Some addons
     * (e.g. CNCVerse) return raw URLs containing literal spaces, which the player cannot
     * open; dropping them removes a guaranteed-dead link from the list. `magnet:` is kept so
     * addons that carry a magnet in `url` (instead of `infoHash`) still play.
     */
    fun isPlayableStreamUrl(url: String): Boolean {
        val trimmed = url.trim()
        val recognisedScheme = trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true) ||
            trimmed.startsWith("magnet:", ignoreCase = true)
        return recognisedScheme && !whitespaceInUrlRegex.containsMatchIn(trimmed)
    }

    /** Direct playable URL for a stream, or null when the stream only carries a torrent/site reference. */
    fun playbackUrlFor(stream: StremioStream): String? {
        stream.url?.trim()?.takeIf { isPlayableStreamUrl(it) }?.let { return it }
        stream.ytId?.trim()?.takeIf { it.isNotEmpty() }?.let { return "https://www.youtube.com/watch?v=$it" }
        return null
    }

    /**
     * Magnet URI for an infoHash-only stream, or null when the stream has no hash.
     *
     * Cloudstream's bundled torrent engine plays these: the host app turns a magnet
     * link into a local range server (`127.0.0.1`) and streams it, so a P2P Stremio
     * stream is playable without a debrid account. `index` selects a file inside
     * multi-file torrents (season packs).
     */
    fun torrentUrlFor(stream: StremioStream): String? {
        val hash = stream.infoHash?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val parts = ArrayList<String>()
        parts.add("xt=urn:btih:$hash")
        stream.behaviorHints?.filename?.trim()?.takeIf { it.isNotEmpty() }?.let {
            parts.add("dn=${encodeQueryValue(it)}")
        }
        stream.sources.orEmpty().forEach { raw ->
            val tracker = when {
                raw.startsWith("tracker:", ignoreCase = true) -> raw.substringAfter("tracker:")
                raw.startsWith("dht:", ignoreCase = true) -> null
                raw.contains("://") -> raw
                else -> null
            }?.trim()?.takeIf { it.isNotEmpty() }
            if (tracker != null) parts.add("tr=${encodeQueryValue(tracker)}")
        }
        stream.fileIdx?.takeIf { it >= 0 }?.let { parts.add("index=$it") }
        return "magnet:?" + parts.joinToString("&")
    }

    fun isTorrentUrl(url: String): Boolean = url.startsWith("magnet:", ignoreCase = true)

    fun requestHeadersFor(stream: StremioStream): Map<String, String> {
        val provided = stream.behaviorHints?.proxyHeaders?.request.orEmpty()
        if (provided.keys.any { it.equals("User-Agent", ignoreCase = true) }) return provided
        return provided + ("User-Agent" to DEFAULT_STREAM_USER_AGENT)
    }

    fun extractReferer(headers: Map<String, String>): String =
        headers.entries.firstOrNull {
            it.key.equals("referer", ignoreCase = true) || it.key.equals("referrer", ignoreCase = true)
        }?.value.orEmpty()

    fun streamDisplayName(stream: StremioStream): String? {
        val name = stream.name?.trim()?.takeIf { it.isNotEmpty() }
        val title = stream.title?.trim()?.takeIf { it.isNotEmpty() }
        val filename = stream.behaviorHints?.filename?.trim()?.takeIf { it.isNotEmpty() }
        val combined = when {
            name != null && title != null && !title.startsWith(name) -> "$name - $title"
            title != null -> title
            name != null -> name
            filename != null -> filename
            else -> stream.description?.trim()?.takeIf { it.isNotEmpty() }
        }
        return combined?.replace(whitespaceRegex, " ")?.take(180)
    }

    /** Quality value (144..2160), 0 when unknown. */
    fun parseQuality(vararg texts: String?): Int {
        val haystack = texts.filterNotNull().joinToString(" ").lowercase()
        if (haystack.isBlank()) return 0
        qualityPatterns.forEach { (quality, regex) ->
            if (regex.containsMatchIn(haystack)) return quality
        }
        return 0
    }

    fun yearFromReleaseInfo(releaseInfo: String?): Int? =
        releaseInfo?.let { yearRegex.find(it)?.value?.toIntOrNull() }

    /**
     * Episode label for a Stremio video. The protocol uses `name`, but some addons
     * (e.g. Nuvio's TMDB catalog) put the episode title in `title` instead.
     */
    fun episodeName(video: StremioVideo): String? =
        video.name?.trim()?.takeIf { it.isNotEmpty() } ?: video.title?.trim()?.takeIf { it.isNotEmpty() }
}
