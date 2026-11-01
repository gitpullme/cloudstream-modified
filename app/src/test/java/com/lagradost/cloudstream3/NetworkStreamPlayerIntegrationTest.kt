package com.lagradost.cloudstream3

import android.net.Uri
import com.lagradost.cloudstream3.actions.ExternalPlaybackRequest
import com.lagradost.cloudstream3.actions.ExternalPlaybackRequestBuilder
import com.lagradost.cloudstream3.actions.ExternalPlaybackRoute
import com.lagradost.cloudstream3.actions.ExternalPlaybackSupport
import com.lagradost.cloudstream3.actions.ExternalSubtitle
import com.lagradost.cloudstream3.actions.LocalStreamProxyServer
import com.lagradost.cloudstream3.actions.NetworkStreamPlayerLauncher
import com.lagradost.cloudstream3.actions.OpenInAppAction
import com.lagradost.cloudstream3.actions.VideoClickActionHolder
import com.lagradost.cloudstream3.actions.temp.WebVideoCastPackage
import com.lagradost.cloudstream3.ui.player.SubtitleData
import com.lagradost.cloudstream3.ui.player.SubtitleOrigin
import com.lagradost.cloudstream3.ui.result.LinkLoadingResult
import com.lagradost.cloudstream3.ui.result.ResultEpisode
import com.lagradost.cloudstream3.ui.result.VideoWatchState
import com.lagradost.cloudstream3.utils.CLEARKEY_DRM_UUID
import com.lagradost.cloudstream3.utils.DrmExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newDrmExtractorLink
import com.lagradost.cloudstream3.actions.HlsVariant
import com.lagradost.cloudstream3.utils.PLAYREADY_DRM_UUID
import com.lagradost.cloudstream3.utils.WIDEVINE_DRM_UUID
import com.sun.net.httpserver.HttpServer
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.GZIPOutputStream
import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class NetworkStreamPlayerIntegrationTest {

    private fun createSampleEpisode(name: String = "Test Movie"): ResultEpisode {
        return ResultEpisode(
            headerName = "Test Show",
            name = name,
            poster = "https://example.com/poster.jpg",
            episode = 1,
            seasonIndex = 1,
            season = 1,
            data = "episode_data",
            apiName = "TestApi",
            id = 12345,
            index = 0,
            position = 0L,
            duration = 100000L,
            score = null,
            description = "Test description",
            isFiller = false,
            tvType = TvType.Movie,
            parentId = 123,
            videoWatchState = VideoWatchState.None
        )
    }

    private fun buildTestRequest(
        url: String,
        headers: Map<String, String> = emptyMap(),
        referer: String = "",
        quality: Int = 1080,
        type: ExtractorLinkType = ExtractorLinkType.M3U8
    ): ExternalPlaybackRequest {
        val link = ExtractorLink(
            source = "Test",
            name = "TestLink",
            url = url,
            referer = referer,
            quality = quality,
            headers = headers,
            type = type
        )
        return ExternalPlaybackRequestBuilder.build(
            null,
            createSampleEpisode(),
            LinkLoadingResult(listOf(link), emptyList(), hashMapOf()),
            0
        )
    }

    private fun openConnection(
        url: String,
        range: String? = null,
        headers: Map<String, String> = emptyMap()
    ): java.net.HttpURLConnection {
        val connection = URL(url).openConnection() as java.net.HttpURLConnection
        connection.connectTimeout = 5_000
        connection.readTimeout = 10_000
        if (range != null) connection.setRequestProperty("Range", range)
        for ((key, value) in headers) {
            connection.setRequestProperty(key, value)
        }
        return connection
    }

    private fun readAllBytes(connection: java.net.HttpURLConnection): ByteArray {
        return connection.inputStream.use { it.readBytes() }
    }

    private fun gzip(data: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).use { it.write(data) }
        return output.toByteArray()
    }

    @Test
    fun `ExternalPlaybackRequest preserves resolved media URL, headers and metadata`() {
        val episode = createSampleEpisode("Inception")
        val link = ExtractorLink(
            source = "TestProvider",
            name = "1080p",
            url = "https://cdn.example.com/media/stream.m3u8?token=xyz",
            referer = "https://source.example.com/embed",
            quality = 1080,
            headers = mapOf(
                "Cookie" to "session=abc12345; auth=token987",
                "Origin" to "https://source.example.com",
                "Custom-Header" to "CustomValue"
            ),
            type = ExtractorLinkType.M3U8
        )

        val sub = SubtitleData(
            originalName = "English",
            nameSuffix = "",
            url = "https://subtitles.example.com/en.vtt",
            origin = SubtitleOrigin.URL,
            mimeType = "text/vtt",
            headers = mapOf("Referer" to "https://subtitles.example.com"),
            languageCode = "en"
        )

        val result = LinkLoadingResult(
            links = listOf(link),
            subs = listOf(sub),
            syncData = hashMapOf()
        )

        val request = ExternalPlaybackRequestBuilder.build(null, episode, result, 0)

        assertEquals("https://cdn.example.com/media/stream.m3u8?token=xyz", request.mediaUrl)
        assertEquals("Inception", request.displayName)
        assertEquals("application/vnd.apple.mpegurl", request.mimeType)
        assertEquals(ExtractorLinkType.M3U8, request.linkType)
        assertEquals("https://source.example.com/embed", request.referer)
        assertEquals("https://source.example.com", request.origin)
        assertEquals("session=abc12345; auth=token987", request.cookie)
        assertTrue(request.requiresProxy())
        assertEquals(1, request.subtitles.size)
        assertEquals("English", request.subtitles[0].name)
        assertEquals("https://subtitles.example.com/en.vtt", request.subtitles[0].url)
    }

    @Test
    fun `ExternalPlaybackRequest correctly derives Widevine, PlayReady and ClearKey DRM schemes`() {
        val episode = createSampleEpisode()

        // Widevine DRM
        val widevineLink = kotlinx.coroutines.runBlocking {
            newDrmExtractorLink(
                source = "TestProvider",
                name = "Widevine Stream",
                url = "https://cdn.example.com/drm/manifest.mpd",
                uuid = WIDEVINE_DRM_UUID,
                type = ExtractorLinkType.DASH
            ) {
                licenseUrl = "https://license.example.com/widevine"
                keyRequestParameters = hashMapOf("X-Custom-Auth" to "AuthKey")
            }
        }

        val wvResult = LinkLoadingResult(listOf(widevineLink), emptyList(), hashMapOf())
        val wvRequest = ExternalPlaybackRequestBuilder.build(null, episode, wvResult, 0)

        assertTrue(wvRequest.isDrm)
        assertEquals("widevine", wvRequest.drmScheme)
        assertEquals("https://license.example.com/widevine", wvRequest.drmLicenseUrl)
        assertEquals("AuthKey", wvRequest.drmHeaders["X-Custom-Auth"])

        // PlayReady DRM
        val playreadyLink = kotlinx.coroutines.runBlocking {
            newDrmExtractorLink(
                source = "TestProvider",
                name = "PlayReady Stream",
                url = "https://cdn.example.com/drm/manifest.mpd",
                uuid = PLAYREADY_DRM_UUID,
                type = ExtractorLinkType.DASH
            ) {
                licenseUrl = "https://license.example.com/playready"
            }
        }

        val prResult = LinkLoadingResult(listOf(playreadyLink), emptyList(), hashMapOf())
        val prRequest = ExternalPlaybackRequestBuilder.build(null, episode, prResult, 0)

        assertTrue(prRequest.isDrm)
        assertEquals("playready", prRequest.drmScheme)

        // ClearKey DRM
        val clearkeyLink = kotlinx.coroutines.runBlocking {
            newDrmExtractorLink(
                source = "TestProvider",
                name = "ClearKey Stream",
                url = "https://cdn.example.com/drm/manifest.mpd",
                uuid = CLEARKEY_DRM_UUID,
                type = ExtractorLinkType.DASH
            ) {
                licenseUrl = "https://license.example.com/clearkey"
            }
        }

        val ckResult = LinkLoadingResult(listOf(clearkeyLink), emptyList(), hashMapOf())
        val ckRequest = ExternalPlaybackRequestBuilder.build(null, episode, ckResult, 0)

        assertTrue(ckRequest.isDrm)
        assertEquals("clearkey", ckRequest.drmScheme)
    }

    @Test
    fun `requiresProxy returns false for clean public MP4 and true for streams with headers`() {
        val episode = createSampleEpisode()

        // Clean progressive link without cookies or referer
        val cleanLink = ExtractorLink(
            source = "PublicCDN",
            name = "720p",
            url = "https://public.example.com/videos/sample.mp4",
            referer = "",
            quality = 720,
            headers = emptyMap(),
            type = ExtractorLinkType.VIDEO
        )

        val cleanResult = LinkLoadingResult(listOf(cleanLink), emptyList(), hashMapOf())
        val cleanReq = ExternalPlaybackRequestBuilder.build(null, episode, cleanResult, 0)
        assertFalse(cleanReq.requiresProxy())

        // Progressive link with Cookie
        val cookieLink = ExtractorLink(
            source = "ProtectedCDN",
            name = "720p",
            url = "https://protected.example.com/videos/sample.mp4",
            referer = "",
            quality = 720,
            headers = mapOf("Cookie" to "auth=1"),
            type = ExtractorLinkType.VIDEO
        )
        val cookieResult = LinkLoadingResult(listOf(cookieLink), emptyList(), hashMapOf())
        val cookieReq = ExternalPlaybackRequestBuilder.build(null, episode, cookieResult, 0)
        assertTrue(cookieReq.requiresProxy())

        // HLS link with Referer
        val hlsLink = ExtractorLink(
            source = "HlsCDN",
            name = "Auto",
            url = "https://hls.example.com/master.m3u8",
            referer = "https://stream.example.com",
            quality = 1080,
            headers = emptyMap(),
            type = ExtractorLinkType.M3U8
        )
        val hlsResult = LinkLoadingResult(listOf(hlsLink), emptyList(), hashMapOf())
        val hlsReq = ExternalPlaybackRequestBuilder.build(null, episode, hlsResult, 0)
        assertTrue(hlsReq.requiresProxy())
    }

    @Test
    fun `LocalStreamProxyServer resolveUrl handles absolute, relative, parent-dir and query parameters`() {
        val base = "https://cdn.example.com/hls/movies/master.m3u8"

        // Absolute URL
        assertEquals(
            "https://other.cdn.com/stream.m3u8",
            LocalStreamProxyServer.resolveUrl(base, "https://other.cdn.com/stream.m3u8")
        )

        // Simple relative segment
        assertEquals(
            "https://cdn.example.com/hls/movies/segment_001.ts",
            LocalStreamProxyServer.resolveUrl(base, "segment_001.ts")
        )

        // Relative path with query params
        assertEquals(
            "https://cdn.example.com/hls/movies/segment_002.ts?key=val&token=123",
            LocalStreamProxyServer.resolveUrl(base, "segment_002.ts?key=val&token=123")
        )

        // Subdirectory relative
        assertEquals(
            "https://cdn.example.com/hls/movies/1080p/index.m3u8",
            LocalStreamProxyServer.resolveUrl(base, "1080p/index.m3u8")
        )

        // Root relative
        assertEquals(
            "https://cdn.example.com/root_segment.ts",
            LocalStreamProxyServer.resolveUrl(base, "/root_segment.ts")
        )

        // Parent directory
        assertEquals(
            "https://cdn.example.com/hls/other/index.m3u8",
            LocalStreamProxyServer.resolveUrl(base, "../other/index.m3u8")
        )
    }

    @Test
    fun `LocalStreamProxyServer isSameHostOrSubdomain detects safe hosts`() {
        assertTrue(LocalStreamProxyServer.isSameHostOrSubdomain(
            "https://cdn.example.com/video.mp4",
            "https://cdn.example.com/chunk.ts"
        ))

        assertTrue(LocalStreamProxyServer.isSameHostOrSubdomain(
            "https://media.example.com/master.m3u8",
            "https://edge1.example.com/seg1.ts"
        ))

        assertFalse(LocalStreamProxyServer.isSameHostOrSubdomain(
            "https://media.example.com/master.m3u8",
            "https://malicious-thirdparty.com/fake.ts"
        ))
    }

    @Test
    fun `LocalStreamProxyServer rewrites master playlist variants and media renditions`() {
        val masterPlaylist = """
            #EXTM3U
            #EXT-X-VERSION:4
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio-1",NAME="English",DEFAULT=YES,URI="audio/eng.m3u8"
            #EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="subs",NAME="English",DEFAULT=NO,URI="subs/en.m3u8"
            #EXT-X-STREAM-INF:BANDWIDTH=5000000,RESOLUTION=1920x1080,AUDIO="audio-1",SUBTITLES="subs"
            1080p/index.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=2500000,RESOLUTION=1280x720,AUDIO="audio-1",SUBTITLES="subs"
            720p/index.m3u8
        """.trimIndent()

        val episode = createSampleEpisode()
        val link = ExtractorLink(
            source = "Test",
            name = "Master",
            url = "https://cdn.example.com/hls/master.m3u8",
            referer = "https://test.com",
            quality = 1080,
            headers = mapOf("Cookie" to "a=1"),
            type = ExtractorLinkType.M3U8
        )
        val request = ExternalPlaybackRequestBuilder.build(
            null,
            episode,
            LinkLoadingResult(listOf(link), emptyList(), hashMapOf()),
            0
        )

        val session = LocalStreamProxyServer.startSession(request)
        val rewritten = LocalStreamProxyServer.rewriteHlsPlaylist(
            masterPlaylist,
            "https://cdn.example.com/hls/master.m3u8",
            session,
            "http://127.0.0.1:8888"
        )

        // Verify that tags are preserved
        assertTrue(rewritten.contains("#EXTM3U"))
        assertTrue(rewritten.contains("#EXT-X-VERSION:4"))
        assertTrue(rewritten.contains("#EXT-X-STREAM-INF:BANDWIDTH=5000000"))

        // Verify that child playlists are rewritten to proxy URLs
        assertTrue(rewritten.contains("http://127.0.0.1:8888/session/${session.sessionId}/res/"))
        assertFalse(rewritten.contains("1080p/index.m3u8"))
        assertFalse(rewritten.contains("audio/eng.m3u8"))

        // Verify resource registration
        assertEquals(4, session.resourceMap.size)
        assertTrue(session.resourceMap.values.contains("https://cdn.example.com/hls/1080p/index.m3u8"))
        assertTrue(session.resourceMap.values.contains("https://cdn.example.com/hls/audio/eng.m3u8"))
        assertTrue(session.resourceMap.values.contains("https://cdn.example.com/hls/subs/en.m3u8"))
    }

    @Test
    fun `LocalStreamProxyServer rewrites media playlist segments, EXT-X-KEY and EXT-X-MAP`() {
        val mediaPlaylist = """
            #EXTM3U
            #EXT-X-VERSION:6
            #EXT-X-TARGETDURATION:6
            #EXT-X-KEY:METHOD=AES-128,URI="enc.key",IV=0x1234567890ABCDEF1234567890ABCDEF
            #EXT-X-MAP:URI="init.mp4"
            #EXTINF:6.000,
            segment001.ts
            #EXTINF:6.000,
            segment002.ts
            #EXT-X-ENDLIST
        """.trimIndent()

        val episode = createSampleEpisode()
        val link = ExtractorLink(
            source = "Test",
            name = "Media",
            url = "https://cdn.example.com/hls/1080p/index.m3u8",
            referer = "",
            quality = 1080,
            headers = mapOf("Cookie" to "b=2"),
            type = ExtractorLinkType.M3U8
        )
        val request = ExternalPlaybackRequestBuilder.build(
            null,
            episode,
            LinkLoadingResult(listOf(link), emptyList(), hashMapOf()),
            0
        )

        val session = LocalStreamProxyServer.startSession(request)
        val rewritten = LocalStreamProxyServer.rewriteHlsPlaylist(
            mediaPlaylist,
            "https://cdn.example.com/hls/1080p/index.m3u8",
            session,
            "http://127.0.0.1:8888"
        )

        assertTrue(rewritten.contains("#EXT-X-ENDLIST"))
        assertTrue(rewritten.contains("IV=0x1234567890ABCDEF1234567890ABCDEF"))

        // Key, Map and segments must all be rewritten
        assertFalse(rewritten.contains("\"enc.key\""))
        assertFalse(rewritten.contains("\"init.mp4\""))
        assertFalse(rewritten.contains("segment001.ts"))

        assertTrue(session.resourceMap.values.contains("https://cdn.example.com/hls/1080p/enc.key"))
        assertTrue(session.resourceMap.values.contains("https://cdn.example.com/hls/1080p/init.mp4"))
        assertTrue(session.resourceMap.values.contains("https://cdn.example.com/hls/1080p/segment001.ts"))
        assertTrue(session.resourceMap.values.contains("https://cdn.example.com/hls/1080p/segment002.ts"))
    }

    @Test
    fun `LocalStreamProxyServer manages session lifecycle and prevents unauthorized access`() {
        val episode = createSampleEpisode()
        val link = ExtractorLink(
            source = "Test",
            name = "SessionTest",
            url = "https://cdn.example.com/hls/test.m3u8",
            referer = "",
            quality = 1080,
            headers = mapOf("Cookie" to "token=xyz"),
            type = ExtractorLinkType.M3U8
        )
        val request = ExternalPlaybackRequestBuilder.build(
            null,
            episode,
            LinkLoadingResult(listOf(link), emptyList(), hashMapOf()),
            0
        )

        val session = LocalStreamProxyServer.startSession(request)
        val sessionId = session.sessionId

        assertNotNull(LocalStreamProxyServer.getSession(sessionId))

        // Non-existent session
        org.junit.Assert.assertNull(LocalStreamProxyServer.getSession("fake_session_token"))

        // Stop session
        LocalStreamProxyServer.stopSession(sessionId)
        org.junit.Assert.assertNull(LocalStreamProxyServer.getSession(sessionId))
    }

    @Test
    fun `ExternalPlaybackRequestBuilder handles headers case-insensitively`() {
        val episode = createSampleEpisode()
        val link = ExtractorLink(
            source = "Test",
            name = "HeaderTest",
            url = "https://cdn.example.com/stream.mp4",
            referer = "https://default.referer.com",
            quality = 1080,
            headers = mapOf(
                "cookie" to "lowercasename=123",
                "REFERER" to "https://override.referer.com",
                "origin" to "https://lowercase.origin.com",
                "USER-AGENT" to "CustomUA/2.0"
            ),
            type = ExtractorLinkType.VIDEO
        )

        val request = ExternalPlaybackRequestBuilder.build(
            null,
            episode,
            LinkLoadingResult(listOf(link), emptyList(), hashMapOf()),
            0
        )

        assertEquals("lowercasename=123", request.cookie)
        assertEquals("https://override.referer.com", request.referer)
        assertEquals("https://lowercase.origin.com", request.origin)
        assertEquals("CustomUA/2.0", request.userAgent)
        assertTrue(request.requiresProxy())
    }

    @Test
    fun `resolveEffectiveMimeType falls back gracefully to video wildcard`() {
        // Without package manager (e.g. Unit test context), returns requested MIME or video/*
        val m3u8Uri = Uri.parse("https://cdn.example.com/stream.m3u8")
        val mimeM3U8 = NetworkStreamPlayerLauncher.resolveEffectiveMimeType(
            null,
            m3u8Uri,
            "application/vnd.apple.mpegurl"
        )
        assertEquals("application/vnd.apple.mpegurl", mimeM3U8)

        val mp4Uri = Uri.parse("https://cdn.example.com/video.mp4")
        val mimeMp4 = NetworkStreamPlayerLauncher.resolveEffectiveMimeType(
            null,
            mp4Uri,
            "video/mp4"
        )
        assertEquals("video/mp4", mimeMp4)

        val blankMime = NetworkStreamPlayerLauncher.resolveEffectiveMimeType(
            null,
            mp4Uri,
            ""
        )
        assertEquals("video/*", blankMime)
    }

    @Test
    fun `direct stream preserves exact media URL and requires no proxy or headers`() {
        val episode = createSampleEpisode()
        val directUrl = "https://public.example.com/movies/sample.mp4?key=token123"
        val link = ExtractorLink(
            source = "PublicMirror",
            name = "1080p",
            url = directUrl,
            referer = "",
            quality = 1080,
            headers = emptyMap(),
            type = ExtractorLinkType.VIDEO
        )

        val request = ExternalPlaybackRequestBuilder.build(
            null,
            episode,
            LinkLoadingResult(listOf(link), emptyList(), hashMapOf()),
            0
        )

        assertFalse(request.requiresProxy())
        assertEquals(directUrl, request.mediaUrl)
        org.junit.Assert.assertNull(request.cookie)
        org.junit.Assert.assertNull(request.referer)
        org.junit.Assert.assertNull(request.origin)
        assertTrue(request.headers.isEmpty())
    }

    @Test
    fun `deterministic resource mapping remains stable across playlist refreshes`() {
        val episode = createSampleEpisode()
        val link = ExtractorLink(
            source = "Test",
            name = "LiveStream",
            url = "https://cdn.example.com/live/index.m3u8",
            referer = "",
            quality = 1080,
            headers = mapOf("Cookie" to "session=live123"),
            type = ExtractorLinkType.M3U8
        )
        val request = ExternalPlaybackRequestBuilder.build(
            null,
            episode,
            LinkLoadingResult(listOf(link), emptyList(), hashMapOf()),
            0
        )

        val session = LocalStreamProxyServer.startSession(request)

        val livePlaylistV1 = """
            #EXTM3U
            #EXT-X-VERSION:3
            #EXT-X-TARGETDURATION:8
            #EXT-X-MEDIA-SEQUENCE:100
            #EXTINF:8.000,
            seg100.ts
            #EXTINF:8.000,
            seg101.ts
            #EXTINF:8.000,
            seg102.ts
        """.trimIndent()

        val rewrittenV1 = LocalStreamProxyServer.rewriteHlsPlaylist(
            livePlaylistV1,
            "https://cdn.example.com/live/index.m3u8",
            session,
            "http://127.0.0.1:8888"
        )

        // Verify segments got registered
        assertEquals(3, session.resourceMap.size)
        val id100 = session.registerResource("https://cdn.example.com/live/seg100.ts")
        val id101 = session.registerResource("https://cdn.example.com/live/seg101.ts")
        val id102 = session.registerResource("https://cdn.example.com/live/seg102.ts")

        assertTrue(rewrittenV1.contains("/res/$id100.ts"))
        assertTrue(rewrittenV1.contains("/res/$id101.ts"))
        assertTrue(rewrittenV1.contains("/res/$id102.ts"))

        // Refresh live playlist after 25s (seg100 rolled off, seg103 added)
        val livePlaylistV2 = """
            #EXTM3U
            #EXT-X-VERSION:3
            #EXT-X-TARGETDURATION:8
            #EXT-X-MEDIA-SEQUENCE:101
            #EXTINF:8.000,
            seg101.ts
            #EXTINF:8.000,
            seg102.ts
            #EXTINF:8.000,
            seg103.ts
        """.trimIndent()

        val rewrittenV2 = LocalStreamProxyServer.rewriteHlsPlaylist(
            livePlaylistV2,
            "https://cdn.example.com/live/index.m3u8",
            session,
            "http://127.0.0.1:8888"
        )

        // seg101 and seg102 MUST have the exact same IDs as before
        assertTrue(rewrittenV2.contains("/res/$id101.ts"))
        assertTrue(rewrittenV2.contains("/res/$id102.ts"))

        // Total registered resources must only be 4 (seg100, seg101, seg102, seg103) - no duplicates
        assertEquals(4, session.resourceMap.size)
    }

    @Test
    fun `resolveResourceId resolves resource with extension, without extension, and fallback`() {
        val episode = createSampleEpisode()
        val link = ExtractorLink(
            source = "Test",
            name = "TestLink",
            url = "https://cdn.example.com/video.m3u8",
            referer = "",
            quality = 1080,
            headers = mapOf("Cookie" to "a=1"),
            type = ExtractorLinkType.M3U8
        )
        val request = ExternalPlaybackRequestBuilder.build(
            null,
            episode,
            LinkLoadingResult(listOf(link), emptyList(), hashMapOf()),
            0
        )
        val session = LocalStreamProxyServer.startSession(request)

        val targetUrl = "https://cdn.example.com/segments/chunk_001.ts"
        val id = session.registerResource(targetUrl)

        // Exact match
        assertEquals(targetUrl, session.resolveResourceId(id))

        // Match with advertised .ts extension
        assertEquals(targetUrl, session.resolveResourceId("$id.ts"))

        // Match with .m3u8 or other extensions
        assertEquals(targetUrl, session.resolveResourceId("$id.m3u8"))

        // Unknown resource returns null
        org.junit.Assert.assertNull(session.resolveResourceId("non_existent_id"))
    }

    @Test
    fun `session active requests protect against expiration during playback`() {
        val episode = createSampleEpisode()
        val link = ExtractorLink(
            source = "Test",
            name = "TestLink",
            url = "https://cdn.example.com/video.m3u8",
            referer = "",
            quality = 1080,
            headers = mapOf("Cookie" to "a=1"),
            type = ExtractorLinkType.M3U8
        )
        val request = ExternalPlaybackRequestBuilder.build(
            null,
            episode,
            LinkLoadingResult(listOf(link), emptyList(), hashMapOf()),
            0
        )

        // Create session with 1 millisecond idle timeout
        val session = LocalStreamProxyServer.ProxySession(
            sessionId = "test_active_session",
            request = request,
            upstreamUrl = request.mediaUrl,
            effectiveHeaders = request.headers,
            idleTimeoutMs = 1L
        )

        // Simulate idle session exceeding timeout
        Thread.sleep(10)
        assertTrue(session.isExpired())

        // Now simulate active playback request streaming
        session.beginRequest()
        assertEquals(1, session.getActiveRequests())
        // Even though idle time exceeded 1ms, active requests protect the session!
        assertFalse(session.isExpired())

        session.endRequest()
        assertEquals(0, session.getActiveRequests())
    }

    @Test
    fun `HLS media playlist preserves live sliding window without adding EXT-X-ENDLIST`() {
        val livePlaylist = """
            #EXTM3U
            #EXT-X-VERSION:3
            #EXT-X-TARGETDURATION:6
            #EXT-X-MEDIA-SEQUENCE:50
            #EXTINF:6.000,
            seg50.ts
            #EXTINF:6.000,
            seg51.ts
            #EXTINF:6.000,
            seg52.ts
            #EXTINF:6.000,
            seg53.ts
        """.trimIndent()

        val episode = createSampleEpisode()
        val link = ExtractorLink(
            source = "Test",
            name = "Live",
            url = "https://cdn.example.com/live.m3u8",
            referer = "",
            quality = 1080,
            headers = mapOf("Cookie" to "a=1"),
            type = ExtractorLinkType.M3U8
        )
        val request = ExternalPlaybackRequestBuilder.build(
            null,
            episode,
            LinkLoadingResult(listOf(link), emptyList(), hashMapOf()),
            0
        )
        val session = LocalStreamProxyServer.startSession(request)

        val rewritten = LocalStreamProxyServer.rewriteHlsPlaylist(
            livePlaylist,
            "https://cdn.example.com/live.m3u8",
            session,
            "http://127.0.0.1:8888"
        )

        // Live playlist must NOT have EXT-X-ENDLIST appended
        assertFalse(rewritten.contains("#EXT-X-ENDLIST"))
        assertTrue(rewritten.contains("#EXT-X-MEDIA-SEQUENCE:50"))
        assertTrue(rewritten.contains("#EXT-X-TARGETDURATION:6"))
        assertEquals(4, session.resourceMap.size)
    }

    @Test
    fun `resolveUrl preserves authentication query parameters on same host relative resources`() {
        val baseSignedUrl = "https://secure-cdn.example.com/streams/index.m3u8?token=SECRET_AUTH_TOKEN&expires=9999999"

        // Relative segment without query params inherits auth tokens on same host
        val resolvedSameHost = LocalStreamProxyServer.resolveUrl(baseSignedUrl, "segment_001.ts")
        assertEquals(
            "https://secure-cdn.example.com/streams/segment_001.ts?token=SECRET_AUTH_TOKEN&expires=9999999",
            resolvedSameHost
        )

        // Relative segment that already has its own query params preserves its own
        val resolvedWithOwnParams = LocalStreamProxyServer.resolveUrl(baseSignedUrl, "segment_002.ts?custom=1")
        assertEquals(
            "https://secure-cdn.example.com/streams/segment_002.ts?custom=1",
            resolvedWithOwnParams
        )

        // Third-party host does not inherit credentials
        val resolvedExternal = LocalStreamProxyServer.resolveUrl(baseSignedUrl, "https://unrelated-domain.com/seg.ts")
        assertEquals(
            "https://unrelated-domain.com/seg.ts",
            resolvedExternal
        )
    }

    @Test
    fun `master playlist preserves all 4 variants and rendition groups without dropping any`() {
        val masterPlaylist = """
            #EXTM3U
            #EXT-X-VERSION:4
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio-main",NAME="English",DEFAULT=YES,URI="audio/en.m3u8"
            #EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="subs-main",NAME="English",DEFAULT=NO,URI="subs/en.vtt"
            #EXT-X-STREAM-INF:BANDWIDTH=20000000,AVERAGE-BANDWIDTH=18000000,RESOLUTION=3840x2160,FRAME-RATE=60.000,CODECS="avc1.640033,mp4a.40.2",AUDIO="audio-main",SUBTITLES="subs-main"
            variants/2160p.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=8000000,AVERAGE-BANDWIDTH=7000000,RESOLUTION=1920x1080,FRAME-RATE=60.000,CODECS="avc1.64002a,mp4a.40.2",AUDIO="audio-main",SUBTITLES="subs-main"
            variants/1080p.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=4000000,AVERAGE-BANDWIDTH=3500000,RESOLUTION=1280x720,FRAME-RATE=30.000,CODECS="avc1.4d401f,mp4a.40.2",AUDIO="audio-main",SUBTITLES="subs-main"
            variants/720p.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=1500000,AVERAGE-BANDWIDTH=1200000,RESOLUTION=854x480,FRAME-RATE=30.000,CODECS="avc1.4d401e,mp4a.40.2",AUDIO="audio-main",SUBTITLES="subs-main"
            variants/480p.m3u8
        """.trimIndent()

        val episode = createSampleEpisode()
        val link = ExtractorLink(
            source = "AdaptiveCDN",
            name = "Master",
            url = "https://cdn.example.com/hls/master.m3u8",
            referer = "https://example.com",
            quality = 0,
            headers = mapOf("Cookie" to "auth=123"),
            type = ExtractorLinkType.M3U8
        )
        val request = ExternalPlaybackRequestBuilder.build(
            null,
            episode,
            LinkLoadingResult(listOf(link), emptyList(), hashMapOf()),
            0
        )
        val session = LocalStreamProxyServer.startSession(request)
        val rewritten = LocalStreamProxyServer.rewriteHlsPlaylist(
            masterPlaylist,
            "https://cdn.example.com/hls/master.m3u8",
            session,
            "http://127.0.0.1:8888"
        )

        // All 4 variant declarations must be preserved with their full attributes
        assertTrue(rewritten.contains("RESOLUTION=3840x2160"))
        assertTrue(rewritten.contains("RESOLUTION=1920x1080"))
        assertTrue(rewritten.contains("RESOLUTION=1280x720"))
        assertTrue(rewritten.contains("RESOLUTION=854x480"))
        assertTrue(rewritten.contains("BANDWIDTH=20000000"))
        assertTrue(rewritten.contains("BANDWIDTH=8000000"))
        assertTrue(rewritten.contains("FRAME-RATE=60.000"))
        assertTrue(rewritten.contains("AUDIO=\"audio-main\""))
        assertTrue(rewritten.contains("SUBTITLES=\"subs-main\""))

        // Original variant paths must NOT appear raw; they must point to session proxy URLs
        assertFalse(rewritten.contains("variants/2160p.m3u8"))
        assertFalse(rewritten.contains("variants/1080p.m3u8"))
        assertFalse(rewritten.contains("variants/720p.m3u8"))
        assertFalse(rewritten.contains("variants/480p.m3u8"))

        // Verify resource mappings in session
        assertTrue(session.resourceMap.values.contains("https://cdn.example.com/hls/variants/2160p.m3u8"))
        assertTrue(session.resourceMap.values.contains("https://cdn.example.com/hls/variants/1080p.m3u8"))
        assertTrue(session.resourceMap.values.contains("https://cdn.example.com/hls/variants/720p.m3u8"))
        assertTrue(session.resourceMap.values.contains("https://cdn.example.com/hls/variants/480p.m3u8"))
        assertTrue(session.resourceMap.values.contains("https://cdn.example.com/hls/audio/en.m3u8"))
        assertTrue(session.resourceMap.values.contains("https://cdn.example.com/hls/subs/en.vtt"))
    }

    @Test
    fun `single-quality filename pattern index-s2160p-v1-a1 m3u8 is treated according to its content`() {
        val mediaPlaylist2160p = """
            #EXTM3U
            #EXT-X-VERSION:4
            #EXT-X-TARGETDURATION:6
            #EXT-X-MEDIA-SEQUENCE:0
            #EXTINF:6.000,
            seg_000.ts
            #EXTINF:6.000,
            seg_001.ts
            #EXT-X-ENDLIST
        """.trimIndent()

        val url = "https://moon.zenoak.top/vdb/eDdzNGFub3dYbWpPaVhTeWFILWxHdzoxY3NCN0hxd2FYQm13RHdESmNPRnN1S2ZpcEo4UlNDSmNmdTYxbkhhUEMw/index-s2160p-v1-a1.m3u8"
        val episode = createSampleEpisode()
        val link = ExtractorLink(
            source = "MoonZenoak",
            name = "2160p",
            url = url,
            referer = "",
            quality = 2160,
            headers = mapOf("Cookie" to "moon_token=abc"),
            type = ExtractorLinkType.M3U8
        )
        val request = ExternalPlaybackRequestBuilder.build(
            null,
            episode,
            LinkLoadingResult(listOf(link), emptyList(), hashMapOf()),
            0
        )
        val session = LocalStreamProxyServer.startSession(request)

        val rewritten = LocalStreamProxyServer.rewriteHlsPlaylist(
            mediaPlaylist2160p,
            url,
            session,
            "http://127.0.0.1:8888"
        )

        // Verified by its contents, not guessed as a master playlist
        assertTrue(rewritten.contains("#EXT-X-TARGETDURATION:6"))
        assertTrue(rewritten.contains("#EXT-X-ENDLIST"))
        assertFalse(rewritten.contains("seg_000.ts\n"))
        assertTrue(session.resourceMap.values.contains("https://moon.zenoak.top/vdb/eDdzNGFub3dYbWpPaVhTeWFILWxHdzoxY3NCN0hxd2FYQm13RHdESmNPRnN1S2ZpcEo4UlNDSmNmdTYxbkhhUEMw/seg_000.ts"))
    }

    @Test
    fun `complete nested playlist chain Master - Variant - Segments - Audio - Subtitles`() {
        val masterPlaylist = """
            #EXTM3U
            #EXT-X-VERSION:4
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio",NAME="English",URI="audio/eng.m3u8"
            #EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="subs",NAME="English",URI="subs/eng.m3u8"
            #EXT-X-STREAM-INF:BANDWIDTH=5000000,RESOLUTION=1920x1080,AUDIO="audio",SUBTITLES="subs"
            1080p/index.m3u8
        """.trimIndent()

        val variantPlaylist = """
            #EXTM3U
            #EXT-X-VERSION:4
            #EXT-X-TARGETDURATION:6
            #EXT-X-KEY:METHOD=AES-128,URI="keys/video.key",IV=0x11223344556677889900AABBCCDDEEFF
            #EXT-X-MAP:URI="init/init.mp4",BYTERANGE="1200@0"
            #EXTINF:6.000,
            seg_1001.ts
            #EXTINF:6.000,
            seg_1002.ts
            #EXT-X-ENDLIST
        """.trimIndent()

        val audioPlaylist = """
            #EXTM3U
            #EXT-X-VERSION:4
            #EXT-X-TARGETDURATION:6
            #EXTINF:6.000,
            audio_1001.aac
            #EXT-X-ENDLIST
        """.trimIndent()

        val subsPlaylist = """
            #EXTM3U
            #EXT-X-VERSION:4
            #EXT-X-TARGETDURATION:6
            #EXTINF:6.000,
            sub_1001.vtt
            #EXT-X-ENDLIST
        """.trimIndent()

        val episode = createSampleEpisode()
        val link = ExtractorLink(
            source = "NestedCDN",
            name = "Master",
            url = "https://secure.example.com/hls/master.m3u8",
            referer = "https://secure.example.com",
            quality = 0,
            headers = mapOf("Cookie" to "sec=1"),
            type = ExtractorLinkType.M3U8
        )
        val request = ExternalPlaybackRequestBuilder.build(
            null,
            episode,
            LinkLoadingResult(listOf(link), emptyList(), hashMapOf()),
            0
        )
        val session = LocalStreamProxyServer.startSession(request)

        // 1. Rewrite Master
        val rewrittenMaster = LocalStreamProxyServer.rewriteHlsPlaylist(
            masterPlaylist,
            "https://secure.example.com/hls/master.m3u8",
            session,
            "http://127.0.0.1:8888"
        )
        assertTrue(rewrittenMaster.contains("/res/"))

        // 2. Rewrite Nested Variant
        val rewrittenVariant = LocalStreamProxyServer.rewriteHlsPlaylist(
            variantPlaylist,
            "https://secure.example.com/hls/1080p/index.m3u8",
            session,
            "http://127.0.0.1:8888"
        )
        assertTrue(rewrittenVariant.contains("METHOD=AES-128"))
        assertTrue(rewrittenVariant.contains("BYTERANGE=\"1200@0\""))
        assertFalse(rewrittenVariant.contains("\"keys/video.key\""))
        assertFalse(rewrittenVariant.contains("\"init/init.mp4\""))
        assertFalse(rewrittenVariant.contains("seg_1001.ts\n"))

        // 3. Rewrite Audio Rendition
        val rewrittenAudio = LocalStreamProxyServer.rewriteHlsPlaylist(
            audioPlaylist,
            "https://secure.example.com/hls/audio/eng.m3u8",
            session,
            "http://127.0.0.1:8888"
        )
        assertFalse(rewrittenAudio.contains("audio_1001.aac\n"))

        // 4. Rewrite Subtitle Rendition
        val rewrittenSubs = LocalStreamProxyServer.rewriteHlsPlaylist(
            subsPlaylist,
            "https://secure.example.com/hls/subs/eng.m3u8",
            session,
            "http://127.0.0.1:8888"
        )
        assertFalse(rewrittenSubs.contains("sub_1001.vtt\n"))

        // Assert all resources are correctly mapped in the session
        assertTrue(session.resourceMap.values.contains("https://secure.example.com/hls/1080p/keys/video.key"))
        assertTrue(session.resourceMap.values.contains("https://secure.example.com/hls/1080p/init/init.mp4"))
        assertTrue(session.resourceMap.values.contains("https://secure.example.com/hls/1080p/seg_1001.ts"))
        assertTrue(session.resourceMap.values.contains("https://secure.example.com/hls/1080p/seg_1002.ts"))
        assertTrue(session.resourceMap.values.contains("https://secure.example.com/hls/audio/audio_1001.aac"))
        assertTrue(session.resourceMap.values.contains("https://secure.example.com/hls/subs/sub_1001.vtt"))
    }

    @Test
    fun `separately extracted sibling links are not merged into an unverified synthetic master`() {
        val episode = createSampleEpisode()
        val link2160 = ExtractorLink("MultiProvider", "4K", "https://cdn.example.com/2160p/index.m3u8", "", 2160, mapOf("Cookie" to "c=1"), type = ExtractorLinkType.M3U8)
        val link1080 = ExtractorLink("MultiProvider", "1080p", "https://cdn.example.com/1080p/index.m3u8", "", 1080, mapOf("Cookie" to "c=1"), type = ExtractorLinkType.M3U8)
        val link720 = ExtractorLink("MultiProvider", "720p", "https://cdn.example.com/720p/index.m3u8", "", 720, mapOf("Cookie" to "c=1"), type = ExtractorLinkType.M3U8)
        val link480 = ExtractorLink("MultiProvider", "480p", "https://cdn.example.com/480p/index.m3u8", "", 480, mapOf("Cookie" to "c=1"), type = ExtractorLinkType.M3U8)

        val result = LinkLoadingResult(listOf(link2160, link1080, link720, link480), emptyList(), hashMapOf())
        val request = ExternalPlaybackRequestBuilder.build(null, episode, result, 1)

        // The selected link remains the source of truth. Sibling links are not
        // silently grouped into a synthetic master without verification.
        assertEquals("https://cdn.example.com/1080p/index.m3u8", request.mediaUrl)
        assertTrue(request.allVariants.isEmpty())
        org.junit.Assert.assertNull(request.masterUrl)
        assertTrue(request.requiresProxy())
    }

    @Test
    fun `direct playback is selected only when no authentication context is required`() {
        val episode = createSampleEpisode()

        fun buildRequest(
            name: String,
            url: String,
            quality: Int,
            headers: Map<String, String> = emptyMap(),
            referer: String = "",
            type: ExtractorLinkType = ExtractorLinkType.M3U8
        ): ExternalPlaybackRequest {
            val link = ExtractorLink("Test", name, url, referer, quality, headers, type = type)
            return ExternalPlaybackRequestBuilder.build(
                null,
                episode,
                LinkLoadingResult(listOf(link), emptyList(), hashMapOf()),
                0
            )
        }

        assertEquals(
            ExternalPlaybackRoute.DIRECT,
            buildRequest("1080p", "https://cdn.example.com/clean/index.m3u8", 1080).playbackRoute()
        )
        assertEquals(
            ExternalPlaybackRoute.LOCAL_PROXY,
            buildRequest(
                "1080p",
                "https://cdn.example.com/clean/index.m3u8",
                1080,
                headers = mapOf("Referer" to "https://provider.example.com")
            ).playbackRoute()
        )
        assertEquals(
            ExternalPlaybackRoute.LOCAL_PROXY,
            buildRequest(
                "1080p",
                "https://cdn.example.com/clean/index.m3u8",
                1080,
                headers = mapOf("User-Agent" to "ProviderUA/1.0")
            ).playbackRoute()
        )
        assertEquals(
            ExternalPlaybackRoute.LOCAL_PROXY,
            buildRequest(
                "1080p",
                "https://cdn.example.com/clean/index.m3u8",
                1080,
                headers = mapOf("Cookie" to "session=1")
            ).playbackRoute()
        )
        assertEquals(
            ExternalPlaybackRoute.DIRECT,
            buildRequest(
                "1080p",
                "https://cdn.example.com/clean/movie.mp4",
                1080,
                type = ExtractorLinkType.VIDEO
            ).playbackRoute()
        )
    }

    @Test
    fun `DRM license or subtitle headers force the local proxy route`() {
        val clean = ExternalPlaybackRequest(
            mediaUrl = "https://cdn.example.com/clean/index.m3u8",
            linkType = ExtractorLinkType.M3U8
        )
        assertEquals(ExternalPlaybackRoute.DIRECT, clean.playbackRoute())

        val licensed = clean.copy(
            isDrm = true,
            drmLicenseUrl = "https://license.example.com/widevine",
            drmHeaders = mapOf("Authorization" to "Bearer license-token")
        )
        assertEquals(ExternalPlaybackRoute.LOCAL_PROXY, licensed.playbackRoute())

        val subtitled = clean.copy(
            subtitles = listOf(
                ExternalSubtitle(
                    url = "https://cdn.example.com/sub.vtt",
                    name = "English",
                    languageCode = "en",
                    mimeType = "text/vtt",
                    headers = mapOf("Cookie" to "sub=1")
                )
            )
        )
        assertEquals(ExternalPlaybackRoute.LOCAL_PROXY, subtitled.playbackRoute())
    }

    @Test
    fun `shared relay rewrites authenticated HLS and subtitles for any local player`() {
        val episode = createSampleEpisode()
        val link = ExtractorLink(
            source = "Provider",
            name = "1080p",
            url = "https://cdn.example.com/auth/index.m3u8?token=abc",
            referer = "https://provider.example.com",
            quality = 1080,
            headers = mapOf("Cookie" to "session=1"),
            type = ExtractorLinkType.M3U8
        )
        val subtitle = SubtitleData(
            originalName = "English",
            nameSuffix = "",
            url = "https://subs.example.com/en.vtt",
            origin = SubtitleOrigin.URL,
            mimeType = "text/vtt",
            headers = mapOf("Cookie" to "sub=1"),
            languageCode = "en"
        )
        val result = LinkLoadingResult(
            links = listOf(link),
            subs = listOf(subtitle),
            syncData = hashMapOf()
        )

        val prepared = kotlinx.coroutines.runBlocking {
            ExternalPlaybackSupport.prepare(null, episode, result, 0)
        }
        try {
            assertTrue(prepared.isProxied)
            assertEquals(0, prepared.index)
            assertEquals(1, prepared.result.links.size)

            val relayedLink = prepared.result.links.single()
            assertTrue(relayedLink.url.startsWith("http://127.0.0.1:"))
            assertTrue(relayedLink.url.endsWith("/master.m3u8"))
            assertTrue(relayedLink.headers.isEmpty())
            assertEquals("", relayedLink.referer)

            val relayedSubtitle = prepared.result.subs.single()
            assertTrue(relayedSubtitle.url.startsWith("http://127.0.0.1:"))
            assertTrue(relayedSubtitle.url.endsWith(".vtt"))
            assertTrue(relayedSubtitle.headers.isEmpty())

            // The relay owns the credentials; the player never receives them.
            assertNotNull(prepared.proxySession)
            assertEquals(
                "session=1",
                prepared.proxySession?.effectiveHeaders?.get("Cookie")
            )
        } finally {
            ExternalPlaybackSupport.stopActivePlayback(null)
        }
    }

    @Test
    fun `clean external links stay direct instead of being relayed`() {
        val link = ExtractorLink(
            source = "Provider",
            name = "1080p",
            url = "https://cdn.example.com/public/index.m3u8",
            referer = "",
            quality = 1080,
            headers = emptyMap(),
            type = ExtractorLinkType.M3U8
        )
        val result = LinkLoadingResult(listOf(link), emptyList(), hashMapOf())

        val prepared = kotlinx.coroutines.runBlocking {
            ExternalPlaybackSupport.prepare(null, createSampleEpisode(), result, 0)
        }

        assertFalse(prepared.isProxied)
        assertEquals(0, prepared.index)
        assertEquals(result, prepared.result)
        assertEquals(link.url, prepared.result.links.single().url)
    }

    @Test
    fun `shared relay only handles HTTP video and HLS sources`() {
        fun link(url: String, type: ExtractorLinkType) = ExtractorLink(
            source = "Provider",
            name = "Test",
            url = url,
            referer = "",
            quality = 1080,
            headers = emptyMap(),
            type = type
        )

        assertTrue(ExternalPlaybackSupport.isRelayEligible(link("https://cdn.example.com/a.m3u8", ExtractorLinkType.M3U8)))
        assertTrue(ExternalPlaybackSupport.isRelayEligible(link("https://cdn.example.com/a.mp4", ExtractorLinkType.VIDEO)))
        assertFalse(ExternalPlaybackSupport.isRelayEligible(link("https://cdn.example.com/a.mpd", ExtractorLinkType.DASH)))
        assertFalse(ExternalPlaybackSupport.isRelayEligible(link("magnet:?xt=urn:btih:abc", ExtractorLinkType.MAGNET)))
        assertFalse(ExternalPlaybackSupport.isRelayEligible(link("file:///sdcard/local.m3u8", ExtractorLinkType.M3U8)))

        val drmLink = kotlinx.coroutines.runBlocking {
            newDrmExtractorLink(
                source = "Provider",
                name = "DRM",
                url = "https://cdn.example.com/drm/index.m3u8",
                uuid = WIDEVINE_DRM_UUID,
                type = ExtractorLinkType.M3U8
            ) { }
        }
        assertFalse(ExternalPlaybackSupport.isRelayEligible(drmLink))
    }

    @Test
    fun `local external players opt into the shared relay while remote casters do not`() {
        val actions = VideoClickActionHolder.allVideoClickActions

        val localPlayerNames = actions
            .filterIsInstance<OpenInAppAction>()
            .filter { it.supportsLocalStreamProxy }
            .map { it.javaClass.simpleName }
            .toSet()

        assertTrue(
            localPlayerNames.containsAll(
                setOf(
                    "VlcPackage",
                    "VlcNightlyPackage",
                    "MpvPackage",
                    "MpvExPackage",
                    "MpvYTDLPackage",
                    "MpvKtPackage",
                    "MpvKtPreviewPackage",
                    "MpvRxPackage",
                    "NextPlayerPackage",
                    "JustPlayerPackage",
                    "OnlyPlayer",
                    "NetworkStreamPlayerPackage",
                    "LibreTorrentPackage",
                    "BiglyBTPackage"
                )
            )
        )

        val webVideoCast = actions
            .filterIsInstance<WebVideoCastPackage>()
            .single()
        assertFalse(webVideoCast.supportsLocalStreamProxy)
    }

    @Test
    fun `checkIsHls detects HLS signature independently of Content-Type or route extension`() {
        // 1. Text with #EXTM3U and generic application/octet-stream content-type
        val hlsBuffer = Buffer().writeUtf8("#EXTM3U\n#EXT-X-VERSION:3\n#EXTINF:6.0,\nseg1.ts\n")
        assertTrue(LocalStreamProxyServer.checkIsHls(hlsBuffer, "application/octet-stream", "https://cdn.example.com/stream?id=999"))

        // 2. Text with UTF-8 BOM (\uFEFF) and text/plain
        val bomBuffer = Buffer().write(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())).writeUtf8("#EXTM3U\n")
        assertTrue(LocalStreamProxyServer.checkIsHls(bomBuffer, "text/plain", "https://cdn.example.com/playlist"))

        // 3. Binary MPEG-TS video segment (starting with 0x47 sync byte) -> NOT HLS
        val tsBytes = ByteArray(188) { 0x47.toByte() }
        val tsBuffer = Buffer().write(tsBytes)
        assertFalse(LocalStreamProxyServer.checkIsHls(tsBuffer, "application/octet-stream", "https://cdn.example.com/chunk.ts"))

        // 4. Binary MP4 video segment (starting with ftyp box) -> NOT HLS
        val mp4Bytes = byteArrayOf(0x00, 0x00, 0x00, 0x20, 'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte())
        val mp4Buffer = Buffer().write(mp4Bytes)
        assertFalse(LocalStreamProxyServer.checkIsHls(mp4Buffer, "video/mp4", "https://cdn.example.com/init.mp4"))
    }

    @Test
    fun `parseRange handles byte-range headers and slicing`() {
        assertEquals(Pair(0L, 499L), LocalStreamProxyServer.parseRange("bytes=0-499"))
        assertEquals(Pair(1000L, null), LocalStreamProxyServer.parseRange("bytes=1000-"))
        assertEquals(Pair(500L, 999L), LocalStreamProxyServer.parseRange("bytes=500-999"))
        org.junit.Assert.assertNull(LocalStreamProxyServer.parseRange(""))
        org.junit.Assert.assertNull(LocalStreamProxyServer.parseRange("invalid"))
    }

    @Test
    fun `end to end HTTP proxy streaming and 60-second multi-segment playback test`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val serverPort = server.address.port

        val masterContent = """
            #EXTM3U
            #EXT-X-VERSION:3
            #EXT-X-STREAM-INF:BANDWIDTH=5000000,RESOLUTION=1920x1080
            1080p.m3u8
        """.trimIndent()

        // 12 segments * 6 seconds = 72 seconds of media
        val mediaContent = buildString {
            append("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:6\n#EXT-X-MEDIA-SEQUENCE:0\n")
            for (i in 0 until 12) {
                append("#EXTINF:6.000,\nseg_$i.ts\n")
            }
            append("#EXT-X-ENDLIST\n")
        }

        server.createContext("/hls/master.m3u8") { exchange ->
            val cookie = exchange.requestHeaders.getFirst("Cookie")
            if (cookie != "auth_token=valid_123") {
                exchange.sendResponseHeaders(401, 0)
                exchange.responseBody.close()
                return@createContext
            }
            val bytes = masterContent.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.set("Content-Type", "application/vnd.apple.mpegurl")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.write(bytes)
            exchange.responseBody.close()
        }

        server.createContext("/hls/1080p.m3u8") { exchange ->
            val cookie = exchange.requestHeaders.getFirst("Cookie")
            if (cookie != "auth_token=valid_123") {
                exchange.sendResponseHeaders(401, 0)
                exchange.responseBody.close()
                return@createContext
            }
            val bytes = mediaContent.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.set("Content-Type", "application/vnd.apple.mpegurl")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.write(bytes)
            exchange.responseBody.close()
        }

        for (i in 0 until 12) {
            server.createContext("/hls/seg_$i.ts") { exchange ->
                val cookie = exchange.requestHeaders.getFirst("Cookie")
                if (cookie != "auth_token=valid_123") {
                    exchange.sendResponseHeaders(401, 0)
                    exchange.responseBody.close()
                    return@createContext
                }
                val chunk = ByteArray(1024) { (i + 1).toByte() }
                exchange.responseHeaders.set("Content-Type", "video/mp2t")
                exchange.sendResponseHeaders(200, chunk.size.toLong())
                exchange.responseBody.write(chunk)
                exchange.responseBody.close()
            }
        }

        server.start()
        try {
            val upstreamMasterUrl = "http://127.0.0.1:$serverPort/hls/master.m3u8"
            val episode = createSampleEpisode()
            val link = ExtractorLink(
                source = "MockUpstream",
                name = "Master",
                url = upstreamMasterUrl,
                referer = "http://127.0.0.1:$serverPort",
                quality = 0,
                headers = mapOf("Cookie" to "auth_token=valid_123"),
                type = ExtractorLinkType.M3U8
            )
            val request = ExternalPlaybackRequestBuilder.build(
                null,
                episode,
                LinkLoadingResult(listOf(link), emptyList(), hashMapOf()),
                0
            )

            val session = LocalStreamProxyServer.startSession(request)
            val proxyPort = LocalStreamProxyServer.ensureStarted()

            // 1. Request Master Playlist through local proxy
            val masterProxyUrl = "http://127.0.0.1:$proxyPort/session/${session.sessionId}/master.m3u8"
            val masterConnection = URL(masterProxyUrl).openConnection() as java.net.HttpURLConnection
            assertEquals(200, masterConnection.responseCode)
            val proxiedMasterText = masterConnection.inputStream.bufferedReader().readText()
            assertTrue(proxiedMasterText.contains("RESOLUTION=1920x1080"))
            assertTrue(proxiedMasterText.contains("/session/${session.sessionId}/res/"))

            // Extract variant proxy URL
            val variantLine = proxiedMasterText.lines().first { it.contains("/session/${session.sessionId}/res/") }
            val variantProxyUrl = variantLine.trim()

            // 2. Request Media Playlist through local proxy
            val variantConnection = URL(variantProxyUrl).openConnection() as java.net.HttpURLConnection
            assertEquals(200, variantConnection.responseCode)
            val proxiedMediaText = variantConnection.inputStream.bufferedReader().readText()
            assertTrue(proxiedMediaText.contains("#EXT-X-TARGETDURATION:6"))
            assertTrue(proxiedMediaText.contains("#EXT-X-ENDLIST"))

            // 3. Request segments across the whole fixture (>60 seconds of video playback)
            val segmentLines = proxiedMediaText.lines().filter { it.contains("/res/") && it.endsWith(".ts") }
            assertEquals(12, segmentLines.size)

            // Test first segment (0s)
            val seg0Conn = URL(segmentLines[0].trim()).openConnection() as java.net.HttpURLConnection
            assertEquals(200, seg0Conn.responseCode)
            val seg0Bytes = seg0Conn.inputStream.readBytes()
            assertEquals(1024, seg0Bytes.size)
            assertEquals(1.toByte(), seg0Bytes[0])

            // Test middle segment (36s)
            val seg6Conn = URL(segmentLines[6].trim()).openConnection() as java.net.HttpURLConnection
            assertEquals(200, seg6Conn.responseCode)
            val seg6Bytes = seg6Conn.inputStream.readBytes()
            assertEquals(1024, seg6Bytes.size)
            assertEquals(7.toByte(), seg6Bytes[0])

            // Test end segment (66s - 72s)
            val seg11Conn = URL(segmentLines[11].trim()).openConnection() as java.net.HttpURLConnection
            assertEquals(200, seg11Conn.responseCode)
            val seg11Bytes = seg11Conn.inputStream.readBytes()
            assertEquals(1024, seg11Bytes.size)
            assertEquals(12.toByte(), seg11Bytes[0])

            // 4. Verify session and active requests remain healthy and valid
            assertNotNull(LocalStreamProxyServer.getSession(session.sessionId))
            assertFalse(session.isExpired())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `single quality HLS startup routes media playlist with valid signature and rewritten segments`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val serverPort = server.address.port

        val mediaContent = """
            #EXTM3U
            #EXT-X-VERSION:3
            #EXT-X-TARGETDURATION:6
            #EXTINF:6.0,
            seg_single_001.ts
            #EXTINF:6.0,
            seg_single_002.ts
            #EXT-X-ENDLIST
        """.trimIndent()

        server.createContext("/hls/single_media.m3u8") { exchange ->
            val bytes = mediaContent.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.set("Content-Type", "application/vnd.apple.mpegurl")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.write(bytes)
            exchange.responseBody.close()
        }

        server.start()
        try {
            val singleUrl = "http://127.0.0.1:$serverPort/hls/single_media.m3u8"
            val episode = createSampleEpisode()
            val link = ExtractorLink(
                source = "SingleHlsSource",
                name = "Single 1080p",
                url = singleUrl,
                referer = "http://127.0.0.1:$serverPort",
                quality = 1080,
                headers = mapOf("Cookie" to "user=single"),
                type = ExtractorLinkType.M3U8
            )
            val request = ExternalPlaybackRequestBuilder.build(null, episode, LinkLoadingResult(listOf(link), emptyList(), hashMapOf()), 0)

            // Single quality: masterUrl must be null, allVariants empty
            org.junit.Assert.assertNull(request.masterUrl)
            assertTrue(request.allVariants.isEmpty())
            assertTrue(request.requiresProxy())

            val session = LocalStreamProxyServer.startSession(request)
            val proxyPort = LocalStreamProxyServer.ensureStarted()

            val streamUrl = LocalStreamProxyServer.createStreamUrl(session)
            assertTrue(streamUrl.contains("/session/${session.sessionId}/master.m3u8"))

            val conn = URL(streamUrl).openConnection() as java.net.HttpURLConnection
            // Simulate ExoPlayer probe header
            conn.setRequestProperty("Range", "bytes=0-")
            assertEquals(200, conn.responseCode)
            assertEquals("application/vnd.apple.mpegurl", conn.contentType)

            val body = conn.inputStream.bufferedReader().readText()
            assertTrue(body.startsWith("#EXTM3U"))
            assertTrue(body.contains("#EXT-X-TARGETDURATION:6"))
            assertTrue(body.contains("/session/${session.sessionId}/res/"))
            assertFalse(body.contains("seg_single_001.ts\n"))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `synthetic master falls back safely when variants are empty or invalid`() {
        val episode = createSampleEpisode()
        val singleLink = ExtractorLink("Test", "Single", "https://cdn.example.com/single.m3u8", "", 1080, mapOf("Cookie" to "a=1"), type = ExtractorLinkType.M3U8)

        val request = ExternalPlaybackRequest(
            mediaUrl = singleLink.url,
            displayName = "Single Quality",
            mimeType = "application/vnd.apple.mpegurl",
            linkType = ExtractorLinkType.M3U8,
            headers = singleLink.headers,
            referer = null,
            origin = null,
            cookie = "a=1",
            userAgent = null,
            authorization = null,
            drmUuid = null,
            drmScheme = null,
            drmLicenseUrl = null,
            drmHeaders = emptyMap(),
            subtitles = emptyList(),
            selectedSubtitle = null,
            allVariants = emptyList()
        )

        val session = LocalStreamProxyServer.startSession(request)
        val synthetic = LocalStreamProxyServer.buildSyntheticMasterPlaylist(emptyList(), session, "http://127.0.0.1:8888")
        assertTrue(synthetic.isEmpty())

        val oneVariant = listOf(HlsVariant("https://cdn.example.com/one.m3u8", 1080))
        val syntheticOne = LocalStreamProxyServer.buildSyntheticMasterPlaylist(oneVariant, session, "http://127.0.0.1:8888")
        assertTrue(syntheticOne.isEmpty())
    }

    @Test
    fun `master route is distinct from resource routing and per-resource headers survive`() {
        val episode = createSampleEpisode()
        val link = ExtractorLink("Test", "Stream", "https://cdn.example.com/stream.m3u8", "", 1080, mapOf("Cookie" to "session=root"), type = ExtractorLinkType.M3U8)
        val request = ExternalPlaybackRequestBuilder.build(null, episode, LinkLoadingResult(listOf(link), emptyList(), hashMapOf()), 0)
        val session = LocalStreamProxyServer.startSession(request)

        val customVariantHeaders = mapOf("Authorization" to "Bearer variant_token_xyz")
        val variantUrl = "https://cdn.example.com/variant/1080p.m3u8"
        val resId = session.registerResource(variantUrl, customVariantHeaders)

        // 1. Resource extension resolves
        assertEquals(variantUrl, session.resolveResourceId(resId))
        assertEquals(variantUrl, session.resolveResourceId("$resId.m3u8"))

        // 2. Per-resource headers survive
        val resolvedHeaders = session.getResourceHeaders(resId)
        assertNotNull(resolvedHeaders)
        assertEquals("Bearer variant_token_xyz", resolvedHeaders?.get("Authorization"))

        val resolvedByUrlHeaders = session.getResourceHeaders(variantUrl)
        assertNotNull(resolvedByUrlHeaders)
        assertEquals("Bearer variant_token_xyz", resolvedByUrlHeaders?.get("Authorization"))
    }

    @Test
    fun `NetworkStreamPlayerPackage is discoverable in preferred video players without context`() {
        val players = com.lagradost.cloudstream3.actions.VideoClickActionHolder.getPlayers(null)
        val nsp = players.firstOrNull { it is com.lagradost.cloudstream3.actions.temp.NetworkStreamPlayerPackage }
        assertNotNull("NetworkStreamPlayerPackage must be discoverable in getPlayers(null) for settings", nsp)
        assertTrue("NetworkStreamPlayerPackage must be recognized as a player", nsp!!.isPlayer)
    }

    @Test
    fun `proxyHttpClient dispatcher and cache settings prevent starvation and disk locks`() {
        val client = LocalStreamProxyServer.proxyHttpClient
        // Cache must be null to prevent disk lock contention during streaming
        org.junit.Assert.assertNull("Proxy client disk cache must be disabled", client.cache)
        // High concurrency dispatcher limits for parallel prefetching
        assertTrue("maxRequests must be at least 128", client.dispatcher.maxRequests >= 128)
        assertTrue("maxRequestsPerHost must be at least 32", client.dispatcher.maxRequestsPerHost >= 32)
    }

    @Test
    fun `handleMediaStream flushes status and headers immediately and streams incrementally`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val serverPort = server.address.port

        server.createContext("/stream/chunked_segment.ts") { exchange ->
            exchange.responseHeaders.set("Content-Type", "video/mp2t")
            exchange.sendResponseHeaders(200, 2048)
            val os = exchange.responseBody
            val chunk1 = ByteArray(1024) { 1.toByte() }
            os.write(chunk1)
            os.flush()
            Thread.sleep(100)
            val chunk2 = ByteArray(1024) { 2.toByte() }
            os.write(chunk2)
            os.flush()
            os.close()
        }

        server.start()
        try {
            val upstreamUrl = "http://127.0.0.1:$serverPort/stream/chunked_segment.ts"
            val request = ExternalPlaybackRequest(
                mediaUrl = upstreamUrl,
                headers = emptyMap()
            )
            val session = LocalStreamProxyServer.startSession(request)
            val proxyPort = LocalStreamProxyServer.ensureStarted()

            val resId = session.registerResource(upstreamUrl, emptyMap())
            val proxyUrl = "http://127.0.0.1:$proxyPort/session/${session.sessionId}/res/$resId.ts"

            val connection = URL(proxyUrl).openConnection() as java.net.HttpURLConnection
            connection.connectTimeout = 3000
            connection.readTimeout = 5000

            assertEquals(200, connection.responseCode)
            assertEquals("video/mp2t", connection.contentType)
            assertEquals(2048L, connection.contentLengthLong)

            val inStream = connection.inputStream
            val firstChunk = ByteArray(1024)
            var bytesRead = 0
            while (bytesRead < 1024) {
                val r = inStream.read(firstChunk, bytesRead, 1024 - bytesRead)
                if (r == -1) break
                bytesRead += r
            }
            assertEquals(1024, bytesRead)
            assertEquals(1.toByte(), firstChunk[0])

            val remainingBytes = inStream.readBytes()
            assertEquals(1024, remainingBytes.size)
            assertEquals(2.toByte(), remainingBytes[0])
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `handleMediaStream handles unknown or indeterminate content length streaming`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val serverPort = server.address.port

        server.createContext("/stream/indeterminate.ts") { exchange ->
            exchange.responseHeaders.set("Content-Type", "video/mp2t")
            exchange.sendResponseHeaders(200, 0)
            val os = exchange.responseBody
            for (i in 0 until 4) {
                os.write(ByteArray(512) { (i + 10).toByte() })
                os.flush()
            }
            os.close()
        }

        server.start()
        try {
            val upstreamUrl = "http://127.0.0.1:$serverPort/stream/indeterminate.ts"
            val request = ExternalPlaybackRequest(
                mediaUrl = upstreamUrl,
                headers = emptyMap()
            )
            val session = LocalStreamProxyServer.startSession(request)
            val proxyPort = LocalStreamProxyServer.ensureStarted()

            val resId = session.registerResource(upstreamUrl, emptyMap())
            val proxyUrl = "http://127.0.0.1:$proxyPort/session/${session.sessionId}/res/$resId.ts"

            val connection = URL(proxyUrl).openConnection() as java.net.HttpURLConnection
            assertEquals(200, connection.responseCode)
            assertEquals("chunked", connection.getHeaderField("Transfer-Encoding"))
            assertEquals(-1L, connection.contentLengthLong)
            val allBytes = connection.inputStream.readBytes()
            assertEquals(2048, allBytes.size)
            assertEquals(10.toByte(), allBytes[0])
            assertEquals(13.toByte(), allBytes[2047])
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `rewriteMediaPlaylist propagates parent headers to child segments and encryption keys`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val serverPort = server.address.port

        val mediaPlaylist = """
            #EXTM3U
            #EXT-X-VERSION:3
            #EXT-X-TARGETDURATION:4
            #EXT-X-KEY:METHOD=AES-128,URI="enc.key"
            #EXTINF:4.000,
            segment_1.ts
            #EXT-X-ENDLIST
        """.trimIndent()

        var keyAuthHeader: String? = null
        var segAuthHeader: String? = null

        server.createContext("/hls/playlist.m3u8") { exchange ->
            val bytes = mediaPlaylist.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.set("Content-Type", "application/vnd.apple.mpegurl")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.write(bytes)
            exchange.responseBody.close()
        }

        server.createContext("/hls/enc.key") { exchange ->
            keyAuthHeader = exchange.requestHeaders.getFirst("X-Parent-Token")
            val keyBytes = ByteArray(16) { 0xAA.toByte() }
            exchange.responseHeaders.set("Content-Type", "application/octet-stream")
            exchange.sendResponseHeaders(200, keyBytes.size.toLong())
            exchange.responseBody.write(keyBytes)
            exchange.responseBody.close()
        }

        server.createContext("/hls/segment_1.ts") { exchange ->
            segAuthHeader = exchange.requestHeaders.getFirst("X-Parent-Token")
            val segBytes = ByteArray(512) { 0x55.toByte() }
            exchange.responseHeaders.set("Content-Type", "video/mp2t")
            exchange.sendResponseHeaders(200, segBytes.size.toLong())
            exchange.responseBody.write(segBytes)
            exchange.responseBody.close()
        }

        server.start()
        try {
            val playlistUrl = "http://127.0.0.1:$serverPort/hls/playlist.m3u8"
            val request = ExternalPlaybackRequest(
                mediaUrl = playlistUrl,
                headers = mapOf("X-Parent-Token" to "Token_Secret_ABC")
            )
            val session = LocalStreamProxyServer.startSession(request)
            val proxyPort = LocalStreamProxyServer.ensureStarted()

            val resId = session.registerResource(playlistUrl, mapOf("X-Parent-Token" to "Token_Secret_ABC"))
            val plConn = URL("http://127.0.0.1:$proxyPort/session/${session.sessionId}/res/$resId.m3u8").openConnection() as java.net.HttpURLConnection
            assertEquals(200, plConn.responseCode)
            val rewritten = plConn.inputStream.bufferedReader().readText()

            val keyLine = rewritten.lines().first { it.contains("#EXT-X-KEY:") }
            val keyProxyUrl = keyLine.substringAfter("URI=\"").substringBefore("\"")
            val segProxyUrl = rewritten.lines().first { it.contains("/res/") && it.endsWith(".ts") }.trim()

            val keyConn = URL(keyProxyUrl).openConnection() as java.net.HttpURLConnection
            assertEquals(200, keyConn.responseCode)
            val keyReceived = keyConn.inputStream.readBytes()
            assertEquals(16, keyReceived.size)
            assertEquals("Token_Secret_ABC", keyAuthHeader)

            val segConn = URL(segProxyUrl).openConnection() as java.net.HttpURLConnection
            assertEquals(200, segConn.responseCode)
            val segReceived = segConn.inputStream.readBytes()
            assertEquals(512, segReceived.size)
            assertEquals("Token_Secret_ABC", segAuthHeader)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `RequestDiagnostic tracks and logs lifecycle stages and bytes transferred`() {
        val diag = LocalStreamProxyServer.RequestDiagnostic(
            requestId = 42L,
            method = "GET",
            routeType = "res",
            resourceType = "media segment",
            targetUrl = "https://cdn.example.com/streams/seg1.ts",
            rangeReceived = "bytes=0-1023",
            rangeForwarded = true
        )
        diag.logStart()
        assertEquals(42L, diag.requestId)
        assertEquals("GET", diag.method)
        assertEquals("res", diag.routeType)
        assertEquals("media segment", diag.resourceType)
        assertEquals(true, diag.rangeForwarded)

        diag.onHeadersReceived(200, "video/mp2t", 1024L)
        assertEquals(200, diag.upstreamStatus)
        assertEquals("video/mp2t", diag.contentType)
        assertEquals(1024L, diag.contentLength)
        assertTrue(diag.timeToHeaders >= 0)

        diag.onFirstByte()
        assertTrue(diag.timeToFirstByte >= 0)

        diag.bytesTransferred = 1024L
        diag.onComplete()
        assertEquals(1024L, diag.bytesTransferred)

        val errDiag = LocalStreamProxyServer.RequestDiagnostic(
            requestId = 43L,
            method = "GET",
            routeType = "res",
            resourceType = "media playlist",
            targetUrl = "https://cdn.example.com/streams/variant.m3u8",
            rangeReceived = null,
            rangeForwarded = false
        )
        errDiag.onError("connecting", java.net.SocketTimeoutException("Read timed out"))
        assertEquals("connecting", errDiag.failureStage)
        assertEquals("Read timed out", errDiag.failureReason)
    }

    @Test
    fun `incremental segment transfer delivers first bytes before upstream completes`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val serverPort = server.address.port
        val secondChunkAllowed = CountDownLatch(1)

        server.createContext("/stream/incremental.ts") { exchange ->
            exchange.responseHeaders.set("Content-Type", "video/mp2t")
            exchange.sendResponseHeaders(200, 2048)
            val out = exchange.responseBody
            out.write(ByteArray(1024) { 1 })
            out.flush()
            secondChunkAllowed.await(15, TimeUnit.SECONDS)
            out.write(ByteArray(1024) { 2 })
            out.flush()
            out.close()
        }

        server.start()
        var session: LocalStreamProxyServer.ProxySession? = null
        try {
            val upstreamUrl = "http://127.0.0.1:$serverPort/stream/incremental.ts"
            session = LocalStreamProxyServer.startSession(buildTestRequest(upstreamUrl))
            val proxyPort = LocalStreamProxyServer.ensureStarted()
            val resId = session.registerResource(upstreamUrl)
            val proxyUrl = "http://127.0.0.1:$proxyPort/session/${session.sessionId}/res/$resId.ts"

            val connection = openConnection(proxyUrl)
            connection.readTimeout = 5_000
            assertEquals(200, connection.responseCode)
            val input = connection.inputStream
            val first = ByteArray(1024)
            var read = 0
            val started = System.currentTimeMillis()
            while (read < first.size) {
                val n = input.read(first, read, first.size - read)
                if (n == -1) fail("Proxy closed before the first chunk")
                read += n
            }
            val elapsed = System.currentTimeMillis() - started

            // If the proxy buffered the complete upstream body this read would
            // block until the 15 second upstream latch expires.
            assertTrue("First chunk took ${elapsed}ms; proxy appears to buffer", elapsed < 5_000)
            assertArrayEquals(ByteArray(1024) { 1 }, first)

            secondChunkAllowed.countDown()
            val second = input.use { it.readBytes() }
            assertEquals(1024, second.size)
            assertArrayEquals(ByteArray(1024) { 2 }, second)
        } finally {
            secondChunkAllowed.countDown()
            session?.let { LocalStreamProxyServer.stopSession(it.sessionId) }
            server.stop(0)
        }
    }

    @Test
    fun `concurrent segment requests are served in parallel`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val serverPort = server.address.port
        val active = AtomicInteger(0)
        val maximum = AtomicInteger(0)

        server.createContext("/stream/concurrent.ts") { exchange ->
            val index = exchange.requestURI.query.substringAfter("i=", "0").toInt()
            val now = active.incrementAndGet()
            maximum.updateAndGet { maxOf(it, now) }
            try {
                Thread.sleep(150)
                val body = ByteArray(256) { index.toByte() }
                exchange.responseHeaders.set("Content-Type", "video/mp2t")
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.write(body)
                exchange.responseBody.close()
            } finally {
                active.decrementAndGet()
            }
        }

        server.executor = Executors.newFixedThreadPool(8)
        server.start()
        var session: LocalStreamProxyServer.ProxySession? = null
        try {
            val upstreamBase = "http://127.0.0.1:$serverPort/stream/concurrent.ts"
            session = LocalStreamProxyServer.startSession(buildTestRequest(upstreamBase))
            val proxyPort = LocalStreamProxyServer.ensureStarted()
            val count = 8
            val proxyUrls = (0 until count).map { i ->
                val upstream = "$upstreamBase?i=$i"
                val resId = session.registerResource(upstream)
                "http://127.0.0.1:$proxyPort/session/${session.sessionId}/res/$resId.ts"
            }

            val executor = Executors.newFixedThreadPool(count)
            val start = CountDownLatch(1)
            val bodies = ConcurrentHashMap<Int, ByteArray>()
            val failures = ConcurrentHashMap<Int, Throwable>()
            try {
                val futures = proxyUrls.mapIndexed { index, url ->
                    executor.submit {
                        try {
                            start.await(5, TimeUnit.SECONDS)
                            val connection = openConnection(url)
                            assertEquals(200, connection.responseCode)
                            bodies[index] = readAllBytes(connection)
                        } catch (t: Throwable) {
                            failures[index] = t
                        }
                    }
                }
                start.countDown()
                futures.forEach { it.get(15, TimeUnit.SECONDS) }
            } finally {
                executor.shutdownNow()
            }

            assertTrue("Concurrent requests failed: $failures", failures.isEmpty())
            for (i in 0 until count) {
                val body = bodies[i]
                assertNotNull("Missing body for request $i", body)
                assertEquals(256, body!!.size)
                assertEquals(i.toByte(), body[0])
            }
            assertTrue("Requests were serialized (max concurrent=$maximum)", maximum.get() >= 2)
        } finally {
            session?.let { LocalStreamProxyServer.stopSession(it.sessionId) }
            server.stop(0)
        }
    }

    @Test
    fun `slow upstream headers are waited for without breaking response framing`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val serverPort = server.address.port
        val body = ByteArray(777) { 9 }

        server.createContext("/stream/slow_headers.ts") { exchange ->
            Thread.sleep(700)
            exchange.responseHeaders.set("Content-Type", "video/mp2t")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.write(body)
            exchange.responseBody.close()
        }

        server.start()
        var session: LocalStreamProxyServer.ProxySession? = null
        try {
            val upstream = "http://127.0.0.1:$serverPort/stream/slow_headers.ts"
            session = LocalStreamProxyServer.startSession(buildTestRequest(upstream))
            val proxyPort = LocalStreamProxyServer.ensureStarted()
            val resId = session.registerResource(upstream)
            val connection = openConnection(
                "http://127.0.0.1:$proxyPort/session/${session.sessionId}/res/$resId.ts"
            )
            connection.readTimeout = 5_000
            assertEquals(200, connection.responseCode)
            assertEquals(777L, connection.contentLengthLong)
            assertArrayEquals(body, readAllBytes(connection))
        } finally {
            session?.let { LocalStreamProxyServer.stopSession(it.sessionId) }
            server.stop(0)
        }
    }

    @Test
    fun `gzip responses are decoded and stale encoding metadata is not forwarded`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val serverPort = server.address.port
        val payload = ByteArray(4096) { (it % 251).toByte() }
        val compressed = gzip(payload)

        server.createContext("/stream/gzip.ts") { exchange ->
            exchange.responseHeaders.set("Content-Type", "video/mp2t")
            exchange.responseHeaders.set("Content-Encoding", "gzip")
            exchange.sendResponseHeaders(200, compressed.size.toLong())
            exchange.responseBody.write(compressed)
            exchange.responseBody.close()
        }

        server.start()
        var session: LocalStreamProxyServer.ProxySession? = null
        try {
            val upstream = "http://127.0.0.1:$serverPort/stream/gzip.ts"
            session = LocalStreamProxyServer.startSession(buildTestRequest(upstream))
            val proxyPort = LocalStreamProxyServer.ensureStarted()
            val resId = session.registerResource(upstream)
            val connection = openConnection(
                "http://127.0.0.1:$proxyPort/session/${session.sessionId}/res/$resId.ts"
            )
            assertEquals(200, connection.responseCode)
            assertNull(connection.getHeaderField("Content-Encoding"))
            assertEquals("chunked", connection.getHeaderField("Transfer-Encoding"))
            assertEquals(-1L, connection.contentLengthLong)
            assertArrayEquals(payload, readAllBytes(connection))
        } finally {
            session?.let { LocalStreamProxyServer.stopSession(it.sessionId) }
            server.stop(0)
        }
    }

    @Test
    fun `playlist requests isolate Range while media segments forward it`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val serverPort = server.address.port
        val playlist = """
            #EXTM3U
            #EXT-X-VERSION:3
            #EXT-X-TARGETDURATION:6
            #EXTINF:6.0,
            seg.ts
            #EXT-X-ENDLIST
        """.trimIndent()
        val segmentBody = ByteArray(1000) { it.toByte() }
        val playlistRange = AtomicReference<String?>("unset")
        val segmentRange = AtomicReference<String?>("unset")
        val segmentContentRange = AtomicReference<String?>(null)

        server.createContext("/hls/range.m3u8") { exchange ->
            playlistRange.set(exchange.requestHeaders.getFirst("Range"))
            val bytes = playlist.toByteArray()
            exchange.responseHeaders.set("Content-Type", "application/vnd.apple.mpegurl")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.write(bytes)
            exchange.responseBody.close()
        }
        server.createContext("/hls/seg.ts") { exchange ->
            val range = exchange.requestHeaders.getFirst("Range")
            segmentRange.set(range)
            if (range == "bytes=100-199") {
                exchange.responseHeaders.set("Content-Type", "video/mp2t")
                exchange.responseHeaders.set("Content-Range", "bytes 100-199/1000")
                exchange.sendResponseHeaders(206, 100)
                exchange.responseBody.write(segmentBody.copyOfRange(100, 200))
                exchange.responseBody.close()
                segmentContentRange.set("bytes 100-199/1000")
            } else {
                exchange.responseHeaders.set("Content-Type", "video/mp2t")
                exchange.sendResponseHeaders(200, segmentBody.size.toLong())
                exchange.responseBody.write(segmentBody)
                exchange.responseBody.close()
            }
        }

        server.start()
        var session: LocalStreamProxyServer.ProxySession? = null
        try {
            val upstream = "http://127.0.0.1:$serverPort/hls/range.m3u8"
            session = LocalStreamProxyServer.startSession(
                buildTestRequest(upstream, headers = mapOf("Cookie" to "a=1"))
            )
            LocalStreamProxyServer.ensureStarted()

            val streamUrl = LocalStreamProxyServer.createStreamUrl(session)
            val masterConnection = openConnection(streamUrl, range = "bytes=0-")
            assertEquals(200, masterConnection.responseCode)
            val rewritten = masterConnection.inputStream.bufferedReader().readText()
            assertNull("Playlist Range must not be forwarded", playlistRange.get())
            assertTrue(rewritten.contains("/res/"))

            val segmentProxyUrl = rewritten.lines()
                .first { it.contains("/res/") && it.endsWith(".ts") }
                .trim()
            val segmentConnection = openConnection(segmentProxyUrl, range = "bytes=100-199")
            assertEquals(206, segmentConnection.responseCode)
            assertEquals("bytes 100-199/1000", segmentConnection.getHeaderField("Content-Range"))
            assertEquals(100L, segmentConnection.contentLengthLong)
            assertEquals("bytes=100-199", segmentRange.get())
            assertArrayEquals(segmentBody.copyOfRange(100, 200), readAllBytes(segmentConnection))
            assertEquals("bytes 100-199/1000", segmentContentRange.get())
        } finally {
            session?.let { LocalStreamProxyServer.stopSession(it.sessionId) }
            server.stop(0)
        }
    }

    @Test
    fun `range is sliced locally when upstream ignores it`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val serverPort = server.address.port
        val body = ByteArray(1000) { it.toByte() }

        server.createContext("/stream/ignores_range.ts") { exchange ->
            exchange.responseHeaders.set("Content-Type", "video/mp2t")
            // Deliberately ignore the incoming Range header.
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.write(body)
            exchange.responseBody.close()
        }

        server.start()
        var session: LocalStreamProxyServer.ProxySession? = null
        try {
            val upstream = "http://127.0.0.1:$serverPort/stream/ignores_range.ts"
            session = LocalStreamProxyServer.startSession(buildTestRequest(upstream))
            val proxyPort = LocalStreamProxyServer.ensureStarted()
            val resId = session.registerResource(upstream)
            val twice = session.registerResource(upstream)
            assertEquals("Resource IDs must stay stable across seeks", resId, twice)

            val proxyUrl = "http://127.0.0.1:$proxyPort/session/${session.sessionId}/res/$resId.ts"
            val first = openConnection(proxyUrl, range = "bytes=100-199")
            assertEquals(206, first.responseCode)
            assertEquals("bytes 100-199/1000", first.getHeaderField("Content-Range"))
            assertArrayEquals(body.copyOfRange(100, 200), readAllBytes(first))

            val second = openConnection(proxyUrl, range = "bytes=500-599")
            assertEquals(206, second.responseCode)
            assertEquals("bytes 500-599/1000", second.getHeaderField("Content-Range"))
            assertArrayEquals(body.copyOfRange(500, 600), readAllBytes(second))
        } finally {
            session?.let { LocalStreamProxyServer.stopSession(it.sessionId) }
            server.stop(0)
        }
    }

    @Test
    fun `relative URIs resolve against the final URL after redirects`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val serverPort = server.address.port
        val wrongPathRequested = AtomicBoolean(false)

        server.createContext("/start/master.m3u8") { exchange ->
            exchange.responseHeaders.set("Location", "/redirected/master.m3u8")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        server.createContext("/redirected/master.m3u8") { exchange ->
            val body = """
                #EXTM3U
                #EXT-X-VERSION:4
                #EXT-X-STREAM-INF:BANDWIDTH=1000000,RESOLUTION=1280x720
                variant/index.m3u8
            """.trimIndent().toByteArray()
            exchange.responseHeaders.set("Content-Type", "application/vnd.apple.mpegurl")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.write(body)
            exchange.responseBody.close()
        }
        server.createContext("/start/variant/index.m3u8") { exchange ->
            wrongPathRequested.set(true)
            exchange.sendResponseHeaders(404, -1)
            exchange.close()
        }
        server.createContext("/redirected/variant/index.m3u8") { exchange ->
            val body = """
                #EXTM3U
                #EXT-X-VERSION:4
                #EXT-X-TARGETDURATION:6
                #EXTINF:6,
                seg_1.ts
                #EXT-X-ENDLIST
            """.trimIndent().toByteArray()
            exchange.responseHeaders.set("Content-Type", "application/vnd.apple.mpegurl")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.write(body)
            exchange.responseBody.close()
        }
        server.createContext("/redirected/variant/seg_1.ts") { exchange ->
            val body = ByteArray(321) { 0x42 }
            exchange.responseHeaders.set("Content-Type", "video/mp2t")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.write(body)
            exchange.responseBody.close()
        }

        server.start()
        var session: LocalStreamProxyServer.ProxySession? = null
        try {
            val upstream = "http://127.0.0.1:$serverPort/start/master.m3u8"
            session = LocalStreamProxyServer.startSession(buildTestRequest(upstream))
            val proxyPort = LocalStreamProxyServer.ensureStarted()

            val masterConnection = openConnection(
                "http://127.0.0.1:$proxyPort/session/${session.sessionId}/master.m3u8"
            )
            assertEquals(200, masterConnection.responseCode)
            val master = masterConnection.inputStream.bufferedReader().readText()
            val variantUrl = master.lines().first { it.contains("/res/") }.trim()

            val variantConnection = openConnection(variantUrl)
            assertEquals(200, variantConnection.responseCode)
            val media = variantConnection.inputStream.bufferedReader().readText()
            val segmentUrl = media.lines().first { it.contains("/res/") && it.endsWith(".ts") }.trim()

            val segmentConnection = openConnection(segmentUrl)
            assertEquals(200, segmentConnection.responseCode)
            val segment = readAllBytes(segmentConnection)
            assertEquals(321, segment.size)
            assertEquals(0x42.toByte(), segment[0])
            assertFalse("Relative URI resolved against the pre-redirect URL", wrongPathRequested.get())
        } finally {
            session?.let { LocalStreamProxyServer.stopSession(it.sessionId) }
            server.stop(0)
        }
    }

    @Test
    fun `authentication headers reach master media key init segment and alternate audio`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val serverPort = server.address.port
        val expectedHeaders = mapOf(
            "Cookie" to "auth=1",
            "Referer" to "https://provider.example.com/",
            "Origin" to "https://provider.example.com",
            "Authorization" to "Bearer abc",
            "X-Provider" to "token-123",
            "User-Agent" to "CustomUA/1.0"
        )
        val seenPaths = ConcurrentHashMap.newKeySet<String>()

        fun requireAuth(exchange: com.sun.net.httpserver.HttpExchange): Boolean {
            for ((key, value) in expectedHeaders) {
                if (exchange.requestHeaders.getFirst(key) != value) {
                    exchange.sendResponseHeaders(401, -1)
                    exchange.close()
                    return false
                }
            }
            seenPaths.add(exchange.requestURI.path)
            return true
        }

        fun send(exchange: com.sun.net.httpserver.HttpExchange, type: String, bytes: ByteArray) {
            exchange.responseHeaders.set("Content-Type", type)
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.write(bytes)
            exchange.responseBody.close()
        }

        val master = """
            #EXTM3U
            #EXT-X-VERSION:4
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud",NAME="English",DEFAULT=YES,URI="audio/eng.m3u8"
            #EXT-X-STREAM-INF:BANDWIDTH=5000000,RESOLUTION=1920x1080,AUDIO="aud"
            video/index.m3u8
        """.trimIndent()
        val videoMedia = """
            #EXTM3U
            #EXT-X-VERSION:6
            #EXT-X-TARGETDURATION:6
            #EXT-X-KEY:METHOD=AES-128,URI="keys/video.key",IV=0x11223344556677889900AABBCCDDEEFF
            #EXT-X-MAP:URI="init/init.mp4",BYTERANGE="1200@0"
            #EXTINF:6,
            seg_0.ts
            #EXTINF:6,
            seg_1.ts
            #EXT-X-ENDLIST
        """.trimIndent()
        val audioMedia = """
            #EXTM3U
            #EXT-X-VERSION:4
            #EXT-X-TARGETDURATION:6
            #EXTINF:6,
            audio_0.aac
            #EXT-X-ENDLIST
        """.trimIndent()

        server.createContext("/hls/master.m3u8") { exchange ->
            if (!requireAuth(exchange)) return@createContext
            send(exchange, "application/vnd.apple.mpegurl", master.toByteArray())
        }
        server.createContext("/hls/video/index.m3u8") { exchange ->
            if (!requireAuth(exchange)) return@createContext
            send(exchange, "application/vnd.apple.mpegurl", videoMedia.toByteArray())
        }
        server.createContext("/hls/audio/eng.m3u8") { exchange ->
            if (!requireAuth(exchange)) return@createContext
            send(exchange, "application/vnd.apple.mpegurl", audioMedia.toByteArray())
        }
        server.createContext("/hls/video/keys/video.key") { exchange ->
            if (!requireAuth(exchange)) return@createContext
            send(exchange, "application/octet-stream", ByteArray(16) { 0x11 })
        }
        server.createContext("/hls/video/init/init.mp4") { exchange ->
            if (!requireAuth(exchange)) return@createContext
            send(exchange, "video/mp4", ByteArray(32) { 0x22 })
        }
        server.createContext("/hls/video/seg_0.ts") { exchange ->
            if (!requireAuth(exchange)) return@createContext
            send(exchange, "video/mp2t", ByteArray(512) { 0x33 })
        }
        server.createContext("/hls/video/seg_1.ts") { exchange ->
            if (!requireAuth(exchange)) return@createContext
            send(exchange, "video/mp2t", ByteArray(512) { 0x44 })
        }
        server.createContext("/hls/audio/audio_0.aac") { exchange ->
            if (!requireAuth(exchange)) return@createContext
            send(exchange, "audio/aac", ByteArray(256) { 0x55 })
        }

        server.start()
        var session: LocalStreamProxyServer.ProxySession? = null
        try {
            val upstream = "http://127.0.0.1:$serverPort/hls/master.m3u8"
            session = LocalStreamProxyServer.startSession(
                buildTestRequest(
                    upstream,
                    headers = mapOf(
                        "Cookie" to "auth=1",
                        "Origin" to "https://provider.example.com",
                        "Authorization" to "Bearer abc",
                        "X-Provider" to "token-123",
                        "User-Agent" to "CustomUA/1.0"
                    ),
                    referer = "https://provider.example.com/"
                )
            )
            LocalStreamProxyServer.ensureStarted()
            val masterConnection = openConnection(LocalStreamProxyServer.createStreamUrl(session))
            assertEquals(200, masterConnection.responseCode)
            val rewrittenMaster = masterConnection.inputStream.bufferedReader().readText()

            val audioUrl = rewrittenMaster.lines()
                .first { it.startsWith("#EXT-X-MEDIA") }
                .substringAfter("URI=\"").substringBefore("\"")
            val videoUrl = rewrittenMaster.lines()
                .first { it.contains("/res/") && !it.startsWith("#") }.trim()

            val videoConnection = openConnection(videoUrl)
            assertEquals(200, videoConnection.responseCode)
            val rewrittenVideo = videoConnection.inputStream.bufferedReader().readText()

            val audioConnection = openConnection(audioUrl)
            assertEquals(200, audioConnection.responseCode)
            val rewrittenAudio = audioConnection.inputStream.bufferedReader().readText()

            val keyUrl = rewrittenVideo.lines()
                .first { it.startsWith("#EXT-X-KEY") }
                .substringAfter("URI=\"").substringBefore("\"")
            val mapUrl = rewrittenVideo.lines()
                .first { it.startsWith("#EXT-X-MAP") }
                .substringAfter("URI=\"").substringBefore("\"")
            val segUrls = rewrittenVideo.lines()
                .filter { it.contains("/res/") && it.endsWith(".ts") }
                .map { it.trim() }
            val audioSegUrl = rewrittenAudio.lines()
                .first { it.contains("/res/") && it.endsWith(".aac") }
                .trim()

            assertEquals(200, openConnection(keyUrl).responseCode)
            val mapConnection = openConnection(mapUrl)
            assertEquals(200, mapConnection.responseCode)
            assertEquals(32, readAllBytes(mapConnection).size)
            assertEquals(2, segUrls.size)
            for (segUrl in segUrls) {
                val connection = openConnection(segUrl)
                assertEquals(200, connection.responseCode)
                assertEquals(512, readAllBytes(connection).size)
            }
            val audioSegConnection = openConnection(audioSegUrl)
            assertEquals(200, audioSegConnection.responseCode)
            assertEquals(256, readAllBytes(audioSegConnection).size)

            assertTrue(seenPaths.contains("/hls/master.m3u8"))
            assertTrue(seenPaths.contains("/hls/video/index.m3u8"))
            assertTrue(seenPaths.contains("/hls/audio/eng.m3u8"))
            assertTrue(seenPaths.contains("/hls/video/keys/video.key"))
            assertTrue(seenPaths.contains("/hls/video/init/init.mp4"))
            assertTrue(seenPaths.contains("/hls/video/seg_0.ts"))
            assertTrue(seenPaths.contains("/hls/video/seg_1.ts"))
            assertTrue(seenPaths.contains("/hls/audio/audio_0.aac"))
        } finally {
            session?.let { LocalStreamProxyServer.stopSession(it.sessionId) }
            server.stop(0)
        }
    }

    @Test
    fun `signed child query parameters and unrelated tags are preserved exactly`() {
        val playlist = """
            #EXTM3U
            #EXT-X-VERSION:6
            #EXT-X-TARGETDURATION:6
            # comment with URI="https://example.com/not-a-resource"
            #EXT-X-CUSTOM-METADATA:URI="https://example.com/also-not-a-resource"
            #EXT-X-START:TIME-OFFSET=10.0
            #EXT-X-KEY:METHOD=AES-128,URI="keys/key.bin?kid=1&sig=abc",IV=0x11223344556677889900AABBCCDDEEFF
            #EXT-X-MAP:URI="init/main.mp4?token=init&sig=xyz",BYTERANGE="1200@0"
            #EXT-X-DISCONTINUITY
            #EXTINF:6,
            seg.ts?token=child&expires=1
            #EXT-X-ENDLIST
        """.trimIndent()
        val base = "https://cdn.example.com/path/index.m3u8?token=parent&expires=2"
        val session = LocalStreamProxyServer.startSession(buildTestRequest(base))
        try {
            val rewritten = LocalStreamProxyServer.rewriteHlsPlaylist(
                playlist,
                base,
                session,
                "http://127.0.0.1:8888"
            )

            assertTrue(session.resourceMap.values.contains("https://cdn.example.com/path/seg.ts?token=child&expires=1"))
            assertTrue(session.resourceMap.values.contains("https://cdn.example.com/path/keys/key.bin?kid=1&sig=abc"))
            assertTrue(session.resourceMap.values.contains("https://cdn.example.com/path/init/main.mp4?token=init&sig=xyz"))
            assertFalse("Parent query must not overwrite child query", rewritten.contains("token=parent&expires=2"))
            assertTrue(rewritten.contains("#EXT-X-DISCONTINUITY"))
            assertTrue(rewritten.contains("#EXT-X-START:TIME-OFFSET=10.0"))
            assertTrue(rewritten.contains("BYTERANGE=\"1200@0\""))
            assertTrue(rewritten.contains("# comment with URI=\"https://example.com/not-a-resource\""))
            assertTrue(rewritten.contains("#EXT-X-CUSTOM-METADATA:URI=\"https://example.com/also-not-a-resource\""))
        } finally {
            LocalStreamProxyServer.stopSession(session.sessionId)
        }
    }

    @Test
    fun `active transfers prevent session cleanup and expire afterwards`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val serverPort = server.address.port
        val requestStarted = CountDownLatch(1)
        val releaseBody = CountDownLatch(1)
        val body = ByteArray(256) { 7 }

        server.createContext("/stream/slow_session.ts") { exchange ->
            requestStarted.countDown()
            releaseBody.await(10, TimeUnit.SECONDS)
            exchange.responseHeaders.set("Content-Type", "video/mp2t")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.write(body)
            exchange.responseBody.close()
        }

        server.start()
        var session: LocalStreamProxyServer.ProxySession? = null
        try {
            val upstream = "http://127.0.0.1:$serverPort/stream/slow_session.ts"
            session = LocalStreamProxyServer.startSession(
                buildTestRequest(upstream),
                idleTimeoutMs = 100L
            )
            val proxyPort = LocalStreamProxyServer.ensureStarted()
            val resId = session.registerResource(upstream)
            val proxyUrl = "http://127.0.0.1:$proxyPort/session/${session.sessionId}/res/$resId.ts"

            val responseFuture = Executors.newSingleThreadExecutor().submit<ByteArray> {
                readAllBytes(openConnection(proxyUrl))
            }
            assertTrue(requestStarted.await(5, TimeUnit.SECONDS))
            Thread.sleep(250)
            LocalStreamProxyServer.cleanupExpiredSessions()
            assertNotNull("Active transfer must protect its session", LocalStreamProxyServer.getSession(session.sessionId))
            releaseBody.countDown()
            assertArrayEquals(body, responseFuture.get(10, TimeUnit.SECONDS))

            Thread.sleep(250)
            LocalStreamProxyServer.cleanupExpiredSessions()
            assertNull("Idle session should expire after its transfer", LocalStreamProxyServer.getSession(session.sessionId))
        } finally {
            releaseBody.countDown()
            session?.let { LocalStreamProxyServer.stopSession(it.sessionId) }
            server.stop(0)
        }
    }

    @Test
    fun `client disconnect cancels the transfer and releases the upstream body`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val serverPort = server.address.port
        val upstreamWriteFailed = CountDownLatch(1)
        val chunk = ByteArray(64 * 1024) { 0x5A }

        server.createContext("/stream/unbounded.ts") { exchange ->
            exchange.responseHeaders.set("Content-Type", "video/mp2t")
            exchange.sendResponseHeaders(200, 0)
            val out = exchange.responseBody
            try {
                for (i in 0 until 500) {
                    out.write(chunk)
                    out.flush()
                    Thread.sleep(5)
                }
            } catch (_: Throwable) {
                upstreamWriteFailed.countDown()
            } finally {
                try {
                    out.close()
                } catch (_: Throwable) {
                }
            }
        }

        server.start()
        var session: LocalStreamProxyServer.ProxySession? = null
        var socket: Socket? = null
        try {
            val upstream = "http://127.0.0.1:$serverPort/stream/unbounded.ts"
            session = LocalStreamProxyServer.startSession(buildTestRequest(upstream))
            val proxyPort = LocalStreamProxyServer.ensureStarted()
            val resId = session.registerResource(upstream)

            socket = Socket("127.0.0.1", proxyPort)
            socket.soTimeout = 5_000
            val request = "GET /session/${session.sessionId}/res/$resId.ts HTTP/1.1\r\n" +
                    "Host: 127.0.0.1:$proxyPort\r\nConnection: close\r\n\r\n"
            socket.getOutputStream().write(request.toByteArray(Charsets.US_ASCII))
            socket.getOutputStream().flush()

            val input = socket.getInputStream()
            val buffer = ByteArray(16 * 1024)
            var received = 0
            while (received < 256 * 1024) {
                val n = input.read(buffer)
                if (n == -1) break
                received += n
            }
            assertTrue("Proxy should have started streaming", received > 0)
            socket.close()
            socket = null

            assertTrue(
                "Upstream body was not released after client disconnect",
                upstreamWriteFailed.await(10, TimeUnit.SECONDS)
            )

            val deadline = System.currentTimeMillis() + 5_000
            while (session.getActiveRequests() > 0 && System.currentTimeMillis() < deadline) {
                Thread.sleep(20)
            }
            assertEquals(0, session.getActiveRequests())
            assertNotNull(LocalStreamProxyServer.getSession(session.sessionId))
        } finally {
            try {
                socket?.close()
            } catch (_: Throwable) {
            }
            session?.let { LocalStreamProxyServer.stopSession(it.sessionId) }
            server.stop(0)
        }
    }

    @Test
    fun `optional audio failure does not terminate the main video session`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val serverPort = server.address.port
        val videoBody = ByteArray(300) { 3 }

        val master = """
            #EXTM3U
            #EXT-X-VERSION:4
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud",NAME="Broken",DEFAULT=YES,URI="audio/missing.m3u8"
            #EXT-X-STREAM-INF:BANDWIDTH=1000000,AUDIO="aud"
            video/index.m3u8
        """.trimIndent()
        val videoMedia = """
            #EXTM3U
            #EXT-X-VERSION:4
            #EXT-X-TARGETDURATION:6
            #EXTINF:6,
            seg.ts
            #EXT-X-ENDLIST
        """.trimIndent()

        server.createContext("/hls/master.m3u8") { exchange ->
            val bytes = master.toByteArray()
            exchange.responseHeaders.set("Content-Type", "application/vnd.apple.mpegurl")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.write(bytes)
            exchange.responseBody.close()
        }
        server.createContext("/hls/video/index.m3u8") { exchange ->
            val bytes = videoMedia.toByteArray()
            exchange.responseHeaders.set("Content-Type", "application/vnd.apple.mpegurl")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.write(bytes)
            exchange.responseBody.close()
        }
        server.createContext("/hls/video/seg.ts") { exchange ->
            exchange.responseHeaders.set("Content-Type", "video/mp2t")
            exchange.sendResponseHeaders(200, videoBody.size.toLong())
            exchange.responseBody.write(videoBody)
            exchange.responseBody.close()
        }
        server.createContext("/hls/audio/missing.m3u8") { exchange ->
            exchange.sendResponseHeaders(503, -1)
            exchange.close()
        }

        server.start()
        var session: LocalStreamProxyServer.ProxySession? = null
        try {
            val upstream = "http://127.0.0.1:$serverPort/hls/master.m3u8"
            session = LocalStreamProxyServer.startSession(buildTestRequest(upstream))
            LocalStreamProxyServer.ensureStarted()
            val masterConnection = openConnection(LocalStreamProxyServer.createStreamUrl(session))
            assertEquals(200, masterConnection.responseCode)
            val rewrittenMaster = masterConnection.inputStream.bufferedReader().readText()

            val audioUrl = rewrittenMaster.lines()
                .first { it.startsWith("#EXT-X-MEDIA") }
                .substringAfter("URI=\"").substringBefore("\"")
            val videoUrl = rewrittenMaster.lines()
                .first { it.contains("/res/") && !it.startsWith("#") }.trim()

            val audioConnection = openConnection(audioUrl)
            assertEquals(503, audioConnection.responseCode)
            assertNotNull(LocalStreamProxyServer.getSession(session.sessionId))

            val videoConnection = openConnection(videoUrl)
            assertEquals(200, videoConnection.responseCode)
            val rewrittenVideo = videoConnection.inputStream.bufferedReader().readText()
            val segmentUrl = rewrittenVideo.lines()
                .first { it.contains("/res/") && it.endsWith(".ts") }
                .trim()
            val segmentConnection = openConnection(segmentUrl)
            assertEquals(200, segmentConnection.responseCode)
            assertArrayEquals(videoBody, readAllBytes(segmentConnection))
        } finally {
            session?.let { LocalStreamProxyServer.stopSession(it.sessionId) }
            server.stop(0)
        }
    }

    @Test
    fun `expired session returns a controlled 410 instead of hanging`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val serverPort = server.address.port
        server.createContext("/hls/expires.m3u8") { exchange ->
            val bytes = "#EXTM3U\n#EXT-X-ENDLIST\n".toByteArray()
            exchange.responseHeaders.set("Content-Type", "application/vnd.apple.mpegurl")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.write(bytes)
            exchange.responseBody.close()
        }
        server.start()
        var session: LocalStreamProxyServer.ProxySession? = null
        try {
            val upstream = "http://127.0.0.1:$serverPort/hls/expires.m3u8"
            session = LocalStreamProxyServer.startSession(
                buildTestRequest(upstream),
                idleTimeoutMs = 50L
            )
            LocalStreamProxyServer.ensureStarted()
            val streamUrl = LocalStreamProxyServer.createStreamUrl(session)
            Thread.sleep(150)
            val connection = openConnection(streamUrl)
            assertEquals(410, connection.responseCode)
        } finally {
            session?.let { LocalStreamProxyServer.stopSession(it.sessionId) }
            server.stop(0)
        }
    }

    @Test
    fun `server shutdown is tied to sessions not Activity visibility`() {
        LocalStreamProxyServer.resetForTest()
        var session: LocalStreamProxyServer.ProxySession? = null
        try {
            val request = buildTestRequest("http://127.0.0.1:1/hls/lifecycle.m3u8")
            session = LocalStreamProxyServer.startSession(request)
            val port = LocalStreamProxyServer.ensureStarted()
            assertTrue(port > 0)
            assertTrue(LocalStreamProxyServer.hasSessions())

            // Backgrounding/Activity destruction does not call this API. Even when
            // asked, it must refuse to stop while a session exists.
            assertFalse(LocalStreamProxyServer.shutdownIfIdle())
            assertTrue(LocalStreamProxyServer.port > 0)
            assertNotNull(LocalStreamProxyServer.getSession(session.sessionId))

            LocalStreamProxyServer.stopSession(session.sessionId)
            session = null
            assertTrue(LocalStreamProxyServer.shutdownIfIdle())

            // A later launch can bind a fresh socket without recreating the object.
            assertTrue(LocalStreamProxyServer.ensureStarted() > 0)
        } finally {
            session?.let { LocalStreamProxyServer.stopSession(it.sessionId) }
            LocalStreamProxyServer.resetForTest()
        }
    }
}
