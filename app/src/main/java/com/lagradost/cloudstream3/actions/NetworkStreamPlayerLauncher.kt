package com.lagradost.cloudstream3.actions

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.core.net.toUri
import com.lagradost.api.Log
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.utils.AppContextUtils.isAppInstalled

/**
 * Dedicated launcher helper for Network Stream (Video) Player (com.genuine.leone).
 *
 * Direct playback is used only when the selected stream needs no authentication
 * context. Whenever headers must reach HLS child requests the stream is served by
 * [LocalStreamProxyServer], and a foreground service keeps that proxy alive while
 * the external player is in the foreground.
 *
 * Network Stream Player's verified contract reads top-level name/origin/referer/
 * cookie/userAgent and DRM extras. Header propagation to HLS child resources
 * cannot be verified, so any required authentication context is routed through
 * [LocalStreamProxyServer]. The media-router HTTP_HEADERS bundle is kept only as
 * a best-effort extra for builds that may honor it.
 */
object NetworkStreamPlayerLauncher {
    const val PACKAGE_NAME = "com.genuine.leone"
    const val EXTRA_HTTP_HEADERS = "android.media.intent.extra.HTTP_HEADERS"
    private const val TAG = "NetworkStreamPlayer"

    /**
     * Verifies whether Network Stream Player is installed on the system.
     * Independent of media URI, proxy session, or MIME-specific resolution.
     */
    fun isInstalled(context: Context?): Boolean {
        if (context == null) return false
        return context.isAppInstalled(PACKAGE_NAME)
    }

    /**
     * Resolves the effective MIME type for Network Stream Player.
     * Tests whether the target package advertises an activity filter matching
     * the requested MIME type (e.g. application/vnd.apple.mpegurl), and gracefully
     * falls back to universal video wildcard to prevent false ActivityNotFoundExceptions.
     */
    fun resolveEffectiveMimeType(pm: PackageManager?, uri: Uri?, requestedMimeType: String?): String? {
        if (pm == null || uri == null) {
            return if (!requestedMimeType.isNullOrBlank()) requestedMimeType else "video/*"
        }

        // 1. If target resolves the specific requested MIME type, use it
        if (!requestedMimeType.isNullOrBlank() && requestedMimeType != "video/*") {
            if (canResolve(pm, uri, requestedMimeType)) {
                return requestedMimeType
            }
        }

        // 2. Check universal video wildcard which matches standard video player intent filters
        if (canResolve(pm, uri, "video/*")) {
            return "video/*"
        }

        // 3. Check if target can resolve raw URI without MIME type
        if (canResolve(pm, uri, null)) {
            return null
        }

        // 4. Default fallback: universal video wildcard
        return "video/*"
    }

