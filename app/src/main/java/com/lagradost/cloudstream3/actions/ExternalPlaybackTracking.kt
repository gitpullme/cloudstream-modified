package com.lagradost.cloudstream3.actions

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.preference.PreferenceManager
import com.lagradost.api.Log
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.removeKey
import com.lagradost.cloudstream3.ui.result.ResultEpisode
import com.lagradost.cloudstream3.utils.DataStore.setKey
import com.lagradost.cloudstream3.ui.result.ResultFragment
import com.lagradost.cloudstream3.utils.DataStoreHelper
import com.lagradost.cloudstream4.EXTERNAL_PLAYER_TRACKING_ENABLED
import java.security.MessageDigest
import java.util.UUID
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Tracking state for a single external-player launch.
 *
 * This is deliberately *not* a second watch-history database. It is a short
 * lived launch marker that lets the MediaSession monitor attribute playback
 * state to the exact CloudStream episode that was opened. All durable progress
 * is written through [DataStoreHelper] exactly like the internal player.
 */
@Serializable
data class PendingExternalPlayback(
    @SerialName("sessionId") val sessionId: String,
    @SerialName("packageName") val packageName: String,
    @SerialName("episode") val episode: ResultEpisode,
    /** Opaque hash of the stream URL; never the URL itself (it may contain tokens). */
    @SerialName("streamIdentifier") val streamIdentifier: String,
    /** Position that may be resumed; 0 when the episode is completed/live. */
    @SerialName("initialPositionMs") val initialPositionMs: Long,
    /** Last durable position, even when it must not be resumed. */
    @SerialName("existingPositionMs") val existingPositionMs: Long = 0L,
    @SerialName("expectedDurationMs") val expectedDurationMs: Long,
    @SerialName("startedAtWallClockMs") val startedAtWallClockMs: Long,
    @SerialName("startedAtElapsedRealtimeMs") val startedAtElapsedRealtimeMs: Long,
    /** Persisted so a process restart cannot issue the resume seek twice. */
    @SerialName("resumeSeekAttempted") val resumeSeekAttempted: Boolean = false,
)

enum class ExternalPlaybackState {
    NONE,
    IDLE,
    BUFFERING,
    PLAYING,
    PAUSED,
    STOPPED,
    ERROR,
}

/**
 * Immutable snapshot of a MediaSession at one point in time.
 *
 * Unit tests build these directly; no Android MediaSession, URL or device is
 * required to exercise the tracking rules.
 */
data class ExternalPlaybackSnapshot(
    val packageName: String,
    val sessionToken: String,
    val state: ExternalPlaybackState,
    val positionMs: Long,
    val durationMs: Long,
    val speed: Float = 1f,
    /** MediaSession.PlaybackState.actions (ACTION_SEEK_TO = 256). */
    val actions: Long = 0L,
    /** MediaSession.PlaybackState.lastPositionUpdateTime (elapsedRealtime). */
    val lastPositionUpdateElapsedMs: Long = 0L,
    val observedAtElapsedMs: Long,
    val title: String? = null,
)

enum class ExternalPlaybackSaveReason {
    PERIODIC,
    PAUSE,
    BUFFERING,
    STOP,
    SESSION_LOST,
    ACTIVITY_RESULT,
    MEDIA_CHANGED,
}

data class ExternalPlaybackUpdate(
    val positionMs: Long,
    val durationMs: Long,
    val watchedContentMs: Long,
    val state: ExternalPlaybackState,
    val reason: ExternalPlaybackSaveReason,
    val immediate: Boolean,
    val completed: Boolean,
    val sessionEnded: Boolean,
    val clearPending: Boolean,
)

data object ExternalPlaybackExpired

/**
 * Pure progress engine. Android code only feeds it snapshots and persists the
 * updates it emits.
 *
 * Rules:
 *  - only [ExternalPlaybackState.PLAYING] contributes to watch time;
 *  - watch time follows media time (so 2x playback counts 2x content time);
 *  - jumps that do not match the expected media-time delta are treated as
 *    seeks and never counted as watched content;
 *  - a session is bound once and other tokens are ignored;
 *  - periodic writes are throttled; pause/stop/session loss are immediate;
 *  - a saved position is never overwritten by the 0:00 start this player uses
 *    before the MediaSession resume seek is confirmed (or before enough genuine
 *    viewing has accumulated when the seek is unsupported).
 */
