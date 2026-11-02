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
import com.lagradost.api.Log
import com.lagradost.cloudstream3.services.ExternalPlaybackNotificationListenerService
import com.lagradost.cloudstream3.ui.result.ResultEpisode

/**
 * Watches the Network Stream Player's MediaSession while a pending external
 * playback exists.
 *
 * Network Stream Player creates a platform MediaSession, calls setActive(true)
 * when its player connects, and publishes playback state/metadata. It never
 * calls Activity.setResult, so the MediaSession is the only reliable source of
 * position and duration.
 *
 * The tracker is started by [ExternalPlaybackNotificationListenerService]. It
 * does nothing unless the user granted notification access and enabled the
 * tracking preference.
 */
object ExternalPlaybackMediaSessionTracker {
    private const val TAG = "ExternalPlayback"
    const val POLL_INTERVAL_MS = 5_000L

    private val handler = Handler(Looper.getMainLooper())
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
    private var sessionManager: MediaSessionManager? = null
    private var listenerComponent: ComponentName? = null
    private var engine: ExternalPlaybackProgressEngine? = null
    private var resumeController: ExternalPlaybackResumeController? = null
    private var pendingSessionId: String? = null
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
            Log.w(TAG, "MediaSessionManager unavailable")
            return
        }
        sessionManager = manager
        listenerComponent = ComponentName(app, ExternalPlaybackNotificationListenerService::class.java)
        running = true
        scan()
        handler.removeCallbacks(pollRunnable)
        handler.postDelayed(pollRunnable, POLL_INTERVAL_MS)
    }

    fun stop() {
        running = false
        handler.removeCallbacks(pollRunnable)
        releaseControllers()
        engine = null
        resumeController = null
        pendingSessionId = null
        sessionManager = null
        listenerComponent = null
        appContext = null
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
     * RESULT_CANCELED. Empty/cancelled results only save the last observed
     * position; they never imply completion.
     */
    fun onActivityResult(resultCode: Int, data: Intent?) {
        val pending = ExternalPlaybackSessionStore.get() ?: return
        val tracker = engineFor(pending)
        val now = SystemClock.elapsedRealtime()
        val position = readLong(data, "position", "extra_position")
        val duration = readLong(data, "duration", "extra_duration")
        val update = tracker.onActivityResult(position, duration, now)
        if (update != null) {
            ExternalPlaybackSessionStore.saveProgress(pending, update)
        }
        Log.d(
            TAG,
            "External result code=$resultCode hasData=${data != null} " +
                    "position=$position duration=$duration completed=${update?.completed == true}"
        )
        ExternalPlaybackSessionStore.clear()
        resetEngine()
    }

    private fun scan() {
        val context = appContext ?: return
        if (!ExternalPlaybackTrackingSettings.isEnabled(context)) {
            if (pendingSessionId != null) {
                ExternalPlaybackSessionStore.clear()
            }
            resetEngine()
            return
        }

        val pending = ExternalPlaybackSessionStore.get()
        if (pending == null) {
            resetEngine()
            return
        }

        val tracker = engineFor(pending)
        val now = SystemClock.elapsedRealtime()

        if (tracker.onTick(now) == ExternalPlaybackExpired) {
            Log.d(TAG, "Pending external playback expired")
            ExternalPlaybackSessionStore.clear()
            resetEngine()
            return
        }

        val manager = sessionManager ?: return
        val component = listenerComponent ?: return
        val sessions = try {
            manager.getActiveSessions(component)
        } catch (t: SecurityException) {
            Log.w(TAG, "Notification access is not granted: ${t.message}")
            stop()
            return
        } catch (t: Throwable) {
            Log.w(TAG, "Could not read active MediaSessions: ${t.message}")
            return
        }

        val matching = sessions.filter { it.packageName == pending.packageName }
        val boundToken = tracker.boundSessionToken
        // Once bound, only that exact session may update this episode. If it
        // disappears, treat it as a session loss even if another Network
        // Stream Player session is visible.
        val usable = if (boundToken != null) {
            matching.filter { it.sessionToken.toString() == boundToken }
        } else {
            matching
        }
        if (usable.isEmpty()) {
            tracker.onSessionLost(now)?.let { update ->
                ExternalPlaybackSessionStore.saveProgress(pending, update)
            }
            return
        }

        val controller = chooseController(usable, tracker, pending)
        bindController(controller)
        val snapshot = snapshotOf(controller, now)
        val update = tracker.onSnapshot(snapshot, now)
        if (update != null) {
            ExternalPlaybackSessionStore.saveProgress(pending, update)
            if (update.clearPending) {
                ExternalPlaybackSessionStore.clear()
                resetEngine()
                return
            }
        }
        evaluateResumeSeek(controller, tracker, pending, snapshot, now)
    }

    /**
     * Network Stream Player has no verified launch-position extra. Resume is
     * performed through its MediaSession only after the session advertises a
     * valid duration and ACTION_SEEK_TO. The seek is issued once and then
     * confirmed by observing the reported position; failure is logged and never
     * pauses playback.
     */
    private fun evaluateResumeSeek(
        controller: MediaController,
        tracker: ExternalPlaybackProgressEngine,
        pending: PendingExternalPlayback,
        snapshot: ExternalPlaybackSnapshot,
        nowElapsedMs: Long,
    ) {
        val resume = resumeController ?: return
        when (val action = resume.onSnapshot(snapshot, tracker.boundSessionToken, nowElapsedMs)) {
            null -> return
            is ExternalPlaybackResumeAction.SeekTo -> {
                // Persist the one-shot flag before touching the player. If the
                // process dies after this point, a restarted tracker must not
                // seek again.
                val marked = ExternalPlaybackSessionStore.markResumeSeekAttempted(pending)
                if (marked == null) {
                    resume.onSeekAborted()
                    Log.w(TAG, "Resume seek aborted: pending session is no longer active")
                    return
                }
                try {
                    controller.transportControls.seekTo(action.positionMs)
                    Log.d(
                        TAG,
                        "Resume seek requested episode=${pending.episode.id} " +
                                "target=${action.positionMs}ms " +
                                "position=${snapshot.positionMs}ms " +
                                "duration=${snapshot.durationMs}ms actions=${snapshot.actions}"
                    )
                } catch (t: Throwable) {
                    resume.onSeekCommandFailed()
                    Log.w(
                        TAG,
                        "Resume seek failed episode=${pending.episode.id} " +
                                "target=${action.positionMs}ms: ${t.message}"
                    )
                }
            }

            ExternalPlaybackResumeAction.Confirmed -> {
                tracker.onResumeSeekConfirmed()
                Log.d(
                    TAG,
                    "Resume position confirmed episode=${pending.episode.id} " +
                            "target=${pending.initialPositionMs}ms " +
                            "reported=${resume.confirmedPositionMs}ms"
                )
            }

            ExternalPlaybackResumeAction.Failed -> {
                Log.w(
                    TAG,
                    "Automatic resume unavailable episode=${pending.episode.id} " +
                            "target=${pending.initialPositionMs}ms state=${resume.state}"
                )
            }
        }
    }

    private fun engineFor(pending: PendingExternalPlayback): ExternalPlaybackProgressEngine {
        val current = engine
        if (current != null && pendingSessionId == pending.sessionId) return current
        resetEngine()
        pendingSessionId = pending.sessionId
        return ExternalPlaybackProgressEngine(pending).also {
            engine = it
            resumeController = ExternalPlaybackResumeController(pending)
        }
    }

    private fun resetEngine() {
        releaseControllers()
        engine = null
        resumeController = null
        pendingSessionId = null
    }

    private fun chooseController(
        sessions: List<MediaController>,
        tracker: ExternalPlaybackProgressEngine,
        pending: PendingExternalPlayback,
    ): MediaController {
        val boundToken = tracker.boundSessionToken
        if (boundToken != null) {
            sessions.firstOrNull { it.sessionToken.toString() == boundToken }?.let { return it }
        }

        val expectedTitle = pending.episode.name ?: pending.episode.headerName
        return sessions.maxWithOrNull(
            compareBy<MediaController> { titleScore(it, expectedTitle) }
                .thenBy { it.playbackState?.lastPositionUpdateTime ?: 0L }
        ) ?: sessions.first()
    }

    private fun titleScore(controller: MediaController, expectedTitle: String?): Int {
        val actual = controller.metadata?.let { metadata ->
            metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
                ?: metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
        }?.trim()?.lowercase() ?: return 0
        val expected = expectedTitle?.trim()?.lowercase() ?: return 0
        if (actual.isEmpty() || expected.isEmpty()) return 0
        return when {
            actual == expected -> 2
            actual.contains(expected) || expected.contains(actual) -> 1
            else -> -1
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
