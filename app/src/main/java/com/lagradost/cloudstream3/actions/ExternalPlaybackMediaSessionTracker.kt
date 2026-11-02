package com.lagradost.cloudstream3.actions

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationManagerCompat
import com.lagradost.api.Log
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.services.ExternalPlaybackNotificationListenerService

/**
 * Android adapter around [ExternalPlaybackTrackerCore].
 *
 * Network Stream Player creates an activity-scoped MediaSession and publishes
 * playback state/metadata. It never calls Activity.setResult, so the
 * MediaSession is the primary source of position and duration. Notification
 * access is the Android permission gate for reading active sessions.
 */
object ExternalPlaybackMediaSessionTracker {
    private const val TAG = "ExternalPlayback"
    const val POLL_INTERVAL_MS = 5_000L

    private val handler = Handler(Looper.getMainLooper())
    private val clock = object : ExternalPlaybackClock {
        override fun nowElapsedMs(): Long = SystemClock.elapsedRealtime()
    }
    private val pendingStore = object : ExternalPlaybackPendingStore {
        override fun get(): PendingExternalPlayback? = ExternalPlaybackSessionStore.get()
        override fun clear(reason: String) = ExternalPlaybackSessionStore.clear(reason)
        override fun markResumeSeekAttempted(
            pending: PendingExternalPlayback,
        ): PendingExternalPlayback? = ExternalPlaybackSessionStore.markResumeSeekAttempted(pending)

        override fun updateObserved(
            pending: PendingExternalPlayback,
            positionMs: Long,
            durationMs: Long,
        ): PendingExternalPlayback? =
            ExternalPlaybackSessionStore.updateObserved(pending, positionMs, durationMs)
    }
    private val progressSink = object : ExternalPlaybackProgressSink {
        override fun save(
            pending: PendingExternalPlayback,
            update: ExternalPlaybackUpdate,
        ): Boolean = ExternalPlaybackSessionStore.saveProgress(pending, update)
    }

    private val callback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            requestImmediateScan()
        }

        override fun onMetadataChanged(metadata: MediaMetadata?) {
            requestImmediateScan()
        }

        override fun onSessionDestroyed() {
            requestImmediateScan()
        }
    }

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (!running) return
            scan()
            handler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    private var running = false
    private var appContext: Context? = null
    private var source: AndroidMediaSessionSource? = null
    private var core: ExternalPlaybackTrackerCore? = null
    private var activeToken: String? = null
    private val controllers = mutableMapOf<String, MediaController>()

    fun start(context: Context) {
        val app = context.applicationContext
        appContext = app
        if (running) {
            scan()
            return
        }
        val manager = app.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
        if (manager == null) {
            Log.w(TAG, "MediaSessionManager unavailable; external tracking cannot start")
        }
        val component = ComponentName(app, ExternalPlaybackNotificationListenerService::class.java)
        val sessionSource = AndroidMediaSessionSource(manager, component, clock)
        source = sessionSource
        core = buildCore(app, sessionSource)
        running = true
        Log.d(
            TAG,
            "Tracker start manager=${manager != null} " +
                    "trackingEnabled=${ExternalPlaybackTrackingSettings.isEnabled(app)} " +
                    "notificationAccess=${hasNotificationAccess(app)}"
        )
        scan()
        handler.removeCallbacks(pollRunnable)
        handler.postDelayed(pollRunnable, POLL_INTERVAL_MS)
    }

    private fun buildCore(
        app: Context,
        sessionSource: ExternalPlaybackSessionSource,
    ): ExternalPlaybackTrackerCore {
        return ExternalPlaybackTrackerCore(
            sessionSource = sessionSource,
            pendingStore = pendingStore,
            progressSink = progressSink,
            clock = clock,
            isTrackingEnabled = { ExternalPlaybackTrackingSettings.isEnabled(app) },
            diagnostics = { Log.d(TAG, it) },
        )
    }

    fun stop(reason: String = "listener-disconnected") {
        running = false
        handler.removeCallbacks(pollRunnable)
        core?.reset(reason)
        core = null
        source = null
        releaseControllers()
        Log.d(TAG, "Tracker stop reason=$reason")
    }

    fun requestImmediateScan() {
        if (!running) return
        handler.removeCallbacks(pollRunnable)
        handler.post {
            if (!running) return@post
            scan()
            if (running) {
                handler.postDelayed(pollRunnable, POLL_INTERVAL_MS)
            }
        }
    }

    /**
     * Called for every Activity Result from Network Stream Player, including
     * RESULT_CANCELED. The result is matched to the oldest outstanding launch;
     * a stale result never clears a newer pending playback. Empty/cancelled
     * results only save the last observed position and never imply completion.
     */
    fun onActivityResult(resultCode: Int, data: Intent?): Boolean {
        val expectedSessionId = ExternalPlaybackSessionStore.consumeResultSessionId()
        val position = readLong(data, "position", "extra_position")
        val duration = readLong(data, "duration", "extra_duration")
        // The listener may have been disconnected while the external player was
        // open. Finalizing the matching marker must still work from the last
        // observed pending state, without notification access.
        val fallbackCore = core ?: (appContext ?: CloudStreamApp.context)?.let { app ->
            val manager = app.getSystemService(Context.MEDIA_SESSION_SERVICE)
                as? MediaSessionManager
            val component = ComponentName(
                app,
                ExternalPlaybackNotificationListenerService::class.java,
            )
            buildCore(app, AndroidMediaSessionSource(manager, component, clock))
        }
        val handled = fallbackCore?.onActivityResult(
            expectedSessionId,
            position,
            duration,
        ) ?: false
        Log.d(
            TAG,
            "External result code=$resultCode hasData=${data != null} " +
                    "expected=${expectedSessionId?.take(8)} position=$position duration=$duration " +
                    "handled=$handled tracked=${fallbackCore != null}"
        )
        if (handled) releaseControllers()
        return handled
    }

    private fun scan() {
        val current = core ?: return
        val outcome = current.scan()
        val token = current.boundSessionToken
        val controller = token?.let { source?.controllerFor(it) }
        if (controller != null) {
            bindController(controller)
        } else {
            releaseControllers()
        }
        if (outcome == ExternalPlaybackScanOutcome.PERMISSION_MISSING) {
            Log.w(TAG, "Notification access missing; tracking will recover after access is granted")
        }
    }

    private fun bindController(controller: MediaController) {
        val token = controller.sessionToken.toString()
        if (activeToken == token) return
        activeToken?.let { old ->
            controllers.remove(old)?.let { previous ->
                try {
                    previous.unregisterCallback(callback)
                } catch (_: Throwable) {
                }
            }
        }
        controllers[token] = controller
        activeToken = token
        try {
            controller.registerCallback(callback, handler)
            Log.d(TAG, "Bound controller token=${shortToken(token)} package=${controller.packageName}")
        } catch (t: Throwable) {
            Log.w(TAG, "Could not register MediaController callback: ${t.message}")
        }
    }

    private fun releaseControllers() {
        controllers.values.forEach { controller ->
            try {
                controller.unregisterCallback(callback)
            } catch (_: Throwable) {
            }
        }
        controllers.clear()
        activeToken = null
    }

    private fun hasNotificationAccess(context: Context): Boolean {
        return try {
            NotificationManagerCompat.getEnabledListenerPackages(context)
                .contains(context.packageName)
        } catch (_: Throwable) {
            false
        }
    }

    private fun shortToken(token: String?): String {
        if (token.isNullOrBlank()) return "none"
        return Integer.toHexString(token.hashCode())
    }

    private fun readLong(intent: Intent?, vararg keys: String): Long? {
        if (intent == null) return null
        for (key in keys) {
            if (intent.hasExtra(key)) {
                val longValue = try {
                    intent.getLongExtra(key, Long.MIN_VALUE)
                } catch (_: Throwable) {
                    Long.MIN_VALUE
                }
                if (longValue != Long.MIN_VALUE) return longValue

                val intValue = try {
                    intent.getIntExtra(key, Int.MIN_VALUE)
                } catch (_: Throwable) {
                    Int.MIN_VALUE
                }
                if (intValue != Int.MIN_VALUE) return intValue.toLong()
            }
        }
        return null
    }
}

