package com.lagradost.cloudstream3.actions

import android.content.Context
import com.lagradost.api.Log
import com.lagradost.cloudstream3.services.LocalStreamProxyService
import com.lagradost.cloudstream3.ui.player.SubtitleData
import com.lagradost.cloudstream3.ui.player.SubtitleOrigin
import com.lagradost.cloudstream3.ui.result.LinkLoadingResult
import com.lagradost.cloudstream3.ui.result.ResultEpisode
import com.lagradost.cloudstream3.utils.DrmExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink

/**
 * Shared bridge between external player intents and [LocalStreamProxyServer].
 *
 * Any local external player can call [prepare] before building its intent. When
 * the selected source needs authentication context (cookies, referer, origin,
 * authorization, custom headers or a non-default user agent), the source and its
 * subtitles are replaced with loopback URLs served by the relay. The player then
 * never receives upstream credentials, while every HLS child request is made by
 * the relay with the correct headers.
 *
 * Remote casters must opt out: a device on the LAN cannot reach the phone's
 * 127.0.0.1 relay. See [OpenInAppAction.supportsLocalStreamProxy].
 */
object ExternalPlaybackSupport {
    private const val TAG = "ExternalPlayback"

    @Volatile
    private var activeSessionId: String? = null

    data class PreparedPlayback(
        val result: LinkLoadingResult,
        val index: Int?,
        val proxySession: LocalStreamProxyServer.ProxySession? = null
    ) {
        val isProxied: Boolean get() = proxySession != null
    }

    /**
     * Only local video/HLS sources are relayed. DASH manifests are not rewritten
     * by the relay; DRM links need player-specific license extras; torrents and
     * magnets are not HTTP media at all.
     */
    internal fun isRelayEligible(link: ExtractorLink?): Boolean {
        if (link == null || link.url.isBlank()) return false
        if (link is DrmExtractorLink) return false
        val isHttp = link.url.startsWith("http://", ignoreCase = true) ||
                link.url.startsWith("https://", ignoreCase = true)
        if (!isHttp) return false
        return link.type == ExtractorLinkType.M3U8 || link.type == ExtractorLinkType.VIDEO
    }

    /**
     * Prepares a link for an external player.
     *
     * Returns the original result/index when the selected source is not relay
     * eligible or needs no authentication (a plain public URL can stay direct).
     * When relayed, the returned result contains exactly one local link and local
     * subtitle URLs, so a player that expects `result.links[index]` keeps working.
     */
    suspend fun prepare(
        context: Context?,
        video: ResultEpisode,
        result: LinkLoadingResult,
        index: Int?
    ): PreparedPlayback {
        val selectedLink = index?.let { result.links.getOrNull(it) } ?: return PreparedPlayback(result, index)
        if (!isRelayEligible(selectedLink)) return PreparedPlayback(result, index)
        val link: ExtractorLink = selectedLink

        val request = try {
            ExternalPlaybackRequestBuilder.build(context, video, result, index)
        } catch (t: Throwable) {
            Log.w(TAG, "Could not build external playback request: ${t.message}")
            return PreparedPlayback(result, index)
        }

        if (request.playbackRoute() != ExternalPlaybackRoute.LOCAL_PROXY) {
            // Switching from a relayed playback to a clean direct source should
            // not leave the previous relay session and its service behind.
            stopActivePlayback(context)
            return PreparedPlayback(result, index)
        }

        val session = startProxySession(context, request)
        val relayedResult = buildRelayedResult(session, link, result)
        Log.d(
            TAG,
            "Prepared relayed ${link.type} '${link.name}' " +
                    "session=${session.sessionId.take(8)}"
        )
        return PreparedPlayback(relayedResult, 0, session)
    }

    /**
     * Starts a relay session and makes it the active external-playback session.
     * The foreground service keeps the loopback server alive while the external
     * player is in the foreground.
     */
    fun startProxySession(
        context: Context?,
        request: ExternalPlaybackRequest
    ): LocalStreamProxyServer.ProxySession {
        val session = try {
            LocalStreamProxyServer.startSession(request)
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to start local stream relay: ${t.message}")
            stopActivePlayback(context)
            throw IllegalStateException("Could not start authenticated stream relay", t)
        }

        // Start the new session first; stopping the old one only shuts the
        // listener down when no other session remains.
        stopActivePlayback(context)
        activeSessionId = session.sessionId

        val appContext = context?.applicationContext ?: context
        if (appContext != null) {
            if (!LocalStreamProxyService.start(appContext)) {
                Log.w(TAG, "Relay is running without a foreground service; " +
                        "Playback may be interrupted if the process is reclaimed")
            }
        }
        return session
    }

    internal suspend fun buildRelayedResult(
        session: LocalStreamProxyServer.ProxySession,
        link: ExtractorLink,
        result: LinkLoadingResult
    ): LinkLoadingResult {
        val relayedLink = newExtractorLink(
            source = link.source,
            name = link.name,
            url = LocalStreamProxyServer.createStreamUrl(session),
            type = link.type
        ) {
            quality = link.quality
            // Deliberately no upstream headers, referer or extractor data:
            // the relay owns credentials and the player only sees a local URL.
        }

        val relayedSubtitles = result.subs.mapIndexed { subtitleIndex, subtitle ->
            relaySubtitle(session, subtitleIndex, subtitle)
        }

        return result.copy(
            links = listOf(relayedLink),
            subs = relayedSubtitles
        )
    }

    internal fun relaySubtitle(
        session: LocalStreamProxyServer.ProxySession,
        index: Int,
        subtitle: SubtitleData
    ): SubtitleData {
        val isHttp = subtitle.url.startsWith("http://", ignoreCase = true) ||
                subtitle.url.startsWith("https://", ignoreCase = true)
        if (!isHttp || subtitle.origin == SubtitleOrigin.EMBEDDED_IN_VIDEO) {
            return subtitle
        }

        val externalSubtitle = ExternalSubtitle(
            url = subtitle.url,
            name = subtitle.name,
            languageCode = subtitle.languageCode,
            mimeType = subtitle.mimeType,
            headers = subtitle.headers
        )
        val relayedUrl = LocalStreamProxyServer.createSubtitleUrl(session, index, externalSubtitle)
        return subtitle.copy(url = relayedUrl, headers = emptyMap())
    }

    /**
     * Stops the active relay session, if any. Safe to call from every external
     * player result callback and after a failed launch.
     */
    fun stopActivePlayback(context: Context?) {
        val sessionId = activeSessionId ?: return
        activeSessionId = null
        LocalStreamProxyServer.stopSession(sessionId)

        val appContext = context?.applicationContext ?: context
        if (appContext != null && !LocalStreamProxyServer.hasSessions()) {
            LocalStreamProxyService.stop(appContext)
        }
        Log.d(TAG, "Stopped external playback session ${sessionId.take(8)}")
    }
}
