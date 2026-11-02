package com.lagradost.cloudstream3.actions

import android.content.Context
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.isLiveStream
import com.lagradost.cloudstream3.ui.player.SubtitleData
import com.lagradost.cloudstream3.ui.player.SubtitleOrigin
import com.lagradost.cloudstream3.ui.result.LinkLoadingResult
import com.lagradost.cloudstream3.ui.result.ResultEpisode
import com.lagradost.cloudstream3.ui.subtitles.SUBTITLE_AUTO_SELECT_KEY
import com.lagradost.cloudstream3.utils.CLEARKEY_DRM_UUID
import com.lagradost.cloudstream3.utils.DataStoreHelper.getVideoWatchState
import com.lagradost.cloudstream3.utils.DataStoreHelper.getViewPos
import com.lagradost.cloudstream3.utils.DrmExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.PLAYREADY_DRM_UUID
import com.lagradost.cloudstream3.utils.WIDEVINE_DRM_UUID

/**
 * Model representing an individual HLS quality variant representation.
 */
data class HlsVariant(
    val url: String,
    val quality: Int,
    val name: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val bandwidth: Long? = null,
    val resolution: String? = null,
    val codecs: String? = null
)

/**
 * How an external player should receive the stream.
 */
enum class ExternalPlaybackRoute {
    /** Hand the selected URL straight to the external player. */
    DIRECT,

    /** Serve the stream through [LocalStreamProxyServer]. */
    LOCAL_PROXY
}

/**
 * Unified model for external player requests, capturing resolved media URL,
 * headers, cookies, referer, origin, user-agent, subtitle, DRM metadata,
 * master playlist URL, and available quality variants.
 */
data class ExternalPlaybackRequest(
    val mediaUrl: String,
    val displayName: String? = null,
    val mimeType: String = "video/*",
    val linkType: ExtractorLinkType = ExtractorLinkType.VIDEO,
    val headers: Map<String, String> = emptyMap(),
    val referer: String? = null,
    val origin: String? = null,
    val cookie: String? = null,
    val userAgent: String? = null,
    val authorization: String? = null,
    val drmUuid: String? = null,
    val drmScheme: String? = null,
    val drmLicenseUrl: String? = null,
    val drmHeaders: Map<String, String> = emptyMap(),
    val subtitles: List<ExternalSubtitle> = emptyList(),
    val selectedSubtitle: ExternalSubtitle? = null,
    /** Verified resume target; 0 when playback must start at the beginning. */
    val positionMs: Long = 0L,
    /** Last durable position, retained even when it must not be resumed. */
    val savedPositionMs: Long = 0L,
    val isDrm: Boolean = false,
    val rawExtractorLink: ExtractorLink? = null,
    val masterUrl: String? = null,
    val masterHeaders: Map<String, String> = emptyMap(),
    val allVariants: List<HlsVariant> = emptyList()
) {
    /**
     * Deterministic direct-versus-proxy choice.
     *
     * Direct playback is used only when no authentication context is required.
     * Network Stream Player's own header UI does not prove that every intent
     * header extra is propagated to HLS variant playlists, segments, keys and
     * initialization maps, so any required header forces the local proxy. This
     * avoids silently handing the player a URL that it can only partially
     * authenticate.
     */
    fun playbackRoute(): ExternalPlaybackRoute {
        val hasCustomHeaders = !cookie.isNullOrBlank() ||
                !referer.isNullOrBlank() ||
                !origin.isNullOrBlank() ||
                !authorization.isNullOrBlank() ||
                headers.any { (key, value) ->
                    value.isNotBlank() && !key.equals("user-agent", ignoreCase = true)
                } ||
                masterHeaders.any { (_, value) -> value.isNotBlank() }

        if (hasCustomHeaders) return ExternalPlaybackRoute.LOCAL_PROXY

        // Per-resource credentials cannot be carried by a plain ACTION_VIEW
        // extra, so use the relay whenever a license or subtitle needs them.
        if (drmHeaders.any { (_, value) -> value.isNotBlank() }) {
            return ExternalPlaybackRoute.LOCAL_PROXY
        }
        if (subtitles.any { subtitle ->
                subtitle.headers.any { (_, value) -> value.isNotBlank() }
            }
        ) {
            return ExternalPlaybackRoute.LOCAL_PROXY
        }

        val hasNonDefaultUserAgent = !userAgent.isNullOrBlank() &&
                !userAgent.equals(USER_AGENT, ignoreCase = false)
        if (hasNonDefaultUserAgent) return ExternalPlaybackRoute.LOCAL_PROXY

        // Explicitly verified multi-variant requests need routing so that every
        // variant keeps its own headers. Single selected links stay direct.
        if (allVariants.size > 1) return ExternalPlaybackRoute.LOCAL_PROXY

        return ExternalPlaybackRoute.DIRECT
    }

    fun requiresProxy(): Boolean = playbackRoute() == ExternalPlaybackRoute.LOCAL_PROXY
}

