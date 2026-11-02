package com.lagradost.cloudstream3.actions

import com.lagradost.cloudstream3.isLiveStream
import com.lagradost.cloudstream3.ui.result.VideoWatchState
import kotlin.math.max
import kotlin.math.min

/**
 * Decides whether a saved episode position may be resumed externally.
 *
 * This mirrors the internal player's completion rule: an episode at or above
 * 95% is treated as completed and is not resumed. Live streams and episodes
 * explicitly marked watched are never resumed.
 */
object ExternalPlaybackResumePolicy {
    const val COMPLETION_PERCENT = ExternalPlaybackProgressEngine.COMPLETION_PERCENT

    fun resolveResumePositionMs(
        savedPositionMs: Long,
        savedDurationMs: Long,
        isLive: Boolean,
        watchState: VideoWatchState,
    ): Long {
        if (isLive) return 0L
        if (watchState == VideoWatchState.Watched) return 0L
        if (savedPositionMs <= 0L || savedDurationMs <= 0L) return 0L
        if (savedPositionMs >= savedDurationMs) return 0L
        val percentage = savedPositionMs.toDouble() / savedDurationMs.toDouble()
        if (percentage >= COMPLETION_PERCENT.toDouble() / 100.0) return 0L
        return savedPositionMs
    }
}

enum class ExternalPlaybackResumeState {
    NOT_NEEDED,
    WAITING_FOR_MEDIA,
    SEEK_REQUESTED,
    CONFIRMED,
    FAILED,
    ALREADY_ATTEMPTED,
}

sealed interface ExternalPlaybackResumeAction {
    data class SeekTo(val positionMs: Long) : ExternalPlaybackResumeAction
    data object Confirmed : ExternalPlaybackResumeAction
    data object Failed : ExternalPlaybackResumeAction
}

/**
 * Pure resume state machine for one external playback session.
 *
 * Network Stream Player has no verified launch-position extra, so resume is
 * performed through its MediaSession. NSP 7.7 uses Media3; its legacy session
 * stub publishes ACTION_SEEK_TO when the current media item is seekable. A seek
 * is only requested after the session belongs to the launched package, reports
 * a valid duration and advertises [ACTION_SEEK_TO]. The command is issued at
 * most once; success is confirmed by observing the reported position move to
 * (or past) the target. A failed or ignored seek never pauses or otherwise
 * interrupts playback.
 */
class ExternalPlaybackResumeController(
    private val pending: PendingExternalPlayback,
    private val maxSeekToleranceMs: Long = DEFAULT_MAX_SEEK_TOLERANCE_MS,
    private val confirmationTimeoutMs: Long = DEFAULT_CONFIRMATION_TIMEOUT_MS,
    private val mediaReadyTimeoutMs: Long = DEFAULT_MEDIA_READY_TIMEOUT_MS,
) {
    companion object {
        /** android.media.session.PlaybackState.ACTION_SEEK_TO */
        const val ACTION_SEEK_TO = 256L
        const val DEFAULT_MAX_SEEK_TOLERANCE_MS = 5_000L
        const val DEFAULT_CONFIRMATION_TIMEOUT_MS = 15_000L
        const val DEFAULT_MEDIA_READY_TIMEOUT_MS = 15_000L
    }

    var state: ExternalPlaybackResumeState =
        when {
            pending.initialPositionMs <= 0L -> ExternalPlaybackResumeState.NOT_NEEDED
            pending.resumeSeekAttempted -> ExternalPlaybackResumeState.ALREADY_ATTEMPTED
            else -> ExternalPlaybackResumeState.WAITING_FOR_MEDIA
        }
        private set

    var lastRequestedPositionMs: Long? = null
        private set

    var confirmedPositionMs: Long? = null
        private set

    private val targetPositionMs = pending.initialPositionMs
    private val toleranceMs = min(
        maxSeekToleranceMs,
        max(1_000L, targetPositionMs / 10L),
    ).coerceAtLeast(1_000L)

    private var mediaReadyAtMs: Long? = null
    private var seekRequestedAtMs: Long? = null

    fun onSnapshot(
        snapshot: ExternalPlaybackSnapshot,
        boundSessionToken: String?,
        nowElapsedMs: Long,
    ): ExternalPlaybackResumeAction? {
        if (state != ExternalPlaybackResumeState.WAITING_FOR_MEDIA &&
            state != ExternalPlaybackResumeState.SEEK_REQUESTED
        ) {
            return null
        }
        if (snapshot.packageName != pending.packageName) return null
        if (boundSessionToken == null || snapshot.sessionToken != boundSessionToken) return null
        if (pending.episode.tvType.isLiveStream()) return fail()
        if (snapshot.state == ExternalPlaybackState.NONE) return null
        if (snapshot.durationMs <= 0L) return null
        if (targetPositionMs >= snapshot.durationMs) return fail()

        if (state == ExternalPlaybackResumeState.SEEK_REQUESTED) {
            // A player that accepted the seek is at or beyond the target. This
            // also tolerates a keyframe seek that lands slightly past it.
            if (snapshot.positionMs >= targetPositionMs - toleranceMs) {
                state = ExternalPlaybackResumeState.CONFIRMED
                confirmedPositionMs = snapshot.positionMs
                return ExternalPlaybackResumeAction.Confirmed
            }
            if (snapshot.state == ExternalPlaybackState.STOPPED ||
                snapshot.state == ExternalPlaybackState.ERROR
            ) {
                return fail()
            }
            val requestedAt = seekRequestedAtMs ?: return fail()
            if (nowElapsedMs - requestedAt >= confirmationTimeoutMs) return fail()
            return null
        }

        // Wait for a prepared media item. Duration and seek capability are the
        // only reliable readiness signals exposed through MediaSession.
        if (snapshot.state == ExternalPlaybackState.STOPPED ||
            snapshot.state == ExternalPlaybackState.ERROR
        ) {
            return fail()
        }
        if (snapshot.positionMs >= targetPositionMs - toleranceMs) {
            // The player is already at or past the saved point (for example it
            // resumed by itself). Do not seek backwards over user playback.
            state = ExternalPlaybackResumeState.CONFIRMED
            confirmedPositionMs = snapshot.positionMs
            return ExternalPlaybackResumeAction.Confirmed
        }
        if (snapshot.actions and ACTION_SEEK_TO == 0L) {
            val readyAt = mediaReadyAtMs ?: nowElapsedMs.also { mediaReadyAtMs = it }
            if (nowElapsedMs - readyAt >= mediaReadyTimeoutMs) return fail()
            return null
        }

        state = ExternalPlaybackResumeState.SEEK_REQUESTED
        seekRequestedAtMs = nowElapsedMs
        lastRequestedPositionMs = targetPositionMs
        return ExternalPlaybackResumeAction.SeekTo(targetPositionMs)
    }

    fun onSeekCommandFailed() {
        if (state == ExternalPlaybackResumeState.SEEK_REQUESTED) {
            state = ExternalPlaybackResumeState.FAILED
        }
    }

    fun onSeekAborted() {
        if (state == ExternalPlaybackResumeState.SEEK_REQUESTED) {
            state = ExternalPlaybackResumeState.FAILED
        }
    }

    private fun fail(): ExternalPlaybackResumeAction {
        state = ExternalPlaybackResumeState.FAILED
        return ExternalPlaybackResumeAction.Failed
    }
}
