package com.magic.stremio

import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey

/**
 * Persisted plugin settings, stored in the Cloudstream data store.
 * One catalog addon + any number of source addons.
 */
object StremioSettings {

    const val DEFAULT_CATALOG_URL = "https://v3-cinemeta.strem.io"
    const val DEFAULT_META_URL = "https://v3-cinemeta.strem.io"

    private const val KEY_CATALOG_URL = "streamiolink_catalog_url"
    private const val KEY_META_URL = "streamiolink_meta_url"
    private const val KEY_SOURCE_URLS = "streamiolink_source_urls"
    private const val KEY_USE_CATALOG_AS_SOURCE = "streamiolink_use_catalog_as_source"

    fun catalogUrl(): String =
        StremioMapping.normalizeAddonUrl(getKey<String>(KEY_CATALOG_URL)) ?: DEFAULT_CATALOG_URL

    fun metaUrl(): String =
        StremioMapping.normalizeAddonUrl(getKey<String>(KEY_META_URL)) ?: DEFAULT_META_URL

    fun sourceUrls(): List<String> =
        getKey<Array<String>>(KEY_SOURCE_URLS)
            ?.mapNotNull { StremioMapping.normalizeAddonUrl(it) }
            ?.distinct()
            .orEmpty()

    fun useCatalogAsSource(): Boolean =
        getKey<Boolean>(KEY_USE_CATALOG_AS_SOURCE) ?: true

    /** All addons that should be queried for streams, catalog addon first, de-duplicated. */
    fun streamAddonUrls(): List<String> {
        val urls = linkedSetOf<String>()
        if (useCatalogAsSource()) urls.add(catalogUrl())
        urls.addAll(sourceUrls())
        return urls.toList()
    }

    fun save(
        catalogUrl: String?,
        metaUrl: String?,
        sourceUrls: List<String>,
        useCatalogAsSource: Boolean,
    ) {
        setKey(KEY_CATALOG_URL, StremioMapping.normalizeAddonUrl(catalogUrl) ?: DEFAULT_CATALOG_URL)
        setKey(KEY_META_URL, StremioMapping.normalizeAddonUrl(metaUrl) ?: DEFAULT_META_URL)
        setKey(
            KEY_SOURCE_URLS,
            sourceUrls.mapNotNull { StremioMapping.normalizeAddonUrl(it) }.distinct().toTypedArray()
        )
        setKey(KEY_USE_CATALOG_AS_SOURCE, useCatalogAsSource)
    }
}
