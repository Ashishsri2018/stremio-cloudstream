package com.magic.stremio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StremioManifestTest {

    private val manifestJson = """
        {
          "id": "org.test.addon",
          "name": "Test Addon",
          "version": "1.0.0",
          "types": ["movie", "series"],
          "resources": [
            "catalog",
            { "name": "stream", "types": ["movie"], "idPrefixes": ["tt"] },
            { "name": "subtitles" }
          ],
          "catalogs": [
            { "type": "movie", "id": "top", "name": "Top Movies" },
            { "type": "series", "id": "searchonly", "extra": [ { "name": "search", "isRequired": true } ] },
            { "type": "series", "id": "netflix", "extra": [ { "name": "genre", "isRequired": true, "options": ["action"] } ] },
            { "type": "movie", "id": "oldformat", "extraSupported": ["genre", "skip"], "extraRequired": ["genre"] },
            { "type": "movie", "id": "searchable", "extraSupported": ["search"] }
          ],
          "idPrefixes": ["tt"]
        }
    """.trimIndent()

    private val manifest = StremioMapping.parseManifest(manifestJson)
    private val catalogs = manifest.catalogs.orEmpty()

    @Test
    fun parsesBasicManifestFields() {
        assertEquals("Test Addon", manifest.name)
        assertEquals("org.test.addon", manifest.id)
        assertEquals(listOf("movie", "series"), manifest.types)
    }

    @Test
    fun readsResourcesOfBothForms() {
        assertEquals(setOf("catalog", "stream", "subtitles"), StremioMapping.manifestResourceNames(manifest))
        assertTrue(StremioMapping.manifestSupportsResource(manifest, "stream"))
        assertTrue(StremioMapping.manifestSupportsResource(manifest, "subtitles"))
        assertTrue(StremioMapping.manifestSupportsResource(manifest, "catalog"))
        assertFalse(StremioMapping.manifestSupportsResource(manifest, "meta"))
    }

    @Test
    fun resourceTypesPreferObjectLevelAndFallBackToManifest() {
        assertEquals(listOf("movie"), StremioMapping.resourceTypesFor(manifest, "stream"))
        assertEquals(listOf("movie", "series"), StremioMapping.resourceTypesFor(manifest, "catalog"))
        assertEquals(listOf("movie", "series"), StremioMapping.resourceTypesFor(manifest, "subtitles"))
    }

    @Test
    fun resourceIdPrefixesPreferObjectLevelAndFallBackToManifest() {
        assertEquals(listOf("tt"), StremioMapping.resourceIdPrefixesFor(manifest, "stream"))
        assertEquals(listOf("tt"), StremioMapping.resourceIdPrefixesFor(manifest, "catalog"))
    }

    @Test
    fun matchesIdPrefixes() {
        assertTrue(StremioMapping.idMatchesPrefixes("tt1234567", listOf("tt")))
        assertFalse(StremioMapping.idMatchesPrefixes("kitsu:123", listOf("tt")))
        assertTrue(StremioMapping.idMatchesPrefixes("anything", emptyList()))
    }

    @Test
    fun homeCatalogsSkipCatalogsWithRequiredExtras() {
        val byId = catalogs.associateBy { it.id }
        assertTrue(StremioMapping.catalogCanLoadHome(byId.getValue("top")))
        assertTrue(StremioMapping.catalogCanLoadHome(byId.getValue("searchable")))
        assertFalse(StremioMapping.catalogCanLoadHome(byId.getValue("searchonly")))
        assertFalse(StremioMapping.catalogCanLoadHome(byId.getValue("netflix")))
        assertFalse(StremioMapping.catalogCanLoadHome(byId.getValue("oldformat")))
    }

    @Test
    fun detectsSearchableCatalogs() {
        val byId = catalogs.associateBy { it.id }
        assertTrue(StremioMapping.catalogSupportsSearch(byId.getValue("searchable")))
        assertTrue(StremioMapping.catalogSupportsSearch(byId.getValue("searchonly")))
        assertFalse(StremioMapping.catalogSupportsSearch(byId.getValue("top")))
    }

    @Test
    fun buildsProtocolUrls() {
        val base = "https://addon.example"
        assertEquals("$base/catalog/movie/top.json", StremioMapping.buildCatalogUrl(base, "movie", "top"))
        assertEquals(
            "$base/catalog/movie/top/search=big%20buck.json",
            StremioMapping.buildCatalogUrl(base, "movie", "top", "search=${StremioMapping.encodeQueryValue("big buck")}")
        )
        assertEquals(
            "$base/catalog/series/netflix/skip=100.json",
            StremioMapping.buildCatalogUrl(base, "series", "netflix", "skip=100")
        )
        assertEquals("$base/meta/series/tt123:1:2.json", StremioMapping.buildMetaUrl(base, "series", "tt123:1:2"))
        assertEquals("$base/stream/movie/tt123.json", StremioMapping.buildStreamUrl(base, "movie", "tt123"))
    }

    @Test
    fun buildsSubtitlesUrlsWithAndWithoutHints() {
        val base = "https://addon.example"
        assertEquals(
            "$base/subtitles/series/tt123:1:2.json",
            StremioMapping.buildSubtitlesUrl(base, "series", "tt123:1:2", null, null)
        )
        assertEquals(
            "$base/subtitles/series/tt123:1:2/videoHash=abc&videoSize=1234.json",
            StremioMapping.buildSubtitlesUrl(base, "series", "tt123:1:2", "abc", 1234L)
        )
    }

    @Test
    fun encodesQueryValuesWithSpacesAndAmpersands() {
        assertEquals("big%20buck%20%26%20more", StremioMapping.encodeQueryValue("big buck & more"))
    }

    @Test
    fun normalizesAddonUrls() {
        assertEquals(
            "https://torrentio.strem.fun/realdebrid=KEY",
            StremioMapping.normalizeAddonUrl("https://torrentio.strem.fun/realdebrid=KEY/manifest.json")
        )
        assertEquals(
            "https://v3-cinemeta.strem.io",
            StremioMapping.normalizeAddonUrl(" https://v3-cinemeta.strem.io/ ")
        )
        assertEquals(
            "https://v3-cinemeta.strem.io",
            StremioMapping.normalizeAddonUrl("v3-cinemeta.strem.io")
        )
        assertEquals(
            "https://addon.example",
            StremioMapping.normalizeAddonUrl("https://addon.example/MANIFEST.JSON")
        )
        assertNull(StremioMapping.normalizeAddonUrl(""))
        assertNull(StremioMapping.normalizeAddonUrl(null))
        assertNull(StremioMapping.normalizeAddonUrl("not a url"))
    }
}
