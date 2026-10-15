package com.cs3addon.StreamioLink

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StremioStreamTest {

    private val streamsJson = """
        {
          "streams": [
            {
              "name": "Torrentio",
              "title": "Movie.2024.1080p.WEB-DL",
              "url": "https://cdn.example/a.m3u8",
              "behaviorHints": {
                "proxyHeaders": {
                  "request": { "User-Agent": "TestUA", "Referer": "https://ref.example/" }
                }
              }
            },
            { "ytId": "abc123", "name": "YouTube" },
            { "infoHash": "DEADBEEF", "title": "4K torrent" },
            {
              "url": "https://debrid.example/d/XYZ",
              "name": "RD+",
              "title": "Movie.2021.720p",
              "behaviorHints": {
                "filename": "Movie.2021.720p.mp4",
                "videoHash": "HASH123",
                "videoSize": 999
              }
            },
            { "externalUrl": "https://external.example/watch" }
          ]
        }
    """.trimIndent()

    private val streams = StremioMapping.parseStreams(streamsJson)

    @Test
    fun parsesAllStreamForms() {
        assertEquals(5, streams.size)
        assertEquals("Torrentio", streams[0].name)
        assertEquals("abc123", streams[1].ytId)
        assertEquals("DEADBEEF", streams[2].infoHash)
        assertEquals("HASH123", streams[3].behaviorHints?.videoHash)
        assertEquals(999L, streams[3].behaviorHints?.videoSize)
        assertEquals("https://external.example/watch", streams[4].externalUrl)
    }

    @Test
    fun playbackUrlOnlyForPlayableStreams() {
        assertEquals("https://cdn.example/a.m3u8", StremioMapping.playbackUrlFor(streams[0]))
        assertEquals("https://www.youtube.com/watch?v=abc123", StremioMapping.playbackUrlFor(streams[1]))
        assertNull(StremioMapping.playbackUrlFor(streams[2]))
        assertEquals("https://debrid.example/d/XYZ", StremioMapping.playbackUrlFor(streams[3]))
        assertNull(StremioMapping.playbackUrlFor(streams[4]))
    }

    @Test
    fun extractsProxyHeadersAndReferer() {
        val headers = StremioMapping.requestHeadersFor(streams[0])
        assertEquals("TestUA", headers["User-Agent"])
        assertEquals("https://ref.example/", StremioMapping.extractReferer(headers))
        assertTrue(StremioMapping.requestHeadersFor(streams[1]).isEmpty())
        assertEquals("", StremioMapping.extractReferer(emptyMap()))
    }

    @Test
    fun guessesStreamKindFromExtensionOrFilename() {
        assertEquals(StremioMapping.StreamKind.HLS, StremioMapping.guessStreamKind("https://cdn.example/a.m3u8?token=1"))
        assertEquals(StremioMapping.StreamKind.DASH, StremioMapping.guessStreamKind("https://cdn.example/a.mpd"))
        assertEquals(StremioMapping.StreamKind.VIDEO, StremioMapping.guessStreamKind("https://debrid.example/d/XYZ"))
        assertEquals(
            StremioMapping.StreamKind.VIDEO,
            StremioMapping.guessStreamKind("https://debrid.example/d/XYZ", "Movie.2021.720p.mp4")
        )
        assertEquals(
            StremioMapping.StreamKind.HLS,
            StremioMapping.guessStreamKind("https://debrid.example/d/XYZ", "playlist.m3u8")
        )
        // PenguPlay style: metadata appended after the extension with "|^|"
        assertEquals(
            StremioMapping.StreamKind.HLS,
            StremioMapping.guessStreamKind(
                "https://pengu.example/direct/external/opaque",
                "Breaking.Bad.S01.E01.720p.m3u8|^|720pMKVBluRay"
            )
        )
        assertEquals(
            StremioMapping.StreamKind.DASH,
            StremioMapping.guessStreamKind("https://debrid.example/d/XYZ", "manifest.mpd|^|1080p")
        )
        assertEquals(
            StremioMapping.StreamKind.VIDEO,
            StremioMapping.guessStreamKind(
                "https://pengu.example/direct/external/opaque",
                "The.Shawshank.Redemption.1994.720p.x264.mkv|^|720pMKVBluRay"
            )
        )
    }

    @Test
    fun parsesQualityFromAddonText() {
        assertEquals(1080, StremioMapping.parseQuality("Movie.2024.1080p.WEB-DL"))
        assertEquals(1080, StremioMapping.parseQuality("FullHD"))
        assertEquals(1080, StremioMapping.parseQuality("Full HD"))
        assertEquals(2160, StremioMapping.parseQuality("4K HDR"))
        assertEquals(2160, StremioMapping.parseQuality("2160p"))
        assertEquals(1440, StremioMapping.parseQuality("1440p"))
        assertEquals(720, StremioMapping.parseQuality("720p x265"))
        assertEquals(720, StremioMapping.parseQuality("HD"))
        assertEquals(480, StremioMapping.parseQuality("480p"))
        assertEquals(360, StremioMapping.parseQuality("360p"))
        assertEquals(240, StremioMapping.parseQuality("240p"))
        assertEquals(144, StremioMapping.parseQuality("144p"))
        assertEquals(0, StremioMapping.parseQuality("HDTS"))
        assertEquals(0, StremioMapping.parseQuality("SD"))
        assertEquals(0, StremioMapping.parseQuality(null))
        assertEquals(0, StremioMapping.parseQuality(""))
        assertEquals(1080, StremioMapping.parseQuality(null, "1080p", null))
    }

    @Test
    fun buildsStreamDisplayNames() {
        assertEquals("Torrentio - Movie.2024.1080p.WEB-DL", StremioMapping.streamDisplayName(streams[0]))
        assertEquals("YouTube", StremioMapping.streamDisplayName(streams[1]))
        assertEquals(
            "Torrentio 1080p",
            StremioMapping.streamDisplayName(
                StremioStream(name = "Torrentio", title = "Torrentio 1080p")
            )
        )
        assertEquals(
            "Movie.2021.720p.mp4",
            StremioMapping.streamDisplayName(StremioStream(behaviorHints = StremioBehaviorHints(filename = "Movie.2021.720p.mp4")))
        )
        assertNull(StremioMapping.streamDisplayName(StremioStream()))
    }

    @Test
    fun parsesReleaseYear() {
        assertEquals(2010, StremioMapping.yearFromReleaseInfo("2010–2015"))
        assertEquals(2021, StremioMapping.yearFromReleaseInfo("2021"))
        assertEquals(1995, StremioMapping.yearFromReleaseInfo("1995-01-01"))
        assertNull(StremioMapping.yearFromReleaseInfo("N/A"))
        assertNull(StremioMapping.yearFromReleaseInfo(null))
    }

    @Test
    fun parsesMetaDetailsWithEpisodes() {
        val meta = StremioMapping.parseMeta(
            """
            {
              "meta": {
                "id": "tt1234567",
                "type": "series",
                "name": "Test Show",
                "poster": "https://img/p.jpg",
                "description": "A show",
                "releaseInfo": "2019-2022",
                "genres": ["Drama"],
                "videos": [
                  { "id": "tt1234567:1:1", "name": "Pilot", "season": 1, "episode": 1,
                    "released": "2019-04-01", "thumbnail": "https://img/e.jpg", "overview": "First" }
                ]
              }
            }
            """.trimIndent()
        )
        assertEquals("Test Show", meta?.name)
        assertEquals(1, meta?.videos?.size)
        assertEquals("tt1234567:1:1", meta?.videos?.first()?.id)
        assertEquals(1, meta?.videos?.first()?.season)
        assertEquals(1, meta?.videos?.first()?.episode)
        assertEquals("2019-04-01", meta?.videos?.first()?.released)
    }

    @Test
    fun parsesSubtitles() {
        val subtitles = StremioMapping.parseSubtitles(
            """{ "subtitles": [ { "id": "1", "url": "https://subs.example/1.srt", "lang": "en" } ] }"""
        )
        assertEquals(1, subtitles.size)
        assertEquals("https://subs.example/1.srt", subtitles[0].url)
        assertEquals("en", subtitles[0].lang)
    }
}