    private fun canResolve(pm: PackageManager, uri: Uri?, mimeType: String?): Boolean {
        if (uri == null) return false
        return try {
            val testIntent = Intent(Intent.ACTION_VIEW).apply {
                setPackage(PACKAGE_NAME)
                if (mimeType != null) {
                    setDataAndType(uri, mimeType)
                } else {
                    data = uri
                }
            }
            testIntent.resolveActivity(pm) != null
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Builds the documented Android HTTP header bundle. Only used for an initial
     * direct request; proxied playback never exposes upstream credentials to the
     * external player process.
     */
    fun buildHttpHeadersBundle(request: ExternalPlaybackRequest): Bundle {
        val headers = Bundle()
        fun put(name: String, value: String?) {
            if (!value.isNullOrBlank() && !headers.containsKey(name)) {
                headers.putString(name, value)
            }
        }

        for ((key, value) in request.headers) {
            put(key, value)
        }
        put("User-Agent", request.userAgent)
        put("Referer", request.referer)
        put("Origin", request.origin)
        put("Cookie", request.cookie)
        put("Authorization", request.authorization)
        return headers
    }

    /**
     * Configures the target Intent atomically for Network Stream Player.
     * Ensures data URI is never overwritten or wiped by MIME type selection.
     */
    fun configureIntent(
        context: Context,
        intent: Intent,
        request: ExternalPlaybackRequest
    ) {
        val proxyServer = LocalStreamProxyServer
        val route = request.playbackRoute()

        val proxySession = if (route == ExternalPlaybackRoute.LOCAL_PROXY) {
            // Do not silently fall back to direct playback: direct playback cannot
            // carry the credentials this stream requires to child resources.
            val session = try {
                ExternalPlaybackSupport.startProxySession(context, request)
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to start local stream proxy: ${t.message}")
                throw IllegalStateException("Could not start authenticated stream relay", t)
            }
            Log.d(
                TAG,
                "Playback route=LOCAL_PROXY session=${session.sessionId.take(8)} " +
                        "direct=${request.mediaUrl.substringBefore('?').take(40)}"
            )
            session
        } else {
            ExternalPlaybackSupport.stopActivePlayback(context)
            Log.d(TAG, "Playback route=DIRECT")
            null
        }

        val playableUrl = if (proxySession != null) {
            proxyServer.createStreamUrl(proxySession)
        } else {
            request.masterUrl ?: request.mediaUrl
        }

        // 1. Data URI and MIME Type (set atomically so neither wipes the other)
        val uri = playableUrl.toUri()
        val pm = try {
            context.packageManager
        } catch (_: Throwable) {
            null
        }
        val targetMimeType = resolveEffectiveMimeType(pm, uri, request.mimeType)
        if (targetMimeType != null) {
            intent.setDataAndType(uri, targetMimeType)
        } else {
            intent.data = uri
        }

        // 2. Title and Playback Metadata. Network Stream Player reads the
        // top-level "name" extra (verified in PlayerActivity.onCreate). The
        // generic title extras are kept for other/older builds.
        request.displayName?.let { title ->
            intent.putExtra("name", title)
            intent.putExtra("title", title)
            intent.putExtra(Intent.EXTRA_TITLE, title)
        }

        // 3. Playback Position.
        // Network Stream Player's verified intent contract has no position
        // extra. PlayerActivity only reads "position" from its saved-instance
        // Bundle, not from Intent extras, and it never calls setResult.
        // Automatic resume is therefore performed by the MediaSession tracker:
        // once the new session reports a valid duration and ACTION_SEEK_TO, it
        // seeks once to the saved position. Until that seek is confirmed, the
        // progress engine refuses to overwrite the saved position with 0:00.

        // 4. HTTP Headers. Direct playback never requires authentication context;
        // proxied playback keeps credentials inside the proxy process.
        if (proxySession == null) {
            val headersBundle = buildHttpHeadersBundle(request)
            if (!headersBundle.isEmpty) {
                intent.putExtra(EXTRA_HTTP_HEADERS, headersBundle)
            }
        }

        // 5. Subtitles (only if subtitles are present)
        val subtitleUris = if (proxySession != null && request.subtitles.isNotEmpty()) {
            request.subtitles.mapIndexed { idx, sub ->
                proxyServer.createSubtitleUrl(proxySession, idx, sub).toUri()
            }
        } else {
            request.subtitles.map { it.url.toUri() }
        }

        if (subtitleUris.isNotEmpty()) {
            intent.putExtra("subs", subtitleUris.toTypedArray())
            intent.putExtra("subs.name", request.subtitles.map { it.name }.toTypedArray())
            intent.putExtra("subs.enable", true)

            val selectedSub = request.selectedSubtitle ?: request.subtitles.firstOrNull()
            val selectedIndex = if (selectedSub != null) request.subtitles.indexOf(selectedSub) else -1
            val selectedUri = if (selectedIndex >= 0 && selectedIndex < subtitleUris.size) {
                subtitleUris[selectedIndex]
            } else {
                subtitleUris.firstOrNull()
            }
            if (selectedUri != null) {
                intent.putExtra("subtitles_location", selectedUri.toString())
            }
        }

        // 6. DRM Configuration (only if stream is DRM-protected)
        if (request.isDrm) {
            request.drmScheme?.let { scheme ->
                intent.putExtra("drmScheme", scheme)
                intent.putExtra("drm_scheme", scheme)
            }
            request.drmLicenseUrl?.let { licenseUrl ->
                val effectiveLicenseUrl = if (proxySession != null) {
                    proxyServer.createLicenseUrl(proxySession)
                } else {
                    licenseUrl
                }
                intent.putExtra("drmLicense", effectiveLicenseUrl)
                intent.putExtra("drm_license_url", effectiveLicenseUrl)
            }
        }
    }

    /**
     * Called when the external player returns a result. Stops the active proxy
     * session and foreground service without touching any newer session.
     */
    fun stopActivePlayback(context: Context?) {
        ExternalPlaybackSupport.stopActivePlayback(context)
    }

    /**
     * Builds the Intent for Network Stream Player from an ExternalPlaybackRequest.
     */
    fun createIntent(
        context: Context,
        request: ExternalPlaybackRequest
    ): Intent {
        val intent = Intent(Intent.ACTION_VIEW)
        intent.setPackage(PACKAGE_NAME)
        configureIntent(context, intent, request)
        return intent
    }

    suspend fun launch(activity: Activity?, request: ExternalPlaybackRequest): Boolean {
        if (activity == null) return false
        if (!isInstalled(activity)) {
            CommonActivity.showToast(R.string.app_not_found_error, Toast.LENGTH_LONG)
            return false
        }
        return try {
            val intent = createIntent(activity, request)
            MainActivity.activityResultLauncher?.launch(intent) ?: run {
                activity.startActivity(intent)
            }
            true
        } catch (e: ActivityNotFoundException) {
            stopActivePlayback(activity)
            Log.e(TAG, "ActivityNotFoundException for Network Stream Player intent: ${e.message}")
            CommonActivity.showToast("Network Stream Player could not open this stream format", Toast.LENGTH_LONG)
            false
        } catch (t: Throwable) {
            stopActivePlayback(activity)
            Log.e(TAG, "Failed to launch Network Stream Player: ${t.message}")
            CommonActivity.showToast(t.message ?: "Failed to launch player", Toast.LENGTH_LONG)
            false
        }
    }
}