class ExternalPlaybackProgressEngine(
    private val pending: PendingExternalPlayback,
    private val saveIntervalMs: Long = DEFAULT_SAVE_INTERVAL_MS,
    private val minPositionDeltaMs: Long = DEFAULT_MIN_POSITION_DELTA_MS,
    private val maxSampleGapMs: Long = DEFAULT_MAX_SAMPLE_GAP_MS,
    private val sessionGraceMs: Long = DEFAULT_SESSION_GRACE_MS,
    private val seekToleranceMs: Long = DEFAULT_SEEK_TOLERANCE_MS,
    private val resumeGuardMs: Long = DEFAULT_RESUME_GUARD_MS,
    private val unboundExpiryMs: Long = DEFAULT_UNBOUND_EXPIRY_MS,
) {
    companion object {
        const val DEFAULT_SAVE_INTERVAL_MS = 5_000L
        const val DEFAULT_MIN_POSITION_DELTA_MS = 1_000L
        const val DEFAULT_MAX_SAMPLE_GAP_MS = 15_000L
        const val DEFAULT_SESSION_GRACE_MS = 30_000L
        const val DEFAULT_SEEK_TOLERANCE_MS = 3_000L
        const val DEFAULT_RESUME_GUARD_MS = 5_000L
        const val DEFAULT_UNBOUND_EXPIRY_MS = 120_000L
        const val COMPLETION_PERCENT = 95L
    }

    var boundSessionToken: String? = null
        private set

    var watchedContentMs: Long = 0L
        private set

    var lastKnownPositionMs: Long = pending.initialPositionMs.coerceAtLeast(0L)
        private set

    var lastKnownDurationMs: Long = pending.expectedDurationMs.coerceAtLeast(0L)
        private set

    var lastKnownState: ExternalPlaybackState = ExternalPlaybackState.NONE
        private set

    var sessionLostAtElapsedMs: Long? = null
        private set

    var lastPersistedPositionMs: Long = -1L
        private set

    var lastPersistAtElapsedMs: Long = Long.MIN_VALUE
        private set

    var completed: Boolean = false
        private set

    var resumeSeekConfirmed: Boolean = false
        private set

    private var lastObservedElapsedMs: Long? = null
    private var lastEffectivePositionMs: Long? = null
    private var lastSpeed: Float = 1f
    private var lastPositionUpdateElapsedMs: Long = 0L
    private var boundTitle: String? = pending.episode.name?.trim()?.takeIf { it.isNotBlank() }

    fun onSnapshot(
        snapshot: ExternalPlaybackSnapshot,
        nowElapsedMs: Long = snapshot.observedAtElapsedMs,
    ): ExternalPlaybackUpdate? {
        if (snapshot.packageName != pending.packageName) return null
        if (nowElapsedMs < pending.startedAtElapsedRealtimeMs) return null

        val previousObserved = lastObservedElapsedMs
        if (previousObserved != null && nowElapsedMs < previousObserved) return null

        // A session that was already updating before this launch belongs to the
        // previous playback. Ignore it until it produces a fresh state.
        if (boundSessionToken == null &&
            snapshot.lastPositionUpdateElapsedMs > 0L &&
            snapshot.lastPositionUpdateElapsedMs < pending.startedAtElapsedRealtimeMs - 1_000L
        ) {
            return null
        }

        if (boundSessionToken == null) {
            // Do not bind a controller that has not published anything yet.
            // Otherwise an empty session could keep the pending marker alive
            // forever without ever producing progress.
            if (snapshot.state == ExternalPlaybackState.NONE && snapshot.durationMs <= 0L) {
                return null
            }
            bind(snapshot, nowElapsedMs)
            return null
        }

        if (boundSessionToken != snapshot.sessionToken) return null

        // Out-of-order callbacks are ignored; never let an old position win.
        if (snapshot.lastPositionUpdateElapsedMs > 0L &&
            snapshot.lastPositionUpdateElapsedMs < lastPositionUpdateElapsedMs
        ) {
            return null
        }

        val snapshotTitle = snapshot.title?.trim()?.takeIf { it.isNotBlank() }
        val currentTitle = boundTitle
        // Titles can be URL file names. Only compare two human-readable
        // titles; otherwise a URL-derived metadata title would look like a
        // different media item and stop valid tracking.
        if (currentTitle != null && snapshotTitle != null &&
            !looksLikeUrl(currentTitle) && !looksLikeUrl(snapshotTitle) &&
            !titlesMatch(currentTitle, snapshotTitle)
        ) {
            // The external player moved to another media item. We cannot map
            // that item to a CloudStream episode id, so stop instead of
            // attributing its progress to the episode that was launched.
            val position = safePosition(lastKnownPositionMs, lastKnownDurationMs)
            return buildUpdate(
                positionMs = position,
                durationMs = lastKnownDurationMs,
                nowElapsedMs = nowElapsedMs,
                reason = ExternalPlaybackSaveReason.MEDIA_CHANGED,
                immediate = true,
                sessionEnded = true,
                clearPending = true,
            )
        }
        if (boundTitle == null && snapshotTitle != null && !looksLikeUrl(snapshotTitle)) {
            boundTitle = snapshotTitle
        }

        val effectivePosition = effectivePosition(snapshot, nowElapsedMs)
        val duration = snapshot.durationMs.takeIf { it > 0L } ?: lastKnownDurationMs
        val previousPosition = lastEffectivePositionMs
        val previousState = lastKnownState
        val previousElapsed = lastObservedElapsedMs
        val previousSpeed = lastSpeed

        if (previousPosition != null && previousElapsed != null &&
            previousState == ExternalPlaybackState.PLAYING
        ) {
            val wallDelta = (nowElapsedMs - previousElapsed).coerceIn(0L, maxSampleGapMs)
            val expectedDelta = (wallDelta * previousSpeed.coerceAtLeast(0f)).toLong()
            val positionDelta = effectivePosition - previousPosition
            val seek = abs(positionDelta - expectedDelta) > seekToleranceMs + expectedDelta / 2L
            if (!seek && positionDelta > 0L) {
                watchedContentMs += min(positionDelta, expectedDelta + seekToleranceMs)
                    .coerceAtLeast(0L)
            }
        }

        lastObservedElapsedMs = nowElapsedMs
        lastEffectivePositionMs = effectivePosition
        lastSpeed = snapshot.speed
        lastKnownState = snapshot.state
        lastKnownPositionMs = effectivePosition
        if (duration > 0L) lastKnownDurationMs = duration
        lastPositionUpdateElapsedMs =
            max(lastPositionUpdateElapsedMs, snapshot.lastPositionUpdateElapsedMs)
        sessionLostAtElapsedMs = null

        val position = safePosition(effectivePosition, duration)
        val completedNow = isCompleted(position, duration)
        if (completedNow) completed = true

        val immediateReason = when (snapshot.state) {
            ExternalPlaybackState.PAUSED -> ExternalPlaybackSaveReason.PAUSE
            ExternalPlaybackState.BUFFERING -> ExternalPlaybackSaveReason.BUFFERING
            ExternalPlaybackState.STOPPED,
            ExternalPlaybackState.IDLE,
            ExternalPlaybackState.ERROR,
            ExternalPlaybackState.NONE -> ExternalPlaybackSaveReason.STOP

            ExternalPlaybackState.PLAYING -> null
        }

        val force = immediateReason != null
        val intervalElapsed = lastPersistAtElapsedMs == Long.MIN_VALUE ||
                nowElapsedMs - lastPersistAtElapsedMs >= saveIntervalMs
        val positionChanged = lastPersistedPositionMs < 0L ||
                abs(position - lastPersistedPositionMs) >= minPositionDeltaMs
        val resumeGuardAllows = !mustPreserveResume(position)

        val shouldPersist = duration > 0L &&
                resumeGuardAllows &&
                (force || (snapshot.state == ExternalPlaybackState.PLAYING &&
                        intervalElapsed && positionChanged))

        if (!shouldPersist) return null

        lastPersistAtElapsedMs = nowElapsedMs
        lastPersistedPositionMs = position
        return ExternalPlaybackUpdate(
            positionMs = position,
            durationMs = duration,
            watchedContentMs = watchedContentMs,
            state = snapshot.state,
            reason = immediateReason ?: ExternalPlaybackSaveReason.PERIODIC,
            immediate = force,
            completed = completedNow,
            sessionEnded = snapshot.state == ExternalPlaybackState.STOPPED ||
                    snapshot.state == ExternalPlaybackState.IDLE ||
                    snapshot.state == ExternalPlaybackState.ERROR ||
                    snapshot.state == ExternalPlaybackState.NONE,
            clearPending = false,
        )
    }

    /**
     * The MediaSession reported a position at or past the resume target after
     * the seek request. From this point on, normal progress may overwrite the
     * old saved position.
     */
    fun onResumeSeekConfirmed() {
        resumeSeekConfirmed = true
    }

    /**
     * Called when no matching MediaSession is currently visible. The first call
     * saves the last known position immediately; later calls only start the
     * grace timer that eventually clears the pending launch marker.
     */
    fun onSessionLost(nowElapsedMs: Long): ExternalPlaybackUpdate? {
        if (boundSessionToken == null) return null
        if (sessionLostAtElapsedMs == null) {
            sessionLostAtElapsedMs = nowElapsedMs
        }
        val duration = lastKnownDurationMs
        if (duration <= 0L) return null
        val position = safePosition(lastKnownPositionMs, duration)
        if (mustPreserveResume(position)) return null
        val changed = lastPersistedPositionMs < 0L ||
                abs(position - lastPersistedPositionMs) >= minPositionDeltaMs
        if (!changed) return null

        lastPersistedPositionMs = position
        lastPersistAtElapsedMs = nowElapsedMs
        return ExternalPlaybackUpdate(
            positionMs = position,
            durationMs = duration,
            watchedContentMs = watchedContentMs,
            state = ExternalPlaybackState.STOPPED,
            reason = ExternalPlaybackSaveReason.SESSION_LOST,
            immediate = true,
            completed = isCompleted(position, duration),
            sessionEnded = true,
            clearPending = false,
        )
    }

    /**
     * Activity Result fallback. Network Stream Player does not call
     * Activity.setResult, so normally both values are null. Empty results are
     * never interpreted as completion.
     */
    fun onActivityResult(
        positionMs: Long?,
        durationMs: Long?,
        nowElapsedMs: Long,
    ): ExternalPlaybackUpdate? {
        val validPosition = positionMs?.takeIf { it >= 0L }
        val validDuration = durationMs?.takeIf { it > 0L }
        if (validPosition != null && validDuration != null && validPosition <= validDuration &&
            !mustPreserveResume(validPosition)
        ) {
            val completedNow = isCompleted(validPosition, validDuration)
            if (completedNow) completed = true
            lastKnownPositionMs = validPosition
            lastKnownDurationMs = validDuration
            lastPersistedPositionMs = validPosition
            lastPersistAtElapsedMs = nowElapsedMs
            return ExternalPlaybackUpdate(
                positionMs = validPosition,
                durationMs = validDuration,
                watchedContentMs = watchedContentMs,
                state = ExternalPlaybackState.STOPPED,
                reason = ExternalPlaybackSaveReason.ACTIVITY_RESULT,
                immediate = true,
                completed = completedNow,
                sessionEnded = true,
                clearPending = true,
            )
        }

        // No usable result data: save what was actually observed, but never
        // infer completion from the player simply closing.
        return onSessionLost(nowElapsedMs)?.copy(
            reason = ExternalPlaybackSaveReason.ACTIVITY_RESULT,
            clearPending = true,
        )
    }

    /** Returns [ExternalPlaybackExpired] when the pending marker must be dropped. */
    fun onTick(nowElapsedMs: Long): ExternalPlaybackExpired? {
        val lostAt = sessionLostAtElapsedMs
        if (lostAt != null && nowElapsedMs - lostAt >= sessionGraceMs) {
            return ExternalPlaybackExpired
        }
        if (boundSessionToken == null &&
            nowElapsedMs - pending.startedAtElapsedRealtimeMs >= unboundExpiryMs
        ) {
            return ExternalPlaybackExpired
        }
        return null
    }

    private fun bind(snapshot: ExternalPlaybackSnapshot, nowElapsedMs: Long) {
        boundSessionToken = snapshot.sessionToken
        val position = effectivePosition(snapshot, nowElapsedMs)
        lastObservedElapsedMs = nowElapsedMs
        lastEffectivePositionMs = position
        lastSpeed = snapshot.speed
        lastKnownState = snapshot.state
        lastKnownPositionMs = position
        if (snapshot.durationMs > 0L) lastKnownDurationMs = snapshot.durationMs
        lastPositionUpdateElapsedMs = snapshot.lastPositionUpdateElapsedMs
        // Start the write cadence at bind time; the first periodic save should
        // happen after the normal interval, not on the next callback.
        lastPersistAtElapsedMs = nowElapsedMs
        sessionLostAtElapsedMs = null
        if (boundTitle == null) {
            boundTitle = snapshot.title?.trim()?.takeIf { it.isNotBlank() }
        }
    }

    private fun effectivePosition(snapshot: ExternalPlaybackSnapshot, nowElapsedMs: Long): Long {
        val base = snapshot.positionMs.coerceAtLeast(0L)
        if (snapshot.state != ExternalPlaybackState.PLAYING) return base
        val speed = snapshot.speed.takeIf { it > 0f } ?: 1f
        val anchor = snapshot.lastPositionUpdateElapsedMs.takeIf { it > 0L }
            ?: snapshot.observedAtElapsedMs
        val elapsed = (nowElapsedMs - anchor).coerceAtLeast(0L)
        val estimated = base + (elapsed * speed).toLong()
        return if (snapshot.durationMs > 0L) {
            estimated.coerceAtMost(snapshot.durationMs)
        } else {
            estimated
        }
    }

    private fun safePosition(position: Long, duration: Long): Long {
        if (duration <= 0L) return position.coerceAtLeast(0L)
        return position.coerceIn(0L, duration)
    }

    private fun isCompleted(position: Long, duration: Long): Boolean {
        if (duration <= 0L) return false
        return position * 100L / duration >= COMPLETION_PERCENT
    }

    /**
     * Network Stream Player has no launch-position extra. Until the MediaSession
     * seek is confirmed, do not overwrite a saved position with the 0:00 (or
     * otherwise earlier) position the player starts from. This also protects a
     * completed episode when the user restarts it externally.
     *
     * After a minute of genuine viewing the new position is accepted, so a user
     * who intentionally restarts can still make progress even if the automatic
     * seek was unsupported.
     */
    private fun mustPreserveResume(position: Long): Boolean {
        val existingPosition = maxOf(pending.existingPositionMs, pending.initialPositionMs)
        if (existingPosition <= 0L) return false
        if (resumeSeekConfirmed) return false
        if (watchedContentMs >= RESUME_OVERRIDE_WATCH_MS) return false
        val threshold = (existingPosition - resumeGuardMs).coerceAtLeast(1L)
        return position < threshold
    }

    private fun titlesMatch(a: String, b: String): Boolean {
        val left = normalizeTitle(a)
        val right = normalizeTitle(b)
        if (left.isEmpty() || right.isEmpty()) return true
        return left == right || left.contains(right) || right.contains(left)
    }

    private fun looksLikeUrl(value: String): Boolean {
        val lower = value.lowercase()
        return lower.startsWith("http://") ||
                lower.startsWith("https://") ||
                lower.startsWith("rtmp") ||
                lower.startsWith("file://") ||
                lower.contains("/")
    }

    private fun normalizeTitle(value: String): String {
        return value.lowercase()
            .substringBeforeLast('.')
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
    }

    private fun buildUpdate(
        positionMs: Long,
        durationMs: Long,
        nowElapsedMs: Long,
        reason: ExternalPlaybackSaveReason,
        immediate: Boolean,
        sessionEnded: Boolean,
        clearPending: Boolean,
    ): ExternalPlaybackUpdate? {
        if (durationMs <= 0L) return null
        val position = safePosition(positionMs, durationMs)
        lastPersistedPositionMs = position
        lastPersistAtElapsedMs = nowElapsedMs
        return ExternalPlaybackUpdate(
            positionMs = position,
            durationMs = durationMs,
            watchedContentMs = watchedContentMs,
            state = lastKnownState,
            reason = reason,
            immediate = immediate,
            completed = isCompleted(position, durationMs),
            sessionEnded = sessionEnded,
            clearPending = clearPending,
        )
    }
}

