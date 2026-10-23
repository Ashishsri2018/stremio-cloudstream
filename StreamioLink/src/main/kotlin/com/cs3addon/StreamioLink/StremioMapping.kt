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

    /**
     * Catalog types that can be mapped onto Cloudstream. `anime` catalogs carry the same
     * movie/series metas as the others (their items declare `type`), so they are loadable too;
     * excluding them dropped whole rows from addons such as AIOMetadata.
     */
    val SUPPORTED_MEDIA_TYPES = setOf("movie", "series", "anime", "anime.movie", "anime.series")

    /**
     * Stremio CDNs (e.g. the `hakunaymatata.com` hosts behind MovieBox/VidLink) return
     * HTTP 428 when the request carries a Mozilla/browser User-Agent, but 200 for
     * non-browser agents. [DEFAULT_STREAM_USER_AGENT] is applied to streams whose addon
     * does not specify its own User-Agent so Cloudstream does not fall back to its
     * global Mozilla UA.
     */
    const val DEFAULT_STREAM_USER_AGENT = "okhttp/4.12.0"

    private val manifestSuffixRegex = Regex("""/manifest\.json/?$""", RegexOption.IGNORE_CASE)
    private val videoFileRegex = Regex("""\.(mp4|mkv|webm|avi|mov|m4v|flv)(?![0-9a-z])""")
    private val yearRegex = Regex("""\b(19|20)\d{2}\b""")
    private val whitespaceRegex = Regex("""\s+""")
    private val whitespaceInUrlRegex = Regex("""\s""")

    /** Cap on how many language tags are shown for one stream, keeping the label compact. */
    private const val MAX_LANGUAGE_TAGS = 3

    private val languageTokenRegex = Regex("""[A-Za-z]+""")
    private val dualAudioRegex = Regex("""dual[\s\-_.]*audio""", RegexOption.IGNORE_CASE)

    /**
     * Languages surfaced on the link label. Deliberately tiny — only the languages this setup
     * actually uses — so detection stays cheap. Stremio streams carry no language field, so this
     * is text-mined from `name`/`title`/`description`/`filename`. "Dual audio" is reported as
     * `Multi`.
     */
    private val languageAliases: Map<String, String> = mapOf(
        "english" to "English",
        "eng" to "English",
        "hindi" to "Hindi",
        "hin" to "Hindi",
        "multi" to "Multi",
    )

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

    /** Language label for a subtitle: `lang`, then `lang_code`, else "Unknown". */
    fun subtitleLanguage(subtitle: StremioSubtitle): String =
        subtitle.lang?.trim()?.takeIf { it.isNotEmpty() }
            ?: subtitle.langCode?.trim()?.takeIf { it.isNotEmpty() }
            ?: "Unknown"

    /**
     * Subtitles embedded on a stream (`stream.subtitles`), flattened across [streams] and
     * deduped by URL. Entries without a URL are skipped.
     */
    fun embeddedSubtitles(streams: List<StremioStream>): List<StremioSubtitle> {
        val seen = mutableSetOf<String>()
        val result = ArrayList<StremioSubtitle>()
        streams.forEach { stream ->
            stream.subtitles.orEmpty().forEach { subtitle ->
                val url = subtitle.url?.trim()?.takeIf { it.isNotEmpty() } ?: return@forEach
                if (seen.add(url)) result.add(subtitle)
            }
        }
        return result
    }

    // ---------------------------------------------------------------- payload

    fun encodePayload(id: String, type: String): String =
        mapper.writeValueAsString(StremioPayload(id = id, type = type))

    fun decodePayload(data: String): StremioPayload? = try {
        mapper.readValue(data, StremioPayload::class.java)
            // Reject blank ids/types: they would build URLs like `/stream/movie/.json`.
            .takeIf { it.id.isNotBlank() && it.type.isNotBlank() }
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
        if (!isSupportedMediaType(catalog.type)) return false
        if (catalog.id.isNullOrBlank()) return false
        // Search/similar/calendar catalogs need a caller-supplied value we cannot invent.
        // Genre-like required extras are resolved from the manifest's default or first option.
        return catalogPresetExtra(catalog) != null
    }

    fun isSupportedMediaType(type: String?): Boolean =
        SUPPORTED_MEDIA_TYPES.contains(type?.trim()?.lowercase())

    /** True for media types whose metadata carries episodes. */
    fun isSeriesType(type: String?): Boolean = when (type?.trim()?.lowercase()) {
        "series", "anime.series", "tv", "tvseries" -> true
        else -> false
    }

    /**
     * Value for the catalog's required extras, as a protocol path extra (`genre=Fall%202026`).
     * Cloudstream's home screen has no genre picker, so a required extra can only be satisfied
     * from the manifest itself: its `default`, or the first `options` entry. Returns null when a
     * required extra cannot be resolved (e.g. `search`, `similar`, `calendarVideosIds`), and the
     * empty string when the catalog has no required extras.
     */
    fun catalogPresetExtra(catalog: StremioCatalog): String? {
        val requiredNames = LinkedHashSet<String>()
        catalog.extra.orEmpty()
            .filter { it.isRequired == true }
            .forEach { extra -> extra.name?.takeIf { it.isNotBlank() }?.let { requiredNames.add(it) } }
        catalog.extraRequired.orEmpty()
            .filter { it.isNotBlank() }
            .forEach { requiredNames.add(it) }
        if (requiredNames.isEmpty()) return ""

        val values = LinkedHashMap<String, String>()
        for (name in requiredNames) {
            val extra = catalog.extra.orEmpty().firstOrNull { it.name.equals(name, ignoreCase = true) }
                ?: return null
            val value = extra.defaultValue?.trim()?.takeIf { it.isNotEmpty() }
                ?: extra.options.orEmpty().firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
                ?: return null
            values[name] = value
        }
        return values.entries.joinToString("&") { (name, value) -> "$name=${encodeQueryValue(value)}" }
    }

    /** Joins a catalog's preset extras with `search`/`skip`, keeping all of them in one path extra. */
    fun catalogExtra(preset: String?, skip: Int? = null, search: String? = null): String? {
        val parts = ArrayList<String>(3)
        preset?.takeIf { it.isNotBlank() }?.let { parts.add(it) }
        search?.takeIf { it.isNotBlank() }?.let { parts.add("search=$it") }
        skip?.takeIf { it > 0 }?.let { parts.add("skip=$it") }
        return parts.takeIf { it.isNotEmpty() }?.joinToString("&")
    }

    fun catalogSupportsSearch(catalog: StremioCatalog): Boolean {
        if (!isSupportedMediaType(catalog.type)) return false
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

    /**
     * Row/tab titles for [catalogs], disambiguated by media type when a name is shared.
     *
     * Cloudstream stores home rows in a map keyed by title (`HomeViewModel.expandable[list.name]`),
     * so two catalogs called "TMDB Popular" (movie + series) collapse into one and the later row
     * silently replaces the earlier one. Adding "(Movies)/(Series)" keeps both rows and keeps the
     * tab/row names in sync (expansion looks tabs up by name).
     */
    fun catalogTitles(catalogs: List<StremioCatalog>, addonName: String): List<Pair<StremioCatalog, String>> {
        val baseTitles = catalogs.map { catalogBaseTitle(it, addonName) }
        val duplicated = baseTitles.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        val used = HashMap<String, Int>()
        return catalogs.mapIndexed { index, catalog ->
            var title = baseTitles[index]
            if (title in duplicated) title = "$title (${mediaTypeLabel(catalog.type)})"
            val seen = (used[title] ?: 0) + 1
            used[title] = seen
            if (seen > 1) title = "$title $seen"
            catalog to title
        }
    }

    fun catalogBaseTitle(catalog: StremioCatalog, addonName: String): String {
        catalog.name?.takeIf { it.isNotBlank() }?.let { return it }
        val type = catalog.type.orEmpty().replaceFirstChar { it.uppercase() }
        return "$addonName $type".trim()
    }

    private fun mediaTypeLabel(type: String?): String = when (type?.trim()?.lowercase()) {
        "movie" -> "Movies"
        "series" -> "Series"
        "anime" -> "Anime"
        "anime.movie" -> "Anime Movies"
        "anime.series" -> "Anime Series"
        else -> type.orEmpty().replaceFirstChar { it.uppercase() }.ifBlank { "Other" }
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

    /**
     * URL shapes to try for the `subtitles` resource, in order. The hint-bearing form
     * (`/subtitles/{type}/{id}/videoHash=…&videoSize=….json`) is the canonical Stremio
     * request, but some addons only implement the extra-less route and answer anything
     * with a path extra with an HTML 404 (e.g. PlavioHD). When hints exist both shapes are
     * returned so [StremioApi.getSubtitles] can fall back; without hints only one.
     */
    fun buildSubtitlesUrlCandidates(
        base: String,
        type: String,
        id: String,
        videoHash: String?,
        videoSize: Long?,
    ): List<String> {
        val withExtra = buildSubtitlesUrl(base, type, id, videoHash, videoSize)
        val plain = buildSubtitlesUrl(base, type, id, null, null)
        return if (withExtra == plain) listOf(plain) else listOf(withExtra, plain)
    }

    // ---------------------------------------------------------------- stream helpers

    enum class StreamKind { HLS, DASH, VIDEO }

    fun guessStreamKind(url: String, filename: String? = null): StreamKind {
        val lower = url.lowercase()
        val path = lower.substringBefore('?').substringBefore('#')
        val name = filename?.lowercase().orEmpty()
        // Addons such as PenguPlay append metadata after the extension
        // (e.g. "movie.mkv|^|720p..."), so check for the marker anywhere in path/name
        // instead of only at the end of the string.
        val isMpd = path.contains(".mpd") || name.contains(".mpd")
        val isM3u8 = path.contains(".m3u8") || name.contains(".m3u8")
        // Explicit extensions are authoritative and beat the semantic path hints below: a DASH
        // manifest can sit under an `/hls/` path, and a direct file can live in a "playlist"
        // folder. A known video-container extension means a direct file, not a playlist.
        if (isMpd && !isM3u8) return StreamKind.DASH
        if (isM3u8) return StreamKind.HLS
        if (videoFileRegex.containsMatchIn(path + " " + name)) return StreamKind.VIDEO
        // Fall back to hints, scanning the full URL last so an extension hidden in a proxy
        // query is still recognised (e.g. `.../proxy/bound?d=...%2F...m3u8`).
        return when {
            lower.contains(".m3u8") || path.contains("/hls") || path.contains("/playlist") ||
                lower.contains("mpegurl") -> StreamKind.HLS
            lower.contains(".mpd") -> StreamKind.DASH
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
        val hints = stream.behaviorHints
        // Prefer proxyHeaders.request; some addons emit a top-level `headers` instead.
        val provided = hints?.proxyHeaders?.request?.takeIf { it.isNotEmpty() }
            ?: hints?.headers.orEmpty()
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

    /**
     * Audio languages advertised by the addon for this stream, in order of appearance.
     *
     * The Stremio stream object has no language field, so the addons write it into free text:
     * `description` (HdHub: `...Hindi.English...`; PenguPlay: `🎧 Audio: English, Hindi`),
     * `title` (PlavioHD: `480p · Hindi, English`) or `behaviorHints.filename`
     * (`...Rus.Eng.mkv`). Detected names are canonicalized, deduped (case-insensitively) and
     * capped at [MAX_LANGUAGE_TAGS]. "Dual audio" is reported as `Multi`.
     */
    fun streamLanguages(stream: StremioStream): List<String> {
        val haystack = listOfNotNull(
            stream.name,
            stream.title,
            stream.description,
            stream.behaviorHints?.filename,
        ).joinToString(" ")
        if (haystack.isBlank()) return emptyList()

        val found = LinkedHashSet<String>()
        if (dualAudioRegex.containsMatchIn(haystack)) found.add("Multi")
        languageTokenRegex.findAll(haystack).forEach { match ->
            languageAliases[match.value.lowercase()]?.let { found.add(it) }
        }
        return found.take(MAX_LANGUAGE_TAGS)
    }

    /** Compact `[Hindi, English]` tag for a stream, or null when no language was detected. */
    fun languageTag(stream: StremioStream): String? =
        streamLanguages(stream).takeIf { it.isNotEmpty() }?.joinToString(", ", "[", "]")

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