private class AndroidMediaSessionSource(
    private val manager: MediaSessionManager?,
    private val listenerComponent: ComponentName,
    private val clock: ExternalPlaybackClock,
) : ExternalPlaybackSessionSource {
    private val controllers = mutableMapOf<String, MediaController>()

    override fun activeSessions(): List<ExternalPlaybackSession> {
        val sessionManager = manager
            ?: throw IllegalStateException("MediaSessionManager unavailable")
        val sessions = sessionManager.getActiveSessions(listenerComponent)
        val now = clock.nowElapsedMs()
        controllers.clear()
        return sessions.map { controller ->
            val token = controller.sessionToken.toString()
            controllers[token] = controller
            ExternalPlaybackSession(
                packageName = controller.packageName,
                sessionToken = token,
                snapshot = snapshotOf(controller, now),
                seekTo = { position -> controller.transportControls.seekTo(position) },
            )
        }
    }

    fun controllerFor(token: String): MediaController? = controllers[token]

    private fun snapshotOf(controller: MediaController, now: Long): ExternalPlaybackSnapshot {
        val state = controller.playbackState
        val metadata = controller.metadata
        return ExternalPlaybackSnapshot(
            packageName = controller.packageName,
            sessionToken = controller.sessionToken.toString(),
            state = state.toExternalState(),
            positionMs = state?.position ?: 0L,
            durationMs = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L,
            speed = state?.playbackSpeed ?: 1f,
            actions = state?.actions ?: 0L,
            lastPositionUpdateElapsedMs = state?.lastPositionUpdateTime ?: 0L,
            observedAtElapsedMs = now,
            title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
                ?: metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE),
            mediaId = metadata?.getString(MediaMetadata.METADATA_KEY_MEDIA_ID),
            queueItemId = state?.activeQueueItemId ?: -1L,
        )
    }

    private fun PlaybackState?.toExternalState(): ExternalPlaybackState {
        return when (this?.state) {
            PlaybackState.STATE_PLAYING -> ExternalPlaybackState.PLAYING
            PlaybackState.STATE_PAUSED -> ExternalPlaybackState.PAUSED
            PlaybackState.STATE_BUFFERING,
            PlaybackState.STATE_CONNECTING -> ExternalPlaybackState.BUFFERING
            PlaybackState.STATE_STOPPED -> ExternalPlaybackState.STOPPED
            PlaybackState.STATE_ERROR -> ExternalPlaybackState.ERROR
            PlaybackState.STATE_NONE, null -> ExternalPlaybackState.NONE
            else -> ExternalPlaybackState.IDLE
        }
    }
}
