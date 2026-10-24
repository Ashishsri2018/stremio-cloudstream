package com.cs3addon.StreamioLink

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StremioPayloadTest {

    @Test
    fun payloadRoundTripForMovie() {
        val encoded = StremioMapping.encodePayload("tt1234567", "movie")
        val decoded = StremioMapping.decodePayload(encoded)
        assertEquals("tt1234567", decoded?.id)
        assertEquals("movie", decoded?.type)
    }

    @Test
    fun payloadRoundTripForEpisode() {
        val encoded = StremioMapping.encodePayload("tt1234567:2:5", "series")
        val decoded = StremioMapping.decodePayload(encoded)
        assertEquals("tt1234567:2:5", decoded?.id)
        assertEquals("series", decoded?.type)
    }

    @Test
    fun payloadRoundTripCarriesCatalogBase() {
        val encoded = StremioMapping.encodePayload("plaviohdtv-42", "tv", "https://hd.plavio.site/c/abc")
        val decoded = StremioMapping.decodePayload(encoded)
        assertEquals("plaviohdtv-42", decoded?.id)
        assertEquals("tv", decoded?.type)
        assertEquals("https://hd.plavio.site/c/abc", decoded?.base)
    }

    @Test
    fun decodesLegacyPayloadWithoutBase() {
        val decoded = StremioMapping.decodePayload("""{"id":"tt1234567","type":"movie"}""")
        assertEquals("tt1234567", decoded?.id)
        assertNull(decoded?.base)
    }

    @Test
    fun decodesRawJsonPayload() {
        val decoded = StremioMapping.decodePayload("""{"id":"kitsu:123","type":"series"}""")
        assertEquals("kitsu:123", decoded?.id)
        assertEquals("series", decoded?.type)
    }

    @Test
    fun rejectsNonJsonPayload() {
        assertNull(StremioMapping.decodePayload("https://example.com/movie"))
        assertNull(StremioMapping.decodePayload(""))
        assertNull(StremioMapping.decodePayload("{}"))
    }

    @Test
    fun ignoresUnknownPayloadFields() {
        val decoded = StremioMapping.decodePayload(
            """{"id":"tt1234567","type":"movie","extra":"future-field"}"""
        )
        assertEquals("tt1234567", decoded?.id)
        assertEquals("movie", decoded?.type)
    }

    @Test
    fun rejectsBlankIdOrType() {
        assertNull(StremioMapping.decodePayload("""{"id":"","type":"movie"}"""))
        assertNull(StremioMapping.decodePayload("""{"id":"   ","type":"movie"}"""))
        assertNull(StremioMapping.decodePayload("""{"id":"tt1234567","type":""}"""))
    }
}