data class ExternalSubtitle(
    val url: String,
    val name: String,
    val languageCode: String?,
    val mimeType: String,
    val headers: Map<String, String> = emptyMap(),
    val isSelected: Boolean = false
)

object ExternalPlaybackRequestBuilder {

    fun deriveResolution(quality: Int): String {
        return when (quality) {
            2160 -> "3840x2160"
            1440 -> "2560x1440"
            1080 -> "1920x1080"
            720 -> "1280x720"
            480 -> "854x480"
            360 -> "640x360"
            240 -> "426x240"
            144 -> "256x144"
            else -> {
                if (quality > 0) {
                    val width = ((quality * 16) / 9) / 2 * 2
                    "${width}x$quality"
                } else ""
            }
        }
    }

    fun deriveBandwidth(quality: Int): Long {
        return when (quality) {
            2160 -> 20_000_000L
            1440 -> 10_000_000L
            1080 -> 5_000_000L
            720 -> 2_500_000L
            480 -> 1_200_000L
            360 -> 800_000L
            240 -> 400_000L
            144 -> 200_000L
            else -> (quality.toLong() * quality.toLong() * 4L).coerceIn(200_000L, 30_000_000L)
        }
    }

    fun build(
        context: Context?,
        video: ResultEpisode,
        result: LinkLoadingResult,
        index: Int?
    ): ExternalPlaybackRequest {
        val linkIndex = index ?: 0
        val link = result.links.getOrNull(linkIndex)
            ?: throw IllegalArgumentException("No media link available for playback")

        val allHeaders = link.getAllHeaders()

        // Case-insensitive header lookups
        fun findHeader(name: String): String? {
            return allHeaders.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
        }

        val referer = findHeader("referer") ?: link.referer.ifBlank { null }
        val cookie = findHeader("cookie")
        val origin = findHeader("origin")
        val userAgent = findHeader("user-agent") ?: USER_AGENT
        val authorization = findHeader("authorization")

        val mimeType = when (link.type) {
            ExtractorLinkType.M3U8 -> "application/vnd.apple.mpegurl"
            ExtractorLinkType.DASH -> "application/dash+xml"
            ExtractorLinkType.VIDEO -> {
                val cleanUrl = link.url.substringBefore("?")
                when {
                    cleanUrl.endsWith(".m3u8", ignoreCase = true) -> "application/vnd.apple.mpegurl"
                    cleanUrl.endsWith(".mpd", ignoreCase = true) -> "application/dash+xml"
                    cleanUrl.endsWith(".mp4", ignoreCase = true) -> "video/mp4"
                    cleanUrl.endsWith(".mkv", ignoreCase = true) -> "video/x-matroska"
                    cleanUrl.endsWith(".webm", ignoreCase = true) -> "video/webm"
                    else -> "video/*"
                }
            }
            else -> "video/*"
        }

        // The selected ExtractorLink is the source of truth. A master URL is
        // recorded only when the selected link itself is an explicitly declared
        // master. Never replace the user's selection with an arbitrary sibling
        // link and never synthesize a master from separately extracted quality
        // links unless their compatibility has been verified.
        val isHlsStream = link.type == ExtractorLinkType.M3U8 || link.url.contains(".m3u8", ignoreCase = true)
        var masterUrl: String? = null
        var masterHeaders: Map<String, String> = emptyMap()

        if (isHlsStream) {
            val isExplicitMaster = link.quality == 0 ||
                    link.name.equals("Auto", ignoreCase = true) ||
                    link.name.equals("Master", ignoreCase = true) ||
                    link.name.startsWith("Auto ", ignoreCase = true) ||
                    link.name.startsWith("Master ", ignoreCase = true) ||
                    link.url.substringBefore("?").endsWith("/master.m3u8", ignoreCase = true)
            if (isExplicitMaster) {
                masterUrl = link.url
                masterHeaders = allHeaders
            }
        }

        // Subtitles
        val userPreferredLang = getKey<String>(SUBTITLE_AUTO_SELECT_KEY) ?: "en"
        val subtitles = result.subs
            .filter { it.origin != SubtitleOrigin.EMBEDDED_IN_VIDEO && it.url.isNotBlank() }
            .map { sub ->
                val isSelected = sub.languageCode == userPreferredLang ||
                        sub.matchesLanguageCode(userPreferredLang)
                ExternalSubtitle(
                    url = sub.url,
                    name = sub.name.trim().ifBlank { sub.originalName.trim() },
                    languageCode = sub.languageCode,
                    mimeType = sub.mimeType.ifBlank { "text/vtt" },
                    headers = sub.headers,
                    isSelected = isSelected
                )
            }

        val selectedSubtitle = subtitles.firstOrNull { it.isSelected } ?: subtitles.firstOrNull()

        // DRM
        var isDrm = false
        var drmUuid: String? = null
        var drmScheme: String? = null
        var drmLicenseUrl: String? = null
        var drmHeaders: Map<String, String> = emptyMap()

        if (link is DrmExtractorLink) {
            isDrm = true
            drmLicenseUrl = link.licenseUrl
            drmHeaders = link.keyRequestParameters
            val uuidStr = link.uuid.toString()
            drmUuid = uuidStr

            drmScheme = when {
                link.uuid == WIDEVINE_DRM_UUID || uuidStr.equals("edef8ba9-79d6-4ace-a3c8-27dcd51d21ed", ignoreCase = true) -> "widevine"
                link.uuid == PLAYREADY_DRM_UUID || uuidStr.equals("9a04f079-9840-4286-ab92-e65be0885f95", ignoreCase = true) -> "playready"
                link.uuid == CLEARKEY_DRM_UUID || uuidStr.equals("e2719d58-a985-b3c9-781a-b030af78d30e", ignoreCase = true) -> "clearkey"
                else -> uuidStr
            }
        }

        val savedPosDur = getViewPos(video.id)
        val savedPosition = savedPosDur?.position?.coerceAtLeast(0L) ?: 0L
        val savedDuration = savedPosDur?.duration?.takeIf { it > 0L } ?: video.duration
        val watchState = getVideoWatchState(video.id) ?: video.videoWatchState
        val resumePosition = ExternalPlaybackResumePolicy.resolveResumePositionMs(
            savedPositionMs = savedPosition,
            savedDurationMs = savedDuration,
            isLive = video.tvType.isLiveStream(),
            watchState = watchState,
        )
        val title = video.name ?: video.headerName

        return ExternalPlaybackRequest(
            mediaUrl = link.url,
            displayName = title,
            mimeType = mimeType,
            linkType = link.type,
            headers = allHeaders,
            referer = referer,
            origin = origin,
            cookie = cookie,
            userAgent = userAgent,
            authorization = authorization,
            drmUuid = drmUuid,
            drmScheme = drmScheme,
            drmLicenseUrl = drmLicenseUrl,
            drmHeaders = drmHeaders,
            subtitles = subtitles,
            selectedSubtitle = selectedSubtitle,
            positionMs = resumePosition,
            savedPositionMs = savedPosition,
            isDrm = isDrm,
            rawExtractorLink = link,
            masterUrl = masterUrl,
            masterHeaders = masterHeaders,
            allVariants = emptyList()
        )
    }
}
