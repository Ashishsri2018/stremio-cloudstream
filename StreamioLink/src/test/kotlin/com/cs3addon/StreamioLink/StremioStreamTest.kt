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
    fun rejectsUnplayableStreamUrls() {
        assertTrue(StremioMapping.isPlayableStreamUrl("https://cdn.example/a.m3u8"))
        assertTrue(StremioMapping.isPlayableStreamUrl("http://140.238.244.130/proxy/bound?d=x"))
        assertTrue(StremioMapping.isPlayableStreamUrl("magnet:?xt=urn:btih:DEADBEEF"))
        assertTrue(!StremioMapping.isPlayableStreamUrl("https://cdn.example/hub/my file.mkv"))
        assertTrue(!StremioMapping.isPlayableStreamUrl("ftp://files.example/a.mkv"))
        assertTrue(!StremioMapping.isPlayableStreamUrl(""))
        // A raw URL containing a literal space (CNCVerse) must not reach the player.
        assertNull(StremioMapping.playbackUrlFor(StremioStream(url = "https://cdn.example/my file.mkv")))
        assertEquals(
            "https://cdn.example/ok.mkv",
            StremioMapping.playbackUrlFor(StremioStream(url = "https://cdn.example/ok.mkv"))
        )
        // A magnet carried in `url` (no infoHash) must still be offered.
        assertEquals(
            "magnet:?xt=urn:btih:DEADBEEF",
            StremioMapping.playbackUrlFor(StremioStream(url = "magnet:?xt=urn:btih:DEADBEEF"))
        )
    }

    @Test
    fun dropsSentinelNoStreamsPlaceholders() {
        // Yukistreams answers an empty result with a stock error video instead of `[]`.
        val json = """
            {
              "streams": [
                {
                  "name": "[YS INFO] No streams found",
                  "title": "No playable streams found\nMelo Movie (2025)\nMovie",
                  "url": "https://stremio.yukistreams.xyz/assets/stream-errors/locked.mp4",
                  "behaviorHints": { "notWebReady": true }
                },
                {
                  "url": "https://stremio.yukistreams.xyz/assets/stream-errors/no-stream-found.mp4",
                  "behaviorHints": { "notWebReady": true }
                },
                {
                  "name": "Yuki Direct\nHLS",
                  "title": "Show EP 01",
                  "url": "https://hls22.cdnvideo11.shop/master/abc.m3u8"
                }
              ]
            }
        """.trimIndent()
        val streams = StremioMapping.parseStreams(json)
        assertTrue(StremioMapping.isSentinelStream(streams[0]))
        assertTrue(StremioMapping.isSentinelStream(streams[1]))
        assertTrue(!StremioMapping.isSentinelStream(streams[2]))
        assertNull(StremioMapping.playbackUrlFor(streams[0]))
        assertNull(StremioMapping.playbackUrlFor(streams[1]))
        assertEquals(
            "https://hls22.cdnvideo11.shop/master/abc.m3u8",
            StremioMapping.playbackUrlFor(streams[2])
        )
    }

    @Test
    fun dropsHealthEndpointPlaceholders() {
        // PlavioHD emits its status JSON as a "stream" for non-started events and for
        // channels that only work in an external player.
        val stream = StremioStream(
            name = "Tip: Use an external player",
            url = "https://sports.highfly.to/health",
        )
        assertTrue(StremioMapping.isSentinelStream(stream))
        assertNull(StremioMapping.playbackUrlFor(stream))
        assertTrue(StremioMapping.isSentinelStream(StremioStream(url = "https://x.example/health?check=1")))
        assertTrue(!StremioMapping.isSentinelStream(StremioStream(url = "https://x.example/healthy/stream.m3u8")))
    }

    @Test
    fun buildsMagnetFromInfoHashStream() {
        val stream = StremioStream(
            infoHash = "45fa4233ef87c58f5f8b4817e4d50c9f5363caef",
            fileIdx = 3,
            behaviorHints = StremioBehaviorHints(filename = "Show S01E02 1080p.mkv"),
            sources = listOf(
                "dht:45fa4233ef87c58f5f8b4817e4d50c9f5363caef",
                "tracker:udp://tracker.opentrackr.org:1337/announce",
                "tracker:https://tracker.example/announce",
            ),
        )
        val magnet = StremioMapping.torrentUrlFor(stream)!!
        assertTrue(magnet.startsWith("magnet:?xt=urn:btih:45fa4233ef87c58f5f8b4817e4d50c9f5363caef"))
        assertTrue(magnet.contains("dn=Show%20S01E02%201080p.mkv"))
        assertTrue(
            magnet.contains(
                "tr=" + StremioMapping.encodeQueryValue("udp://tracker.opentrackr.org:1337/announce")
            )
        )
        assertTrue(
            magnet.contains("tr=" + StremioMapping.encodeQueryValue("https://tracker.example/announce"))
        )
        assertTrue(magnet.contains("&index=3"))
        assertTrue(!magnet.contains("dht%3A"))
    }

    @Test
    fun magnetIsOnlyBuiltForInfoHashStreams() {
        assertNull(StremioMapping.torrentUrlFor(streams[0])) // direct url, no hash
        assertNull(StremioMapping.torrentUrlFor(streams[4])) // externalUrl only
        assertEquals("magnet:?xt=urn:btih:DEADBEEF", StremioMapping.torrentUrlFor(streams[2]))
        assertTrue(StremioMapping.isTorrentUrl("magnet:?xt=urn:btih:abc"))
        assertTrue(!StremioMapping.isTorrentUrl("https://cdn.example/a.m3u8"))
    }

    @Test
    fun detectsYouTubePageUrls() {
        assertTrue(StremioMapping.isYouTubeUrl("https://www.youtube.com/watch?v=abc123"))
        assertTrue(StremioMapping.isYouTubeUrl("https://m.youtube.com/watch?v=abc123"))
        assertTrue(StremioMapping.isYouTubeUrl("https://youtu.be/abc123"))
        assertTrue(StremioMapping.isYouTubeUrl("https://www.youtube-nocookie.com/embed/abc123"))
        assertTrue(!StremioMapping.isYouTubeUrl("https://cdn.example/a.m3u8"))
        assertTrue(!StremioMapping.isYouTubeUrl("https://notyoutube.example/watch?v=abc"))
        assertTrue(!StremioMapping.isYouTubeUrl(""))
    }

    @Test
    fun parsesPublicTrackerList() {
        val text = """
            # comment
            udp://tracker.opentrackr.org:1337/announce

            https://tracker.example/announce
            wss://tracker.example/socket
            http://tracker.example/plain
            ftp://tracker.example/ignored
            https://bad.example/announce with space
            udp://tracker.opentrackr.org:1337/announce
            not-a-url
        """.trimIndent() + "\r\n"
        assertEquals(
            listOf(
                "udp://tracker.opentrackr.org:1337/announce",
                "https://tracker.example/announce",
                "wss://tracker.example/socket",
                "http://tracker.example/plain",
            ),
            StremioMapping.parsePublicTrackers(text)
        )
        assertEquals(2, StremioMapping.parsePublicTrackers(text, max = 2).size)
        assertTrue(StremioMapping.parsePublicTrackers(text, max = 0).isEmpty())
    }

    @Test
    fun mergesPublicTrackersIntoMagnet() {
        val stream = StremioStream(
            infoHash = "DEADBEEF",
            fileIdx = 2,
            behaviorHints = StremioBehaviorHints(filename = "Show S01E01.mkv"),
            sources = listOf(
                "tracker:udp://addon.example:1337/announce",
                "https://tracker.example/announce",
            ),
        )
        val magnet = StremioMapping.torrentUrlFor(
            stream,
            listOf(
                "https://tracker.example/announce",
                "udp://public.example:1337/announce",
                "  ",
            ),
        )!!
        assertTrue(magnet.startsWith("magnet:?xt=urn:btih:DEADBEEF"))
        assertTrue(magnet.contains("dn=Show%20S01E01.mkv"))
        assertEquals(3, Regex("""[?&]tr=""").findAll(magnet).count())
        assertTrue(
            magnet.indexOf("tr=" + StremioMapping.encodeQueryValue("udp://addon.example:1337/announce")) <
                magnet.indexOf("tr=" + StremioMapping.encodeQueryValue("udp://public.example:1337/announce"))
        )
        assertTrue(magnet.endsWith("&index=2"))
    }

    @Test
    fun detectsStreamsThatNeedInfoHashMagnets() {
        assertTrue(StremioMapping.needsInfoHashMagnet(streams[2]))
        assertTrue(StremioMapping.needsInfoHashMagnet(StremioStream(infoHash = "DEADBEEF")))
        assertTrue(!StremioMapping.needsInfoHashMagnet(StremioStream()))
        assertTrue(!StremioMapping.needsInfoHashMagnet(StremioStream(infoHash = "  ")))
        assertTrue(!StremioMapping.needsInfoHashMagnet(streams[0])) // direct URL
        assertTrue(!StremioMapping.needsInfoHashMagnet(streams[1])) // ytId
        assertTrue(!StremioMapping.needsInfoHashMagnet(streams[4])) // externalUrl only
        assertTrue(!StremioMapping.needsInfoHashMagnet(StremioStream(url = "magnet:?xt=urn:btih:DEADBEEF")))
        // An unplayable URL still falls back to the hash.
        assertTrue(
            StremioMapping.needsInfoHashMagnet(
                StremioStream(url = "https://bad.example/my file.mkv", infoHash = "DEADBEEF")
            )
        )
    }

    @Test
    fun extractsProxyHeadersAndReferer() {
        val headers = StremioMapping.requestHeadersFor(streams[0])
        assertEquals("TestUA", headers["User-Agent"])
        assertEquals("https://ref.example/", StremioMapping.extractReferer(headers))
        assertEquals("", StremioMapping.extractReferer(emptyMap()))
    }

    @Test
    fun appliesNonMozillaUserAgentWhenAddonOmitsOne() {
        // Some Stremio CDNs return HTTP 428 for Mozilla UAs, so an addon-less stream
        // must not fall back to Cloudstream's global browser UA.
        val headers = StremioMapping.requestHeadersFor(streams[1])
        assertEquals(StremioMapping.DEFAULT_STREAM_USER_AGENT, headers["User-Agent"])
        assertTrue(!headers["User-Agent"]!!.startsWith("Mozilla"))
    }

    @Test
    fun fallsBackToTopLevelBehaviorHintsHeaders() {
        val json = """
            {
              "streams": [
                {
                  "url": "https://cdn.example/a.m3u8",
                  "behaviorHints": {
                    "headers": { "User-Agent": "TopUA", "Referer": "https://top.example/" }
                  }
                }
              ]
            }
        """.trimIndent()
        val stream = StremioMapping.parseStreams(json).single()
        val headers = StremioMapping.requestHeadersFor(stream)
        assertEquals("TopUA", headers["User-Agent"])
        assertEquals("https://top.example/", StremioMapping.extractReferer(headers))
    }

    @Test
    fun topLevelHeadersStillGetDefaultUserAgentWhenMissing() {
        val stream = StremioStream(
            url = "https://cdn.example/a.m3u8",
            behaviorHints = StremioBehaviorHints(headers = mapOf("Referer" to "https://top.example/")),
        )
        val headers = StremioMapping.requestHeadersFor(stream)
        assertEquals(StremioMapping.DEFAULT_STREAM_USER_AGENT, headers["User-Agent"])
        assertEquals("https://top.example/", headers["Referer"])
    }

    @Test
    fun proxyHeadersRequestWinsOverTopLevelHeaders() {
        val stream = StremioStream(
            url = "https://cdn.example/a.m3u8",
            behaviorHints = StremioBehaviorHints(
                proxyHeaders = StremioProxyHeaders(request = mapOf("User-Agent" to "ProxyUA")),
                headers = mapOf("User-Agent" to "TopUA"),
            ),
        )
        val headers = StremioMapping.requestHeadersFor(stream)
        assertEquals("ProxyUA", headers["User-Agent"])
        assertTrue(headers.values.none { it == "TopUA" })
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
    fun guessesHlsBehindProxyOrPlainPaths() {
        // Extension only appears inside the query of a plain proxy (CNCVerse style).
        assertEquals(
            StremioMapping.StreamKind.HLS,
            StremioMapping.guessStreamKind(
                "http://140.238.244.130/proxy/bound?d=https%3A%2F%2Ftv.imgcdn.kim%2Fnewtv%2Fhls%2Fnf%2F70005379.m3u8%3Fin%3D1"
            )
        )
        // Common HLS path hints with no extension at all (PenguPlay style).
        assertEquals(
            StremioMapping.StreamKind.HLS,
            StremioMapping.guessStreamKind("https://pengu.example/hls/cinejoy/playlist/vOxb5wvXd8Y/")
        )
        // A direct video file must not be mistaken for HLS.
        assertEquals(
            StremioMapping.StreamKind.VIDEO,
            StremioMapping.guessStreamKind("https://cdn.example/hub/abc123?X-Amz-Expires=28800")
        )
    }

    @Test
    fun prioritizesExplicitExtensionsOverPathHints() {
        // A DASH manifest under an /hls/ path must stay DASH.
        assertEquals(
            StremioMapping.StreamKind.DASH,
            StremioMapping.guessStreamKind("https://cdn.example/hls/live/manifest.mpd")
        )
        // Direct files under /hls/ or /playlist/ paths must stay VIDEO, not HLS.
        assertEquals(
            StremioMapping.StreamKind.VIDEO,
            StremioMapping.guessStreamKind("https://cdn.example/hls/movie.mp4")
        )
        assertEquals(
            StremioMapping.StreamKind.VIDEO,
            StremioMapping.guessStreamKind("https://cdn.example/playlist/movie.mp4")
        )
        // A title containing "playlist" (no leading slash) is not a path hint.
        assertEquals(
            StremioMapping.StreamKind.VIDEO,
            StremioMapping.guessStreamKind("https://cdn.example/shows/the-playlist-s01e01.mp4")
        )
        // ".m3u8" in the query of a direct file must not win over the file extension.
        assertEquals(
            StremioMapping.StreamKind.VIDEO,
            StremioMapping.guessStreamKind("https://cdn.example/vod/movie.mp4?token=xyz.m3u8")
        )
        // An extension-less HLS path hint still resolves to HLS.
        assertEquals(
            StremioMapping.StreamKind.HLS,
            StremioMapping.guessStreamKind("https://cdn.example/hls/live/")
        )
    }

    @Test
    fun radioStreamsUseProgressiveVideoKind() {
        // Shoutcast/Icecast URLs usually have no extension; ExoPlayer sniffs MP3/ADTS from the
        // progressive stream, so they must not be mistaken for HLS/DASH.
        assertEquals(
            StremioMapping.StreamKind.VIDEO,
            StremioMapping.guessStreamKind("http://stream.antenne.de:80/antenne")
        )
        assertEquals(
            StremioMapping.StreamKind.VIDEO,
            StremioMapping.guessStreamKind("https://radio.example/live/stream.mp3")
        )
        assertEquals(
            StremioMapping.StreamKind.HLS,
            StremioMapping.guessStreamKind("https://radio.example/live/stream.m3u8")
        )
    }

    @Test
    fun parsesQualityFromAddonText() {
        assertEquals(1080, StremioMapping.parseQuality("Movie.2024.1080p.WEB-DL"))
        assertEquals(1080, StremioMapping.parseQuality("FullHD"))
        assertEquals(1080, StremioMapping.parseQuality("Full HD"))
        assertEquals(1080, StremioMapping.parseQuality("Full-HD"))
        assertEquals(2160, StremioMapping.parseQuality("4K HDR"))
        assertEquals(2160, StremioMapping.parseQuality("2160p"))
        assertEquals(2160, StremioMapping.parseQuality("Ultra HD"))
        assertEquals(2160, StremioMapping.parseQuality("Movie.Ultra-HD.x265"))
        assertEquals(1440, StremioMapping.parseQuality("1440p"))
        assertEquals(720, StremioMapping.parseQuality("720p x265"))
        assertEquals(720, StremioMapping.parseQuality("HD"))
        assertEquals(720, StremioMapping.parseQuality("Movie.HD.x264"))
        assertEquals(720, StremioMapping.parseQuality("[HD]"))
        assertEquals(720, StremioMapping.parseQuality("1280x720"))
        assertEquals(480, StremioMapping.parseQuality("480p"))
        assertEquals(480, StremioMapping.parseQuality("Movie.480p.TrueHD"))
        assertEquals(480, StremioMapping.parseQuality("Movie.480p.TrueHD.7.1.Atmos"))
        assertEquals(480, StremioMapping.parseQuality("Movie.480p.DTS-HD.MA"))
        assertEquals(480, StremioMapping.parseQuality("Movie.480p.DTS-HD"))
        assertEquals(480, StremioMapping.parseQuality("Movie.480p.HD-Audio"))
        assertEquals(360, StremioMapping.parseQuality("360p"))
        assertEquals(240, StremioMapping.parseQuality("240p"))
        assertEquals(144, StremioMapping.parseQuality("144p"))
        assertEquals(0, StremioMapping.parseQuality("HDTS"))
        assertEquals(0, StremioMapping.parseQuality("HD-TS"))
        assertEquals(0, StremioMapping.parseQuality("HDCAM"))
        assertEquals(0, StremioMapping.parseQuality("HD-CAM"))
        assertEquals(0, StremioMapping.parseQuality("HD-TC"))
        assertEquals(0, StremioMapping.parseQuality("Movie.HD CAM.x264"))
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
    fun extractsLanguagesFromAddonText() {
        // HdHub: language only in description (release filename + a bare language line).
        assertEquals(
            listOf("Hindi", "English"),
            StremioMapping.streamLanguages(
                StremioStream(
                    name = "HdHub 480p",
                    description = "[FSL] [560MB] The.Shawshank.Redemption.1994.480p.BluRay.Hindi.English.DD2.0.x264.ESubs.mkv\nHindi\nFSL | HdHub",
                )
            )
        )
        // PenguPlay: audio line in description and a dotted filename.
        assertEquals(
            listOf("English", "Hindi"),
            StremioMapping.streamLanguages(
                StremioStream(
                    name = "🐧 PenguPlay 🐧 720p • HDHub4u · FSL",
                    description = "🍿 The Shawshank Redemption\n🎧 Audio: English, Hindi",
                    behaviorHints = StremioBehaviorHints(
                        filename = "The.Shawshank.Redemption.1994.720p.BluRay.Hindi.English.DD5.1.x264.mkv"
                    ),
                )
            )
        )
        // PlavioHD/CNCVerse style: language in title.
        assertEquals(
            listOf("Hindi", "English"),
            StremioMapping.streamLanguages(
                StremioStream(name = "480p · Vegamovies", title = "480p · Hindi, English\nVegamovies · PlavioHD")
            )
        )
        // Meteor: no title, language tokens in the description/filename.
        assertEquals(
            listOf("Russian", "English"),
            StremioMapping.streamLanguages(
                StremioStream(
                    name = "[P2P☁️] Meteor",
                    description = "📄 Shawshank.Redemption.1994.Rus.Eng.mkv\n🔊 eac3",
                )
            )
        )
        // "Dual audio" is reported as Multi.
        assertEquals(
            listOf("Multi", "Hindi"),
            StremioMapping.streamLanguages(
                StremioStream(title = "Movie 1080p Dual Audio (Hindi)")
            )
        )
        // Order of appearance wins and the tag list is capped at three.
        assertEquals(
            listOf("English", "Hindi", "Tamil"),
            StremioMapping.streamLanguages(
                StremioStream(
                    title = "English Hindi Tamil Telugu",
                    description = "ENGLISH again",
                )
            )
        )
    }

    @Test
    fun detectsExpandedLanguageSet() {
        assertEquals(
            listOf("Tamil", "Telugu", "Malayalam"),
            StremioMapping.streamLanguages(StremioStream(title = "1080p Tamil, Telugu, Malayalam"))
        )
        assertEquals(
            listOf("Japanese", "Korean", "English"),
            StremioMapping.streamLanguages(StremioStream(title = "Japanese + Korean + English audio"))
        )
        assertEquals(
            listOf("Spanish", "French", "German"),
            StremioMapping.streamLanguages(
                StremioStream(description = "Movie.2020.1080p.Spa.Fre.Ger.DD5.1.mkv")
            )
        )
        assertEquals(
            listOf("Spanish", "French", "Portuguese"),
            StremioMapping.streamLanguages(StremioStream(title = "Español, Français, Português"))
        )
        assertEquals(
            listOf("Bengali", "Marathi", "Punjabi"),
            StremioMapping.streamLanguages(
                StremioStream(description = "Bengali Marathi Punjabi Gujarati Urdu")
            )
        )
        assertEquals(
            listOf("Russian", "Ukrainian", "English"),
            StremioMapping.streamLanguages(
                StremioStream(
                    behaviorHints = StremioBehaviorHints(
                        filename = "Movie.2024.1080p.Rus.Ukr.Eng.mkv"
                    )
                )
            )
        )
        assertEquals(
            listOf("Arabic", "Turkish", "Hebrew"),
            StremioMapping.streamLanguages(StremioStream(title = "Arabic Türkçe Hebrew"))
        )
        assertEquals(
            listOf("Filipino", "Malay", "Indonesian"),
            StremioMapping.streamLanguages(StremioStream(title = "Tagalog Melayu Bahasa Indonesia"))
        )
    }

    @Test
    fun prefersExplicitAudioOverSubtitleLists() {
        val cncMobile = StremioStream(
            name = "🎬CNC Verse Mobile 🧊 1080p • CNCVerse Bridge",
            title = "📡 Breaking Bad • S02E01\n" +
                "🏷️ Auto\n" +
                "🎞️ 1080p · 720p\n" +
                "🛰️ Source: CNC Verse Mobile • NetflixM\n" +
                "🎧 Audio: German, English, Spanish, French, +5 more\n" +
                "📝 Subtitles: Arabic, Czech, Danish, +28 more",
        )
        assertEquals(
            listOf("German", "English", "Spanish"),
            StremioMapping.streamLanguages(cncMobile)
        )
        assertEquals(
            listOf("Hindi", "English"),
            StremioMapping.streamLanguages(
                StremioStream(
                    description = "📡 Breaking Bad - Seven Thirty-Seven (2008) • S02E01\n" +
                        "🎞️ 720p • WEB-DL • x264 • MKV • ~1.1 Mbps\n" +
                        "🛰️ Source: MoviesDrives · PixelDrain\n" +
                        "💾 400 MB\n" +
                        "🎧 Audio: Hindi, English",
                )
            )
        )
        assertNull(
            StremioMapping.languageTag(
                StremioStream(
                    title = "📡 Breaking Bad • S02E01\n" +
                        "📝 Subtitles: Arabic, Czech, Danish, +28 more"
                )
            )
        )
        assertNull(
            StremioMapping.languageTag(
                StremioStream(title = "🛰️ Source: NetNaija • Arabic Hardsub\n🎞️ 720p • Direct")
            )
        )
    }

    @Test
    fun detectsDubAndLocaleLanguageLabels() {
        assertEquals(
            listOf("Spanish"),
            StremioMapping.streamLanguages(StremioStream(title = "🛰️ Source: NetNaija • ES-LA Dub"))
        )
        assertEquals(
            listOf("Spanish"),
            StremioMapping.streamLanguages(StremioStream(title = "NetNaija • esla dub"))
        )
        assertEquals(
            listOf("Portuguese"),
            StremioMapping.streamLanguages(StremioStream(title = "NetNaija • PT-BR Dub"))
        )
        assertEquals(
            listOf("Portuguese"),
            StremioMapping.streamLanguages(StremioStream(title = "NetNaija • ptbr"))
        )
        assertEquals(
            listOf("Portuguese"),
            StremioMapping.streamLanguages(StremioStream(title = "Filme Dublado"))
        )
        assertEquals(
            listOf("Spanish"),
            StremioMapping.streamLanguages(StremioStream(title = "Audio: Castellano"))
        )
        assertEquals(
            listOf("English", "Hindi"),
            StremioMapping.streamLanguages(StremioStream(title = "English audio, Hindi"))
        )
        assertEquals(
            listOf("Multi", "Hindi"),
            StremioMapping.streamLanguages(StremioStream(title = "Movie Multi Audio (Hindi)"))
        )
        assertEquals(
            listOf("Hindi", "English"),
            StremioMapping.streamLanguages(
                StremioStream(
                    title = "🏷️ (Movies4u.Foo).Breaking.Bad.S02E01.720p.BluRay.Hindi.ORG.2.0-English.ESub.x264.mkv[399.72 MB]"
                )
            )
        )
        assertNull(StremioMapping.languageTag(StremioStream(title = "Filme Legendado")))
        assertNull(StremioMapping.languageTag(StremioStream(title = "Film VOSTFR")))
        assertNull(StremioMapping.languageTag(StremioStream(title = "🙊 No included subtitles")))
    }

    @Test
    fun dedupesLanguageCodesAndNames() {
        assertEquals(
            listOf("English", "Hindi"),
            StremioMapping.streamLanguages(
                StremioStream(title = "English ENG Hindi HIN")
            )
        )
    }

    @Test
    fun ignoresAmbiguousLanguageLikeWords() {
        assertNull(StremioMapping.languageTag(StremioStream(title = "Ben-Hur")))
        assertNull(StremioMapping.languageTag(StremioStream(title = "Mar 2020")))
        assertNull(StremioMapping.languageTag(StremioStream(title = "May 2024")))
        assertNull(StremioMapping.languageTag(StremioStream(description = "Price per episode")))
        assertNull(StremioMapping.languageTag(StremioStream(title = "Dan in Real Life")))
    }

    @Test
    fun buildsLanguageTagOrNull() {
        assertEquals(
            "[Hindi, English]",
            StremioMapping.languageTag(StremioStream(title = "1080p Hindi, English"))
        )
        assertEquals(
            "[Tamil, English]",
            StremioMapping.languageTag(StremioStream(title = "1080p Tamil, English"))
        )
        assertNull(StremioMapping.languageTag(StremioStream(name = "HdHub 480p")))
        assertNull(StremioMapping.languageTag(StremioStream()))
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
    fun parsesTenPointRatings() {
        assertEquals(9.3, StremioMapping.ratingFrom10("9.3"))
        assertEquals(7.6, StremioMapping.ratingFrom10(" 7.6 "))
        assertEquals(10.0, StremioMapping.ratingFrom10("10"))
        assertNull(StremioMapping.ratingFrom10("0"))
        assertNull(StremioMapping.ratingFrom10("10.1"))
        assertNull(StremioMapping.ratingFrom10("N/A"))
        assertNull(StremioMapping.ratingFrom10(""))
        assertNull(StremioMapping.ratingFrom10(null))
    }

    @Test
    fun parsesRuntimeFormats() {
        assertEquals(142, StremioMapping.runtimeMinutes("2h22min"))
        assertEquals(142, StremioMapping.runtimeMinutes("142m"))
        assertEquals(57, StremioMapping.runtimeMinutes("57min"))
        assertEquals(62, StremioMapping.runtimeMinutes("1h2min"))
        assertEquals(45, StremioMapping.runtimeMinutes("45 min"))
        assertEquals(120, StremioMapping.runtimeMinutes("120"))
        assertEquals(60, StremioMapping.runtimeMinutes("1h"))
        assertNull(StremioMapping.runtimeMinutes("0"))
        assertNull(StremioMapping.runtimeMinutes("N/A"))
        assertNull(StremioMapping.runtimeMinutes(""))
        assertNull(StremioMapping.runtimeMinutes(null))
    }

    @Test
    fun buildsTrailerUrls() {
        assertEquals(
            "https://www.youtube.com/watch?v=PLl99DlL6b4",
            StremioMapping.trailerUrl("PLl99DlL6b4")
        )
        assertEquals("https://www.youtube.com/watch?v=abc", StremioMapping.trailerUrl(" abc "))
        assertEquals(
            "https://example.com/trailer.mp4",
            StremioMapping.trailerUrl("https://example.com/trailer.mp4")
        )
        assertNull(StremioMapping.trailerUrl(""))
        assertNull(StremioMapping.trailerUrl(null))
    }

    @Test
    fun mapsShowStatusStrings() {
        assertEquals(StremioMapping.ShowState.Completed, StremioMapping.showStateFrom("Ended"))
        assertEquals(StremioMapping.ShowState.Completed, StremioMapping.showStateFrom("Released"))
        assertEquals(StremioMapping.ShowState.Completed, StremioMapping.showStateFrom("Canceled"))
        assertEquals(StremioMapping.ShowState.Ongoing, StremioMapping.showStateFrom("Continuing"))
        assertEquals(StremioMapping.ShowState.Ongoing, StremioMapping.showStateFrom("Returning Series"))
        assertEquals(StremioMapping.ShowState.Ongoing, StremioMapping.showStateFrom("In Production"))
        assertEquals(StremioMapping.ShowState.Ongoing, StremioMapping.showStateFrom("Planned"))
        assertNull(StremioMapping.showStateFrom("Unknown"))
        assertNull(StremioMapping.showStateFrom(null))
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
    fun parsesAiometadataMetadata() {
        val meta = StremioMapping.parseMeta(
            """
            {
              "meta": {
                "id": "tt0903747",
                "type": "series",
                "name": "Breaking Bad",
                "logo": "https://img/logo.png",
                "runtime": "48min",
                "status": "Ended",
                "app_extras": {
                  "certification": "TV-MA",
                  "cast": [
                    { "name": "Bryan Cranston", "character": "Walter White",
                      "photo": "https://img/bryan.jpg" }
                  ]
                },
                "videos": [
                  { "id": "tt0903747:1:1", "title": "Pilot", "season": 1, "episode": 1,
                    "runtime": "59min", "available": true }
                ]
              }
            }
            """.trimIndent()
        )
        assertEquals("https://img/logo.png", meta?.logo)
        assertEquals("48min", meta?.runtime)
        assertEquals("Ended", meta?.status)
        assertEquals("TV-MA", meta?.appExtras?.certification)
        assertEquals("Bryan Cranston", meta?.appExtras?.cast?.first()?.name)
        assertEquals("Walter White", meta?.appExtras?.cast?.first()?.character)
        assertEquals("https://img/bryan.jpg", meta?.appExtras?.cast?.first()?.photo)
        assertEquals("59min", meta?.videos?.first()?.runtime)
        assertEquals(true, meta?.videos?.first()?.available)
    }

    @Test
    fun parsesNuvioMetadata() {
        val meta = StremioMapping.parseMeta(
            """
            {
              "meta": {
                "id": "tt0903747",
                "type": "series",
                "name": "Breaking Bad",
                "status": "Ended",
                "ageRating": "TV-MA",
                "cast": ["Bryan Cranston", "Aaron Paul"],
                "app_extras": {
                  "certification": "TV-MA",
                  "ratings": {
                    "imdb": { "rating": "9.5", "votes": 2695979 },
                    "tmdb": { "rating": "9.0", "votes": 18945 }
                  }
                },
                "trailers": [
                  { "source": "VFkjBy2b50Q", "type": "Trailer" }
                ],
                "videos": [
                  { "id": "tt0903747:1:1", "title": "Pilot", "season": 1, "episode": 1,
                    "rating": "8.5", "runtime": "59min", "available": true }
                ]
              }
            }
            """.trimIndent()
        )
        assertEquals("Ended", meta?.status)
        assertEquals("TV-MA", meta?.ageRating)
        assertEquals(listOf("Bryan Cranston", "Aaron Paul"), meta?.cast)
        assertEquals("9.5", meta?.appExtras?.ratings?.imdb?.rating)
        assertEquals(2695979L, meta?.appExtras?.ratings?.imdb?.votes)
        assertEquals("9.0", meta?.appExtras?.ratings?.tmdb?.rating)
        assertEquals("VFkjBy2b50Q", meta?.trailers?.first()?.source)
        assertEquals("8.5", meta?.videos?.first()?.rating)
        assertEquals("59min", meta?.videos?.first()?.runtime)
        assertEquals(true, meta?.videos?.first()?.available)
    }

    @Test
    fun episodeNameFallsBackToTitleField() {
        // Nuvio's TMDB catalog labels episodes with `title` instead of `name`.
        val meta = StremioMapping.parseMeta(
            """
            {
              "meta": {
                "id": "tt1234567",
                "type": "series",
                "name": "Breaking Bad",
                "videos": [
                  { "id": "tt0903747:1:1", "title": "Pilot", "season": 1, "episode": 1 }
                ]
              }
            }
            """.trimIndent()
        )
        val video = meta?.videos?.first()
        assertEquals("Pilot", video?.title)
        assertNull(video?.name)
        assertEquals("Pilot", video?.let { StremioMapping.episodeName(it) })
        assertEquals("Name wins", StremioMapping.episodeName(StremioVideo(name = "Name wins", title = "Title")))
        assertNull(StremioMapping.episodeName(StremioVideo(name = "  ", title = "")))
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

    @Test
    fun parsesEmbeddedSubtitlesOnStreams() {
        val streams = StremioMapping.parseStreams(
            """
            {
              "streams": [
                {
                  "url": "https://cdn.example/a.mkv",
                  "subtitles": [
                    { "url": "https://subs.example/en.srt", "lang": "English" },
                    { "url": "https://subs.example/fr.srt", "lang_code": "fr" }
                  ]
                },
                { "url": "https://cdn.example/b.mkv" }
              ]
            }
            """.trimIndent()
        )
        assertEquals(2, streams[0].subtitles?.size)
        assertEquals("https://subs.example/fr.srt", streams[0].subtitles?.get(1)?.url)
        assertEquals("fr", streams[0].subtitles?.get(1)?.langCode)
        assertTrue(streams[1].subtitles.isNullOrEmpty())
    }

    @Test
    fun resolvesSubtitleLanguageWithLangCodeFallback() {
        assertEquals("English", StremioMapping.subtitleLanguage(StremioSubtitle(lang = "English")))
        assertEquals("fr", StremioMapping.subtitleLanguage(StremioSubtitle(langCode = "fr")))
        assertEquals("en", StremioMapping.subtitleLanguage(StremioSubtitle(lang = "en", langCode = "fr")))
        assertEquals("fr", StremioMapping.subtitleLanguage(StremioSubtitle(lang = "  ", langCode = "fr")))
        assertEquals("Unknown", StremioMapping.subtitleLanguage(StremioSubtitle()))
    }

    @Test
    fun flattensAndDedupesEmbeddedSubtitles() {
        val streams = listOf(
            StremioStream(
                subtitles = listOf(
                    StremioSubtitle(url = "https://subs.example/en.srt", lang = "English"),
                    StremioSubtitle(url = "https://subs.example/en.srt", lang = "English duplicate"),
                    StremioSubtitle(lang = "no url"),
                )
            ),
            StremioStream(
                subtitles = listOf(
                    StremioSubtitle(url = "https://subs.example/en.srt", lang = "duplicate across streams"),
                    StremioSubtitle(url = "https://subs.example/fr.srt", langCode = "fr"),
                )
            ),
        )
        val embedded = StremioMapping.embeddedSubtitles(streams)
        assertEquals(2, embedded.size)
        assertEquals("https://subs.example/en.srt", embedded[0].url)
        assertEquals("https://subs.example/fr.srt", embedded[1].url)
    }
}
