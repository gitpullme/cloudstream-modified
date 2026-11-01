package com.lagradost.cloudstream3.actions.temp

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.lagradost.api.Log
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.actions.ExternalPlaybackMediaSessionTracker
import com.lagradost.cloudstream3.actions.ExternalPlaybackRequestBuilder
import com.lagradost.cloudstream3.actions.ExternalPlaybackSessionStore
import com.lagradost.cloudstream3.actions.NetworkStreamPlayerLauncher
import com.lagradost.cloudstream3.actions.OpenInAppAction
import com.lagradost.cloudstream3.ui.result.LinkLoadingResult
import com.lagradost.cloudstream3.ui.result.ResultEpisode
import com.lagradost.cloudstream3.utils.AppContextUtils.isAppInstalled
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.txt

/**
 * Integration with Network Stream (Video) Player (com.genuine.leone).
 * Handles video playback with full header forwarding, HLS playlist rewriting,
 * subtitle forwarding, and DRM configuration via local loopback proxying when required.
 */
class NetworkStreamPlayerPackage : OpenInAppAction(
    appName = txt(R.string.network_stream_player),
    packageName = NetworkStreamPlayerLauncher.PACKAGE_NAME
) {
    override val sourceTypes: Set<ExtractorLinkType> =
        setOf(ExtractorLinkType.VIDEO, ExtractorLinkType.M3U8, ExtractorLinkType.DASH)

    override val oneSource: Boolean = true


    override suspend fun runAction(
        context: Context?,
        video: ResultEpisode,
        result: LinkLoadingResult,
        index: Int?
    ) {
        if (context == null) return

        // 1. Independent package installation check
        if (!context.isAppInstalled(packageName)) {
            CommonActivity.showToast(R.string.app_not_found_error, Toast.LENGTH_LONG)
            return
        }

        // 2. Build external playback request from resolved link
        val request = try {
            ExternalPlaybackRequestBuilder.build(context, video, result, index)
        } catch (t: Throwable) {
            Log.e("NetworkStreamPlayer", "Failed to build playback request: ${t.message}")
            CommonActivity.showToast("Failed to prepare playback: ${t.message}", Toast.LENGTH_LONG)
            return
        }

        // 3. Create and configure intent atomically
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setPackage(packageName)
        }

        // 4. Launch intent and handle errors without misreporting missing package.
        // Persist the pending session before launch so it survives backgrounding.
        // Replacing any older marker prevents stale playback from leaking into
        // this launch.
        try {
            NetworkStreamPlayerLauncher.configureIntent(context, intent, request)
            ExternalPlaybackSessionStore.begin(context, video, request, packageName)
            setKey("last_opened", video)
            launchResult(intent)
        } catch (e: ActivityNotFoundException) {
            ExternalPlaybackSessionStore.clear()
            NetworkStreamPlayerLauncher.stopActivePlayback(context)
            Log.e("NetworkStreamPlayer", "ActivityNotFoundException for intent: $intent: ${e.message}")
            CommonActivity.showToast("Network Stream Player could not open this stream format", Toast.LENGTH_LONG)
        } catch (t: Throwable) {
            ExternalPlaybackSessionStore.clear()
            NetworkStreamPlayerLauncher.stopActivePlayback(context)
            Log.e("NetworkStreamPlayer", "Failed to launch Network Stream Player: ${t.message}")
            CommonActivity.showToast(t.message ?: "Failed to launch player", Toast.LENGTH_LONG)
        }
    }

    override suspend fun putExtra(
        context: Context,
        intent: Intent,
        video: ResultEpisode,
        result: LinkLoadingResult,
        index: Int?
    ) {
        val request = ExternalPlaybackRequestBuilder.build(context, video, result, index)
        NetworkStreamPlayerLauncher.configureIntent(context, intent, request)
    }

    /**
     * Network Stream Player never calls Activity.setResult, so this is normally
     * never invoked. Keep it safe: any verified position/duration data is
     * handled by the same tracker used for MediaSession updates.
     */
    override fun onResult(activity: Activity, intent: Intent?) {
        NetworkStreamPlayerLauncher.stopActivePlayback(activity)
    }

    override fun onExternalPlaybackReturned(resultCode: Int, intent: Intent?) {
        ExternalPlaybackMediaSessionTracker.onActivityResult(resultCode, intent)
    }
}
