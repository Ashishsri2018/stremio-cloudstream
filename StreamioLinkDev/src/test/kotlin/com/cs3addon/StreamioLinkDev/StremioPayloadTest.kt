package com.cs3addon.StreamioLinkDev

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