private const val RESUME_OVERRIDE_WATCH_MS = 60_000L

/**
 * Durable launch marker. SharedPreferences/DataStore is used only for this
 * short lived marker; episode progress itself always goes through
 * [DataStoreHelper].
 */
object ExternalPlaybackSessionStore {
    private const val TAG = "ExternalPlayback"
    private const val PENDING_KEY = "external_playback_pending"
    private const val MAX_PENDING_AGE_MS = 12 * 60 * 60 * 1_000L

    fun begin(
        context: Context?,
        video: ResultEpisode,
        request: ExternalPlaybackRequest,
        packageName: String,
    ): PendingExternalPlayback? {
        val appContext = context?.applicationContext ?: CloudStreamApp.context ?: return null
        clear()
        val pending = PendingExternalPlayback(
            sessionId = UUID.randomUUID().toString(),
            packageName = packageName,
            episode = video,
            streamIdentifier = streamIdentifier(request),
            initialPositionMs = request.positionMs.coerceAtLeast(0L),
            existingPositionMs = request.savedPositionMs.coerceAtLeast(0L),
            expectedDurationMs = video.duration.coerceAtLeast(0L),
            startedAtWallClockMs = System.currentTimeMillis(),
            startedAtElapsedRealtimeMs = SystemClock.elapsedRealtime(),
        )
        appContext.setKey(PENDING_KEY, pending)
        Log.d(TAG, "Pending external playback ${pending.sessionId.take(8)} episode=${video.id}")
        return pending
    }

