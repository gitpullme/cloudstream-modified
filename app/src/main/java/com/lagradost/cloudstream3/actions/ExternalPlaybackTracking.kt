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
    /** Latest observed position, retained when MediaSession duration is missing. */
    @SerialName("lastObservedPositionMs") val lastObservedPositionMs: Long = 0L,
    @SerialName("lastObservedDurationMs") val lastObservedDurationMs: Long = 0L,
    /**
     * True only after the tracker has actually observed playback. It separates
     * a real observation from the initial resume seed stored by [begin].
     */
    @SerialName("hasObservedPlayback") val hasObservedPlayback: Boolean = false,
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
    /** Strong media identity when the player publishes it. */
    val mediaId: String? = null,
    /** PlaybackState.activeQueueItemId, or -1 when unknown. */
    val queueItemId: Long = -1L,
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
    /**
     * Genuine watched media time observed in this process, logged for
     * diagnostics only. CloudStream's existing history model has no cumulative
     * watch-time field, and this integration does not invent a parallel store.
     */
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
    private val candidateGraceMs: Long = DEFAULT_CANDIDATE_GRACE_MS,
) {
    companion object {
        const val DEFAULT_SAVE_INTERVAL_MS = 5_000L
        const val DEFAULT_MIN_POSITION_DELTA_MS = 1_000L
        const val DEFAULT_MAX_SAMPLE_GAP_MS = 15_000L
        const val DEFAULT_SESSION_GRACE_MS = 30_000L
        const val DEFAULT_SEEK_TOLERANCE_MS = 3_000L
        const val DEFAULT_RESUME_GUARD_MS = 5_000L
        const val DEFAULT_UNBOUND_EXPIRY_MS = 120_000L
        const val DEFAULT_CANDIDATE_GRACE_MS = 60_000L
        const val COMPLETION_PERCENT = 95L
    }

    var boundSessionToken: String? = null
        private set

    var watchedContentMs: Long = 0L
        private set

    var lastKnownPositionMs: Long =
        pending.lastObservedPositionMs.takeIf { it > 0L }
            ?: pending.initialPositionMs.coerceAtLeast(0L)
        private set

    var lastKnownDurationMs: Long =
        pending.lastObservedDurationMs.takeIf { it > 0L }
            ?: pending.expectedDurationMs.coerceAtLeast(0L)
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

    var lastEffectivePositionMs: Long? = null
        private set

    var lastDecision: String = "not-started"
        private set

    var lastTitleMismatchDescription: String? = null
        private set

    var titleMismatchDetected: Boolean = false
        private set

    /**
     * Timestamp when a candidate session was first seen but ambiguity prevented
     * binding. While candidates are present the unbound expiry timer is extended
     * by [candidateGraceMs] from this point. The timer still fires eventually
     * so tracking cannot stay alive forever.
     */
    var candidateFirstSeenElapsedMs: Long? = null
        private set

    /**
     * Whether the most recent scan found candidate sessions (ambiguous or not).
     * Set externally by the tracker core after each scan.
     */
    var hasCandidates: Boolean = false

    private var lastObservedElapsedMs: Long? = null
    private var lastSpeed: Float = 1f
    private var lastReportedPositionMs: Long? = null
    private var lastPositionUpdateElapsedMs: Long = 0L
    private var boundTitle: String? = pending.episode.name?.trim()?.takeIf { it.isNotBlank() }
    var boundMediaId: String? = null
        private set
    var boundQueueItemId: Long = -1L
        private set

    fun onSnapshot(
        snapshot: ExternalPlaybackSnapshot,
        nowElapsedMs: Long = snapshot.observedAtElapsedMs,
    ): ExternalPlaybackUpdate? {
        lastDecision = "evaluating"
        if (snapshot.packageName != pending.packageName) {
            lastDecision = "ignored-package"
            return null
        }
        if (nowElapsedMs < pending.startedAtElapsedRealtimeMs) {
            lastDecision = "ignored-before-launch"
            return null
        }

        val previousObserved = lastObservedElapsedMs
        if (previousObserved != null && nowElapsedMs < previousObserved) {
            lastDecision = "ignored-time-regression"
            return null
        }

        // A session that was already updating before this launch belongs to the
        // previous playback. Ignore it until it produces a fresh state.
        if (boundSessionToken == null &&
            snapshot.lastPositionUpdateElapsedMs > 0L &&
            snapshot.lastPositionUpdateElapsedMs < pending.startedAtElapsedRealtimeMs - 1_000L
        ) {
            lastDecision = "ignored-stale-session"
            return null
        }

        if (boundSessionToken == null) {
            // Do not bind a controller that has not published anything yet.
            // Otherwise an empty session could keep the pending marker alive
            // forever without ever producing progress.
            if (snapshot.state == ExternalPlaybackState.NONE && snapshot.durationMs <= 0L) {
                lastDecision = "ignored-empty-session"
                return null
            }
            bind(snapshot, nowElapsedMs)
            lastDecision = "bound"
            return null
        }

        if (boundSessionToken != snapshot.sessionToken) {
            lastDecision = "ignored-token"
            return null
        }

        // Out-of-order callbacks are ignored; never let an old position win.
        if (snapshot.lastPositionUpdateElapsedMs > 0L &&
            snapshot.lastPositionUpdateElapsedMs < lastPositionUpdateElapsedMs
        ) {
            lastDecision = "ignored-stale-update"
            return null
        }

        // A real item transition needs a strong identifier. A title change
        // alone is only a diagnostic: NSP reformats titles freely and may use
        // filenames, provider prefixes or URL-derived strings.
        genuineMediaChangeReason(snapshot)?.let { reason ->
            lastDecision = "media-changed:$reason"
            return mediaChangedUpdate(nowElapsedMs)
        }
        updateBoundIdentity(snapshot)

        val snapshotTitle = snapshot.title?.trim()?.takeIf { it.isNotBlank() }
        val currentTitle = boundTitle
        if (!ExternalPlaybackTitleMatcher.isLikelySame(currentTitle, snapshotTitle)) {
            val description = "expected=${currentTitle?.take(80)} actual=${snapshotTitle?.take(80)}"
            lastTitleMismatchDescription = description
            titleMismatchDetected = true
            lastDecision = "title-mismatch-continue"
        }
        if (snapshotTitle != null &&
            !ExternalPlaybackTitleMatcher.looksLikeUrl(snapshotTitle) &&
            (boundTitle == null || ExternalPlaybackTitleMatcher.looksLikeUrl(boundTitle))
        ) {
            // Prefer a human-readable title over a URL once the player
            // publishes one, but never treat either as media identity.
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
        lastReportedPositionMs = snapshot.positionMs.coerceAtLeast(0L)
        lastSpeed = snapshot.speed
        lastKnownState = snapshot.state
        lastKnownPositionMs = effectivePosition
        if (duration > 0L) lastKnownDurationMs = duration
        lastPositionUpdateElapsedMs =
            max(lastPositionUpdateElapsedMs, snapshot.lastPositionUpdateElapsedMs)
        sessionLostAtElapsedMs = null

        val position = safePosition(effectivePosition, duration)
        if (duration <= 0L) {
            lastDecision = "no-duration"
            return null
        }
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
        if (mustPreserveResume(position)) {
            lastDecision = "resume-guard"
            return null
        }
        if (!force && snapshot.state != ExternalPlaybackState.PLAYING) {
            lastDecision = "not-playing"
            return null
        }
        if (!force && !intervalElapsed) {
            lastDecision = "interval-not-elapsed"
            return null
        }
        if (!force && !positionChanged) {
            lastDecision = "position-unchanged"
            return null
        }

        lastPersistAtElapsedMs = nowElapsedMs
        lastPersistedPositionMs = position
        lastDecision = "persisted-${immediateReason ?: ExternalPlaybackSaveReason.PERIODIC}"
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
     * Rebind a replacement MediaSession without resetting the episode's
     * confirmed position or watched-content counter. The new snapshot becomes
     * the baseline so the gap between sessions is not counted as watched.
     */
    fun rebindSession(
        snapshot: ExternalPlaybackSnapshot,
        nowElapsedMs: Long = snapshot.observedAtElapsedMs,
    ) {
        boundSessionToken = snapshot.sessionToken
        // Do not extrapolate across the gap between the old and new session.
        // The new snapshot is the baseline; confirmed progress stays intact.
        val position = clampToDuration(
            snapshot.positionMs.coerceAtLeast(0L),
            snapshot.durationMs.takeIf { it > 0L } ?: lastKnownDurationMs,
        )
        lastObservedElapsedMs = nowElapsedMs
        lastEffectivePositionMs = position
        lastReportedPositionMs = snapshot.positionMs.coerceAtLeast(0L)
        lastSpeed = snapshot.speed
        lastKnownState = snapshot.state
        lastKnownPositionMs = position
        if (snapshot.durationMs > 0L) lastKnownDurationMs = snapshot.durationMs
        lastPositionUpdateElapsedMs = snapshot.lastPositionUpdateElapsedMs
        sessionLostAtElapsedMs = null
        boundMediaId = snapshot.mediaId?.takeIf { it.isNotBlank() }
        boundQueueItemId = snapshot.queueItemId
        lastDecision = "rebound"
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
     * Called when the durable write failed. The next tick may retry the same
     * position instead of treating it as already persisted.
     */
    fun onPersistFailed() {
        lastPersistedPositionMs = -1L
    }

    /**
     * Called when no matching MediaSession is currently visible. The first call
     * saves the last known position immediately; later calls only start the
     * grace timer that eventually clears the pending launch marker.
     */
    fun onSessionLost(nowElapsedMs: Long): ExternalPlaybackUpdate? {
        if (boundSessionToken == null && !pending.hasObservedPlayback) {
            lastDecision = "session-lost-unobserved"
            return null
        }
        if (sessionLostAtElapsedMs == null) {
            sessionLostAtElapsedMs = nowElapsedMs
        }
        val duration = lastKnownDurationMs
        if (duration <= 0L) {
            lastDecision = "session-lost-no-duration"
            return null
        }
        val position = safePosition(lastKnownPositionMs, duration)
        if (mustPreserveResume(position)) {
            lastDecision = "session-lost-resume-guard"
            return null
        }
        val changed = lastPersistedPositionMs < 0L ||
                abs(position - lastPersistedPositionMs) >= minPositionDeltaMs
        if (!changed) {
            lastDecision = "session-lost-unchanged"
            return null
        }

        lastPersistedPositionMs = position
        lastPersistAtElapsedMs = nowElapsedMs
        lastDecision = "persisted-session-lost"
        return ExternalPlaybackUpdate(
            positionMs = position,
            durationMs = duration,
            watchedContentMs = watchedContentMs,
            state = ExternalPlaybackState.STOPPED,
            reason = ExternalPlaybackSaveReason.SESSION_LOST,
            immediate = true,
            // Closing the player or losing its session is not completion
            // evidence. Completion is only reported from an observed snapshot.
            completed = false,
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
            lastDecision = "persisted-activity-result"
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
            lastDecision = "expired-session-grace"
            return ExternalPlaybackExpired
        }
        if (boundSessionToken == null && !pending.hasObservedPlayback &&
            nowElapsedMs - pending.startedAtElapsedRealtimeMs >= unboundExpiryMs
        ) {
            // If candidates are present (ambiguous), extend the grace period.
            if (hasCandidates) {
                val firstSeen = candidateFirstSeenElapsedMs
                if (firstSeen == null) {
                    // First time we notice candidates — record the timestamp.
                    candidateFirstSeenElapsedMs = nowElapsedMs
                    lastDecision = "waiting-ambiguous-candidates"
                    return null
                }
                // Give ambiguous candidates a bounded resolution window.
                if (nowElapsedMs - firstSeen < candidateGraceMs) {
                    lastDecision = "waiting-ambiguous-candidates"
                    return null
                }
                // Grace period exhausted — still ambiguous, expire.
                lastDecision = "expired-unbound-ambiguous"
                return ExternalPlaybackExpired
            }
            lastDecision = "expired-unbound"
            return ExternalPlaybackExpired
        }
        lastDecision = "waiting"
        return null
    }

    private fun bind(snapshot: ExternalPlaybackSnapshot, nowElapsedMs: Long) {
        boundSessionToken = snapshot.sessionToken
        val position = effectivePosition(snapshot, nowElapsedMs)
        lastObservedElapsedMs = nowElapsedMs
        lastEffectivePositionMs = position
        lastReportedPositionMs = snapshot.positionMs.coerceAtLeast(0L)
        lastSpeed = snapshot.speed
        lastKnownState = snapshot.state
        lastKnownPositionMs = position
        if (snapshot.durationMs > 0L) lastKnownDurationMs = snapshot.durationMs
        lastPositionUpdateElapsedMs = snapshot.lastPositionUpdateElapsedMs
        // Start the write cadence at bind time; the first periodic save should
        // happen after the normal interval, not on the next callback.
        lastPersistAtElapsedMs = nowElapsedMs
        sessionLostAtElapsedMs = null
        boundMediaId = snapshot.mediaId?.takeIf { it.isNotBlank() }
        boundQueueItemId = snapshot.queueItemId
        if (boundTitle == null) {
            boundTitle = snapshot.title?.trim()?.takeIf { it.isNotBlank() }
        }
    }

    /**
     * Extrapolates the current media position.
     *
     * If the MediaSession publishes a usable update timestamp, use that
     * timestamp and the reported speed. If it does not, anchor the estimate to
     * the previous effective position and the wall-clock time between scans.
     * The fallback is only used across two continuous PLAYING observations, so
     * paused/buffering gaps and long gaps are never counted as watched time.
     */
    private fun effectivePosition(snapshot: ExternalPlaybackSnapshot, nowElapsedMs: Long): Long {
        val base = snapshot.positionMs.coerceAtLeast(0L)
        val knownDuration = snapshot.durationMs.takeIf { it > 0L } ?: lastKnownDurationMs
        if (snapshot.state != ExternalPlaybackState.PLAYING) {
            return clampToDuration(base, knownDuration)
        }

        val speed = snapshot.speed.takeIf { it > 0f } ?: 1f
        val timestamp = snapshot.lastPositionUpdateElapsedMs
        val estimated = if (timestamp > 0L) {
            val elapsed = (nowElapsedMs - timestamp).coerceIn(0L, maxSampleGapMs)
            base + (elapsed * speed).toLong()
        } else {
            val previousPosition = lastEffectivePositionMs
            val previousObserved = lastObservedElapsedMs
            val previousState = lastKnownState
            val previousReported = lastReportedPositionMs
            if (previousPosition != null && previousObserved != null &&
                previousState == ExternalPlaybackState.PLAYING
            ) {
                val wallDelta = (nowElapsedMs - previousObserved).coerceIn(0L, maxSampleGapMs)
                val expectedDelta = (wallDelta * lastSpeed.coerceAtLeast(0f)).toLong()
                val expectedPosition = previousPosition + expectedDelta
                if (previousReported != null && base == previousReported) {
                    // The player republishes the same static position without a
                    // timestamp. It is not a seek; continue from the last
                    // effective position, never from the repeated raw value.
                    max(base, expectedPosition)
                } else {
                    val expectedReported = (previousReported ?: base) + expectedDelta
                    val difference = base - expectedReported
                    if (abs(difference) > seekToleranceMs + expectedDelta / 2L) {
                        // The player reported a seek. Use the reported position
                        // and never count the skipped content as watched.
                        base
                    } else {
                        max(base, expectedPosition)
                    }
                }
            } else {
                // First observation without a timestamp: do not invent time.
                base
            }
        }
        return clampToDuration(estimated, knownDuration)
    }

    private fun clampToDuration(position: Long, duration: Long): Long {
        if (duration <= 0L) return position.coerceAtLeast(0L)
        return position.coerceIn(0L, duration)
    }

    private fun genuineMediaChangeReason(snapshot: ExternalPlaybackSnapshot): String? {
        val mediaId = snapshot.mediaId?.takeIf { it.isNotBlank() }
        if (boundMediaId != null && mediaId != null && boundMediaId != mediaId) {
            return "media-id"
        }
        if (boundQueueItemId >= 0L && snapshot.queueItemId >= 0L &&
            boundQueueItemId != snapshot.queueItemId
        ) {
            return "queue-item"
        }
        return null
    }

    private fun updateBoundIdentity(snapshot: ExternalPlaybackSnapshot) {
        if (boundMediaId == null) {
            boundMediaId = snapshot.mediaId?.takeIf { it.isNotBlank() }
        }
        if (boundQueueItemId < 0L && snapshot.queueItemId >= 0L) {
            boundQueueItemId = snapshot.queueItemId
        }
    }

    /**
     * A genuine transition preserves the last confirmed progress for the
     * episode that was launched, then clears the pending marker. It never
     * attributes the next item's position to the selected episode.
     */
    private fun mediaChangedUpdate(nowElapsedMs: Long): ExternalPlaybackUpdate {
        val duration = lastKnownDurationMs.coerceAtLeast(0L)
        val position = safePosition(lastKnownPositionMs, duration)
        if (duration > 0L) {
            lastPersistedPositionMs = position
            lastPersistAtElapsedMs = nowElapsedMs
        }
        return ExternalPlaybackUpdate(
            positionMs = position,
            durationMs = duration,
            watchedContentMs = watchedContentMs,
            state = lastKnownState,
            reason = ExternalPlaybackSaveReason.MEDIA_CHANGED,
            immediate = true,
            completed = isCompleted(position, duration),
            sessionEnded = true,
            clearPending = true,
        )
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

}

private const val RESUME_OVERRIDE_WATCH_MS = 60_000L

/**
 * Indirection that lets tests verify the production persistence path without
 * touching Android storage. Production always delegates to [DataStoreHelper].
 */
fun interface ExternalPlaybackHistoryWriter {
    fun write(pending: PendingExternalPlayback, update: ExternalPlaybackUpdate)
}

/**
 * Durable launch marker. SharedPreferences/DataStore is used only for this
 * short lived marker; episode progress itself always goes through
 * [DataStoreHelper].
 */
object ExternalPlaybackSessionStore {
    private const val TAG = "ExternalPlayback"
    private const val PENDING_KEY = "external_playback_pending"
    private const val RESULT_QUEUE_KEY = "external_playback_result_queue"
    private const val MAX_PENDING_AGE_MS = 12 * 60 * 60 * 1_000L
    private const val MAX_RESULT_QUEUE = 4

    fun begin(
        context: Context?,
        video: ResultEpisode,
        request: ExternalPlaybackRequest,
        packageName: String,
    ): PendingExternalPlayback? {
        val appContext = context?.applicationContext ?: CloudStreamApp.context ?: return null
        clear("replaced-by-new-launch")
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
            lastObservedPositionMs = maxOf(request.savedPositionMs, video.position)
                .coerceAtLeast(0L),
            lastObservedDurationMs = video.duration.coerceAtLeast(0L),
        )
        appContext.setKey(PENDING_KEY, pending)
        pushResultSessionId(pending.sessionId)
        Log.d(
            TAG,
            "Pending playback ${pending.sessionId.take(8)} episode=${video.id} " +
                    "resume=${pending.initialPositionMs}ms duration=${pending.expectedDurationMs}ms"
        )
        return pending
    }

    private fun pushResultSessionId(sessionId: String) {
        val appContext = CloudStreamApp.context ?: return
        val current = getKey<String>(RESULT_QUEUE_KEY).orEmpty()
        val ids = current.split(',').filter { it.isNotBlank() }.toMutableList()
        ids.add(sessionId)
        while (ids.size > MAX_RESULT_QUEUE) ids.removeAt(0)
        appContext.setKey(RESULT_QUEUE_KEY, ids.joinToString(","))
    }

    /**
     * One Activity Result belongs to the oldest outstanding external launch.
     * Returning null means there is no launch that can be finalized safely.
     */
    fun consumeResultSessionId(): String? {
        val appContext = CloudStreamApp.context ?: return null
        val ids = getKey<String>(RESULT_QUEUE_KEY).orEmpty()
            .split(',').filter { it.isNotBlank() }.toMutableList()
        if (ids.isEmpty()) return null
        val first = ids.removeAt(0)
        if (ids.isEmpty()) {
            removeKey(RESULT_QUEUE_KEY)
        } else {
            appContext.setKey(RESULT_QUEUE_KEY, ids.joinToString(","))
        }
        return first
    }

    fun get(): PendingExternalPlayback? {
        val pending = getKey<PendingExternalPlayback>(PENDING_KEY) ?: return null
        val age = System.currentTimeMillis() - pending.startedAtWallClockMs
        val elapsedNow = SystemClock.elapsedRealtime()
        if (age < 0L || age > MAX_PENDING_AGE_MS ||
            elapsedNow < pending.startedAtElapsedRealtimeMs
        ) {
            clear("expired-invalid")
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

    /**
     * Retains the latest observed position/duration in the short-lived launch
     * marker. This is not a second history database; it is used when the
     * external metadata temporarily omits duration and to recover tracker state
     * after a service recreation.
     */
    fun updateObserved(
        pending: PendingExternalPlayback,
        positionMs: Long,
        durationMs: Long,
    ): PendingExternalPlayback? {
        val current = get() ?: return null
        if (current.sessionId != pending.sessionId) return null
        val position = positionMs.coerceAtLeast(0L)
        // Never replace a valid duration with a transient zero.
        val duration = durationMs.takeIf { it > 0L } ?: current.lastObservedDurationMs
        if (current.lastObservedPositionMs == position &&
            current.lastObservedDurationMs == duration &&
            current.hasObservedPlayback
        ) {
            return current
        }
        val updated = current.copy(
            lastObservedPositionMs = position,
            lastObservedDurationMs = duration,
            hasObservedPlayback = true,
        )
        val appContext = CloudStreamApp.context ?: return null
        appContext.setKey(PENDING_KEY, updated)
        return updated
    }

    /**
     * Removes the launch marker after a launch failed before the external
     * player was opened. Also removes only that launch's queued Activity
     * Result; a newer marker must never be finalized by an old result.
     */
    fun abandon(pending: PendingExternalPlayback) {
        val current = get()
        if (current?.sessionId == pending.sessionId) {
            clear("launch-failed")
        }
        removeResultSessionId(pending.sessionId)
    }

    private fun removeResultSessionId(sessionId: String) {
        val appContext = CloudStreamApp.context ?: return
        val ids = getKey<String>(RESULT_QUEUE_KEY).orEmpty()
            .split(',').filter { it.isNotBlank() && it != sessionId }
        if (ids.isEmpty()) {
            removeKey(RESULT_QUEUE_KEY)
        } else {
            appContext.setKey(RESULT_QUEUE_KEY, ids.joinToString(","))
        }
    }

    /**
     * The pending marker and the Activity Result queue have different
     * lifetimes. A result belongs to the launch that created it, so replacing
     * or expiring the marker must not make an old result look like the current
     * launch.
     */
    fun clear(reason: String) {
        Log.d(TAG, "Clearing pending playback: $reason")
        removeKey(PENDING_KEY)
    }

    fun clear() {
        clear("unspecified")
    }

    const val MIN_HISTORY_DURATION_MS = 30_000L

    fun saveProgress(
        pending: PendingExternalPlayback,
        update: ExternalPlaybackUpdate,
        writer: ExternalPlaybackHistoryWriter = dataStoreHistoryWriter,
    ): Boolean {
        val episodeId: Int? = pending.episode.id
        if (episodeId == null) {
            Log.w(
                TAG,
                "Cannot save progress: episode id is null for episode=${pending.episode.name ?: pending.episode.headerName}"
            )
            return false
        }
        if (update.durationMs < MIN_HISTORY_DURATION_MS) {
            Log.w(
                TAG,
                "Cannot save progress episode=$episodeId: duration ${update.durationMs}ms is below ${MIN_HISTORY_DURATION_MS}ms minimum"
            )
            return false
        }
        try {
            // Reuse the exact internal-player storage and 90% resume rules.
            writer.write(pending, update)
            Log.d(
                TAG,
                "Saved external progress episode=$episodeId " +
                        "pos=${update.positionMs}/${update.durationMs} " +
                        "watched=${update.watchedContentMs} reason=${update.reason} " +
                        "completed=${update.completed}"
            )
            return true
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to save external progress episode=$episodeId: ${t.message}")
            return false
        }
    }

    private val dataStoreHistoryWriter = ExternalPlaybackHistoryWriter { pending, update ->
        DataStoreHelper.setViewPosAndResume(
            id = pending.episode.id,
            position = update.positionMs,
            duration = update.durationMs,
            currentEpisode = pending.episode,
            nextEpisode = null,
        )
        Handler(Looper.getMainLooper()).post {
            ResultFragment.updateUI()
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
