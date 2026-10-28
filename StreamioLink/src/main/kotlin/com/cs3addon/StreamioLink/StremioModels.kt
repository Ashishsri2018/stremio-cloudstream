package com.cs3addon.StreamioLink

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.JsonNode

/**
 * Data classes mirroring the Stremio addon protocol.
 * https://stremio.github.io/stremio-addon-sdk/protocol.html
 */

@JsonIgnoreProperties(ignoreUnknown = true)
data class StremioManifest(
    @JsonProperty("id") val id: String? = null,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("description") val description: String? = null,
    @JsonProperty("version") val version: String? = null,
    /** Entries are either plain strings or objects: { name, types, idPrefixes }. */
    @JsonProperty("resources") val resources: List<JsonNode>? = null,
    @JsonProperty("types") val types: List<String>? = null,
    @JsonProperty("catalogs") val catalogs: List<StremioCatalog>? = null,
    @JsonProperty("idPrefixes") val idPrefixes: List<String>? = null,
    @JsonProperty("logo") val logo: String? = null,
    @JsonProperty("background") val background: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class StremioCatalog(
    @JsonProperty("type") val type: String? = null,
    @JsonProperty("id") val id: String? = null,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("genres") val genres: List<String>? = null,
    @JsonProperty("extra") val extra: List<StremioExtra>? = null,
    @JsonProperty("extraSupported") val extraSupported: List<String>? = null,
    @JsonProperty("extraRequired") val extraRequired: List<String>? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class StremioExtra(
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("isRequired") val isRequired: Boolean? = null,
    @JsonProperty("options") val options: List<String>? = null,
    /** Provider-chosen value for a required extra (e.g. TMDB By Year's current year). */
    @JsonProperty("default") val defaultValue: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class StremioMetaPreview(
    @JsonProperty("id") val id: String? = null,
    @JsonProperty("type") val type: String? = null,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("poster") val poster: String? = null,
    @JsonProperty("posterShape") val posterShape: String? = null,
    @JsonProperty("background") val background: String? = null,
    @JsonProperty("logo") val logo: String? = null,
    @JsonProperty("description") val description: String? = null,
    @JsonProperty("releaseInfo") val releaseInfo: String? = null,
    @JsonProperty("imdbRating") val imdbRating: String? = null,
    @JsonProperty("genres") val genres: List<String>? = null,
    @JsonProperty("runtime") val runtime: String? = null,
    @JsonProperty("behaviorHints") val behaviorHints: StremioBehaviorHints? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class StremioCatalogResponse(
    @JsonProperty("metas") val metas: List<StremioMetaPreview>? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class StremioMetaDetails(
    @JsonProperty("id") val id: String? = null,
    @JsonProperty("type") val type: String? = null,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("poster") val poster: String? = null,
    @JsonProperty("background") val background: String? = null,
    @JsonProperty("logo") val logo: String? = null,
    @JsonProperty("description") val description: String? = null,
    @JsonProperty("releaseInfo") val releaseInfo: String? = null,
    @JsonProperty("imdbRating") val imdbRating: String? = null,
    @JsonProperty("genres") val genres: List<String>? = null,
    @JsonProperty("runtime") val runtime: String? = null,
    @JsonProperty("status") val status: String? = null,
    @JsonProperty("ageRating") val ageRating: String? = null,
    @JsonProperty("cast") val cast: List<String>? = null,
    /** AIOMetadata/Nuvio extension carrying cast objects, certifications and extra ratings. */
    @JsonProperty("app_extras") val appExtras: StremioAppExtras? = null,
    @JsonProperty("trailers") val trailers: List<StremioTrailer>? = null,
    @JsonProperty("trailerStreams") val trailerStreams: List<StremioTrailerStream>? = null,
    @JsonProperty("videos") val videos: List<StremioVideo>? = null,
    @JsonProperty("behaviorHints") val behaviorHints: StremioBehaviorHints? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class StremioAppExtras(
    @JsonProperty("cast") val cast: List<StremioCastMember>? = null,
    @JsonProperty("certification") val certification: String? = null,
    @JsonProperty("ratings") val ratings: StremioRatings? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class StremioCastMember(
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("character") val character: String? = null,
    @JsonProperty("photo") val photo: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class StremioRatings(
    @JsonProperty("imdb") val imdb: StremioRatingSource? = null,
    @JsonProperty("tmdb") val tmdb: StremioRatingSource? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class StremioRatingSource(
    @JsonProperty("rating") val rating: String? = null,
    @JsonProperty("votes") val votes: Long? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class StremioTrailer(
    @JsonProperty("source") val source: String? = null,
    @JsonProperty("ytId") val ytId: String? = null,
    @JsonProperty("type") val type: String? = null,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("lang") val lang: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class StremioTrailerStream(
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("ytId") val ytId: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class StremioVideo(
    @JsonProperty("id") val id: String? = null,
    @JsonProperty("name") val name: String? = null,
    /** Some addons (e.g. Nuvio's TMDB catalog) label the episode here instead of `name`. */
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("season") val season: Int? = null,
    @JsonProperty("episode") val episode: Int? = null,
    @JsonProperty("number") val number: Int? = null,
    @JsonProperty("thumbnail") val thumbnail: String? = null,
    @JsonProperty("released") val released: String? = null,
    @JsonProperty("overview") val overview: String? = null,
    @JsonProperty("rating") val rating: String? = null,
    @JsonProperty("runtime") val runtime: String? = null,
    @JsonProperty("available") val available: Boolean? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class StremioMetaResponse(
    @JsonProperty("meta") val meta: StremioMetaDetails? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class StremioStreamsResponse(
    @JsonProperty("streams") val streams: List<StremioStream>? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class StremioStream(
    @JsonProperty("url") val url: String? = null,
    @JsonProperty("ytId") val ytId: String? = null,
    @JsonProperty("infoHash") val infoHash: String? = null,
    /** Selected file inside a multi-file torrent (season packs). Used as the magnet `index`. */
    @JsonProperty("fileIdx") val fileIdx: Int? = null,
    @JsonProperty("externalUrl") val externalUrl: String? = null,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("description") val description: String? = null,
    @JsonProperty("behaviorHints") val behaviorHints: StremioBehaviorHints? = null,
    @JsonProperty("sources") val sources: List<String>? = null,
    /** Subtitles embedded directly on the stream object (a valid Stremio protocol field). */
    @JsonProperty("subtitles") val subtitles: List<StremioSubtitle>? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class StremioBehaviorHints(
    @JsonProperty("notWebReady") val notWebReady: Boolean? = null,
    @JsonProperty("filename") val filename: String? = null,
    @JsonProperty("bingeGroup") val bingeGroup: String? = null,
    @JsonProperty("videoHash") val videoHash: String? = null,
    @JsonProperty("videoSize") val videoSize: Long? = null,
    @JsonProperty("proxyHeaders") val proxyHeaders: StremioProxyHeaders? = null,
    /** Some addons put request headers here instead of under `proxyHeaders.request`. */
    @JsonProperty("headers") val headers: Map<String, String>? = null,
    /** Live-channel hint on meta items (official protocol: `meta.behaviorHints.isLive`). */
    @JsonProperty("isLive") val isLive: Boolean? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class StremioProxyHeaders(
    @JsonProperty("request") val request: Map<String, String>? = null,
    @JsonProperty("response") val response: Map<String, String>? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class StremioSubtitlesResponse(
    @JsonProperty("subtitles") val subtitles: List<StremioSubtitle>? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class StremioSubtitle(
    @JsonProperty("id") val id: String? = null,
    @JsonProperty("url") val url: String? = null,
    @JsonProperty("lang") val lang: String? = null,
    /** Some addons omit `lang` and only provide the ISO code here. */
    @JsonProperty("lang_code") val langCode: String? = null,
)

/**
 * Opaque payload that travels through Cloudstream and comes back to load()/loadLinks().
 * Never add behaviour to this class, it is only a transport object.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
data class StremioPayload(
    @JsonProperty("id") val id: String,
    @JsonProperty("type") val type: String,
    /** Catalog addon the item came from, so its metadata is fetched from the right addon. */
    @JsonProperty("base") val base: String? = null,
)

/**
 * Identifies a single catalog. Travels through Cloudstream inside [MainPageData.data]
 * so pagination requests can target one catalog at a time. [base] is the catalog addon URL
 * (multiple catalog addons are supported; the first one is the primary home addon).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class StremioCatalogRef(
    @JsonProperty("type") val type: String,
    @JsonProperty("id") val id: String,
    @JsonProperty("base") val base: String? = null,
)