    fun get(): PendingExternalPlayback? {
        val pending = getKey<PendingExternalPlayback>(PENDING_KEY) ?: return null
        val age = System.currentTimeMillis() - pending.startedAtWallClockMs
        val elapsedNow = SystemClock.elapsedRealtime()
        if (age < 0L || age > MAX_PENDING_AGE_MS ||
            elapsedNow < pending.startedAtElapsedRealtimeMs
        ) {
            clear()
            return null
        }
        return pending
    }

    /**
     * Persist that the one permitted resume seek has been issued. If the
     * process dies after this point the new tracker must not seek again.
     * Returns null when the marker is gone or belongs to another launch.
     */
    fun markResumeSeekAttempted(pending: PendingExternalPlayback): PendingExternalPlayback? {
        val current = get() ?: return null
        if (current.sessionId != pending.sessionId) return null
        if (current.resumeSeekAttempted) return current
        val updated = current.copy(resumeSeekAttempted = true)
        val appContext = CloudStreamApp.context ?: return null
        appContext.setKey(PENDING_KEY, updated)
        return updated
    }

    fun clear() {
        removeKey(PENDING_KEY)
    }

    fun saveProgress(pending: PendingExternalPlayback, update: ExternalPlaybackUpdate) {
        if (update.durationMs <= 0L) return
        try {
            // Reuse the exact internal-player storage and 90% resume rules.
            DataStoreHelper.setViewPosAndResume(
                id = pending.episode.id,
                position = update.positionMs,
                duration = update.durationMs,
                currentEpisode = pending.episode,
                nextEpisode = null,
            )
            Log.d(
                TAG,
                "Saved external progress episode=${pending.episode.id} " +
                        "pos=${update.positionMs}/${update.durationMs} " +
                        "watched=${update.watchedContentMs} reason=${update.reason} " +
                        "completed=${update.completed}"
            )
            Handler(Looper.getMainLooper()).post {
                ResultFragment.updateUI()
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to save external progress: ${t.message}")
        }
    }

    private fun streamIdentifier(request: ExternalPlaybackRequest): String {
        val material = buildString {
            append(request.mediaUrl)
            request.masterUrl?.let { append('|').append(it) }
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(material.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }
            .take(24)
    }
}

/** Reads the user-visible tracking preference from the same SharedPreferences. */
object ExternalPlaybackTrackingSettings {
    const val KEY_ENABLED = EXTERNAL_PLAYER_TRACKING_ENABLED

    fun isEnabled(context: Context): Boolean {
        return PreferenceManager.getDefaultSharedPreferences(context)
            .getBoolean(KEY_ENABLED, true)
    }
}
