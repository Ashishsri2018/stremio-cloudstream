package com.cs3addon.StreamioLink

import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey

/**
 * Persisted plugin settings, stored in the Cloudstream data store.
 * One or more catalog addons (all enabled ones power home rows and search; the first enabled one
 * is the primary home/metadata addon) plus any number of source addons. Each catalog addon can
 * additionally be used as a stream source.
 * Entries can be disabled without being removed: they stay configured (and visible in settings)
 * but are skipped everywhere.
 */
object StremioSettings {

    const val DEFAULT_CATALOG_URL = "https://v3-cinemeta.strem.io"
    const val DEFAULT_META_URL = "https://v3-cinemeta.strem.io"

    /** A configured catalog addon. Order in the list is the home-row order. */
    data class CatalogEntry(
        val url: String,
        val useAsSource: Boolean,
        val enabled: Boolean = true,
    )

    /** A configured source addon (streams & subtitles). */
    data class SourceEntry(val url: String, val enabled: Boolean = true)

    private const val KEY_CATALOG_URL = "streamiolink_catalog_url"
    private const val KEY_CATALOG_URLS = "streamiolink_catalog_urls"
    private const val KEY_CATALOG_SOURCE_URLS = "streamiolink_catalog_source_urls"
    private const val KEY_DISABLED_CATALOG_URLS = "streamiolink_disabled_catalog_urls"
    private const val KEY_USE_CATALOG_AS_SOURCE = "streamiolink_use_catalog_as_source"
    private const val KEY_META_URL = "streamiolink_meta_url"
    private const val KEY_SOURCE_URLS = "streamiolink_source_urls"
    private const val KEY_DISABLED_SOURCE_URLS = "streamiolink_disabled_source_urls"

    /** Every configured catalog addon, including disabled ones (settings UI order). */
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
        val disabledUrls = getKey<Array<String>>(KEY_DISABLED_CATALOG_URLS)
            ?.mapNotNull { StremioMapping.normalizeAddonUrl(it) }
            ?.toSet()
            .orEmpty()
        return stored.map { CatalogEntry(it, it in sourceUrls, it !in disabledUrls) }
    }

    /** Enabled catalog addons in home-row order; the first entry is the primary addon. */
    fun catalogUrls(): List<String> = catalogEntries().filter { it.enabled }.map { it.url }

    fun catalogUrl(): String = catalogUrls().firstOrNull() ?: DEFAULT_CATALOG_URL

    fun metaUrl(): String =
        StremioMapping.normalizeAddonUrl(getKey<String>(KEY_META_URL)) ?: DEFAULT_META_URL

    /** Every configured source addon, including disabled ones (settings UI order). */
    fun sourceEntries(): List<SourceEntry> {
        val stored = getKey<Array<String>>(KEY_SOURCE_URLS)
            ?.mapNotNull { StremioMapping.normalizeAddonUrl(it) }
            ?.distinct()
            .orEmpty()
        val disabledUrls = getKey<Array<String>>(KEY_DISABLED_SOURCE_URLS)
            ?.mapNotNull { StremioMapping.normalizeAddonUrl(it) }
            ?.toSet()
            .orEmpty()
        return stored.map { SourceEntry(it, it !in disabledUrls) }
    }

    /** Enabled source addons only — the ones the provider queries. */
    fun sourceUrls(): List<String> = sourceEntries().filter { it.enabled }.map { it.url }

    /** All addons that should be queried for streams: enabled catalogs first, then sources. */
    fun streamAddonUrls(): List<String> {
        val urls = linkedSetOf<String>()
        catalogEntries().filter { it.enabled && it.useAsSource }.forEach { urls.add(it.url) }
        urls.addAll(sourceEntries().filter { it.enabled }.map { it.url })
        return urls.toList()
    }

    fun save(
        catalogs: List<CatalogEntry>,
        metaUrl: String?,
        sources: List<SourceEntry>,
    ) {
        val entries = catalogs
            .mapNotNull { entry ->
                StremioMapping.normalizeAddonUrl(entry.url)?.let {
                    CatalogEntry(it, entry.useAsSource, entry.enabled)
                }
            }
            .distinctBy { it.url }
            .ifEmpty { listOf(CatalogEntry(DEFAULT_CATALOG_URL, true)) }
            // The provider must always have a primary addon; the UI already prevents this
            // state, so this is only a defensive fallback.
            .let { list -> if (list.any { it.enabled }) list else list.mapIndexed { index, entry ->
                if (index == 0) entry.copy(enabled = true) else entry
            } }

        val normalizedSources = sources
            .mapNotNull { entry ->
                StremioMapping.normalizeAddonUrl(entry.url)?.let { SourceEntry(it, entry.enabled) }
            }
            .distinctBy { it.url }

        setKey(KEY_CATALOG_URLS, entries.map { it.url }.toTypedArray())
        setKey(
            KEY_CATALOG_SOURCE_URLS,
            entries.filter { it.useAsSource }.map { it.url }.toTypedArray(),
        )
        setKey(
            KEY_DISABLED_CATALOG_URLS,
            entries.filter { !it.enabled }.map { it.url }.toTypedArray(),
        )
        // Keep the legacy keys in sync so downgrading to an older plugin build still works.
        val legacyPrimary = entries.firstOrNull { it.enabled } ?: entries.first()
        setKey(KEY_CATALOG_URL, legacyPrimary.url)
        setKey(KEY_USE_CATALOG_AS_SOURCE, legacyPrimary.useAsSource)
        setKey(KEY_META_URL, StremioMapping.normalizeAddonUrl(metaUrl) ?: DEFAULT_META_URL)
        setKey(KEY_SOURCE_URLS, normalizedSources.map { it.url }.toTypedArray())
        setKey(
            KEY_DISABLED_SOURCE_URLS,
            normalizedSources.filter { !it.enabled }.map { it.url }.toTypedArray(),
        )
    }
}
