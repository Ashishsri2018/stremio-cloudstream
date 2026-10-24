package com.cs3addon.StreamioLink

import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey

/**
 * Persisted plugin settings, stored in the Cloudstream data store.
 * One or more catalog addons (the first is the primary home/meta/search addon) plus any
 * number of source addons. Each catalog addon can additionally be used as a stream source.
 */
object StremioSettings {

    const val DEFAULT_CATALOG_URL = "https://v3-cinemeta.strem.io"
    const val DEFAULT_META_URL = "https://v3-cinemeta.strem.io"

    /** A configured catalog addon. Order in the list is the home-row order. */
    data class CatalogEntry(val url: String, val useAsSource: Boolean)

    private const val KEY_CATALOG_URL = "streamiolink_catalog_url"
    private const val KEY_CATALOG_URLS = "streamiolink_catalog_urls"
    private const val KEY_CATALOG_SOURCE_URLS = "streamiolink_catalog_source_urls"
    private const val KEY_USE_CATALOG_AS_SOURCE = "streamiolink_use_catalog_as_source"
    private const val KEY_META_URL = "streamiolink_meta_url"
    private const val KEY_SOURCE_URLS = "streamiolink_source_urls"

    fun catalogEntries(): List<CatalogEntry> {
        val stored = getKey<Array<String>>(KEY_CATALOG_URLS)
            ?.mapNotNull { StremioMapping.normalizeAddonUrl(it) }
            ?.distinct()
            .orEmpty()
        if (stored.isEmpty()) {
            // Migrate the pre-multi-catalog settings (single URL + global source toggle).
            val legacyUrl = StremioMapping.normalizeAddonUrl(getKey<String>(KEY_CATALOG_URL))
                ?: DEFAULT_CATALOG_URL
            val legacySource = getKey<Boolean>(KEY_USE_CATALOG_AS_SOURCE) ?: true
            return listOf(CatalogEntry(legacyUrl, legacySource))
        }
        val sourceUrls = getKey<Array<String>>(KEY_CATALOG_SOURCE_URLS)
            ?.mapNotNull { StremioMapping.normalizeAddonUrl(it) }
            ?.toSet()
            .orEmpty()
        return stored.map { CatalogEntry(it, it in sourceUrls) }
    }

    /** Catalog addons in home-row order; the first entry is the primary addon. */
    fun catalogUrls(): List<String> = catalogEntries().map { it.url }

    fun catalogUrl(): String = catalogUrls().firstOrNull() ?: DEFAULT_CATALOG_URL

    fun metaUrl(): String =
        StremioMapping.normalizeAddonUrl(getKey<String>(KEY_META_URL)) ?: DEFAULT_META_URL

    fun sourceUrls(): List<String> =
        getKey<Array<String>>(KEY_SOURCE_URLS)
            ?.mapNotNull { StremioMapping.normalizeAddonUrl(it) }
            ?.distinct()
            .orEmpty()

    /** All addons that should be queried for streams: enabled catalogs first, then sources. */
    fun streamAddonUrls(): List<String> {
        val urls = linkedSetOf<String>()
        catalogEntries().filter { it.useAsSource }.forEach { urls.add(it.url) }
        urls.addAll(sourceUrls())
        return urls.toList()
    }

    fun save(
        catalogs: List<CatalogEntry>,
        metaUrl: String?,
        sourceUrls: List<String>,
    ) {
        val entries = catalogs
            .mapNotNull { entry ->
                StremioMapping.normalizeAddonUrl(entry.url)?.let { CatalogEntry(it, entry.useAsSource) }
            }
            .distinctBy { it.url }
            .ifEmpty { listOf(CatalogEntry(DEFAULT_CATALOG_URL, true)) }

        setKey(KEY_CATALOG_URLS, entries.map { it.url }.toTypedArray())
        setKey(
            KEY_CATALOG_SOURCE_URLS,
            entries.filter { it.useAsSource }.map { it.url }.toTypedArray(),
        )
        // Keep the legacy keys in sync so downgrading to an older plugin build still works.
        setKey(KEY_CATALOG_URL, entries.first().url)
        setKey(KEY_USE_CATALOG_AS_SOURCE, entries.first().useAsSource)
        setKey(KEY_META_URL, StremioMapping.normalizeAddonUrl(metaUrl) ?: DEFAULT_META_URL)
        setKey(
            KEY_SOURCE_URLS,
            sourceUrls.mapNotNull { StremioMapping.normalizeAddonUrl(it) }.distinct().toTypedArray()
        )
    }
}
