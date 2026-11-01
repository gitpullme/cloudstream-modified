package com.lagradost.cloudstream3.ui.player.buffering

import kotlin.math.max
import kotlin.math.min

enum class BufferTargetReason {
    LIVE_WINDOW,
    PAUSED,
    END_OF_MEDIA,
}

data class EffectiveForwardTarget(
    val targetMs: Long,
    val reasons: Set<BufferTargetReason>,
)

/**
 * Framework-free aggressive buffering policy used by the Media3 load-control adapter and by
 * diagnostics. Keeping the decisions here makes the rolling-window behavior unit testable.
 */
class AggressiveBufferController(
    val policy: PlayerBufferingPolicy.Aggressive,
) {
    companion object {
        fun forwardBufferedMs(currentPositionMs: Long, bufferedPositionMs: Long): Long {
            return (bufferedPositionMs - currentPositionMs).coerceAtLeast(0L)
        }
    }
    @Volatile
    var bitrateEstimateBps: Long? = null
        private set

    fun updateBitrate(bitrateBps: Long?) {
        bitrateEstimateBps = bitrateBps?.takeIf { it > 0L }
    }

    fun effectiveTarget(
        playWhenReady: Boolean,
        isLive: Boolean,
        remainingMediaMs: Long?,
    ): EffectiveForwardTarget {
        var target = policy.targetForwardMs
        val reasons = mutableSetOf<BufferTargetReason>()

        if (isLive && policy.liveBufferMs < target) {
            target = policy.liveBufferMs
            reasons += BufferTargetReason.LIVE_WINDOW
        }

        if (!playWhenReady && policy.pausedBufferMs < target) {
            target = policy.pausedBufferMs
            reasons += BufferTargetReason.PAUSED
        }

        if (remainingMediaMs != null) {
            val remaining = remainingMediaMs.coerceAtLeast(0L)
            if (remaining < target) {
                target = remaining
                reasons += BufferTargetReason.END_OF_MEDIA
            }
        }

        return EffectiveForwardTarget(
            targetMs = target.coerceAtLeast(0L),
            reasons = reasons,
        )
    }

    fun shouldContinueLoading(
        bufferedForwardMs: Long,
        allocatedBytes: Long,
        playWhenReady: Boolean,
        isLive: Boolean,
        remainingMediaMs: Long?,
    ): Boolean {
        if (allocatedBytes >= policy.memoryBudgetBytes) return false

        val target = effectiveTarget(
            playWhenReady = playWhenReady,
            isLive = isLive,
            remainingMediaMs = remainingMediaMs,
        ).targetMs

        return target > 0L && bufferedForwardMs < target
    }

    fun shouldStartPlayback(
        bufferedForwardMs: Long,
        allocatedBytes: Long,
        playbackSpeed: Float,
        rebuffering: Boolean,
        playWhenReady: Boolean,
        isLive: Boolean,
        remainingMediaMs: Long?,
    ): Boolean {
        if (bufferedForwardMs <= 0L) return false
        if (allocatedBytes >= policy.memoryBudgetBytes) return true

        val target = effectiveTarget(
            playWhenReady = playWhenReady,
            isLive = isLive,
            remainingMediaMs = remainingMediaMs,
        ).targetMs

        val requestedBufferMs = if (rebuffering) {
            policy.rebufferBufferMs
        } else {
            policy.startupBufferMs
        }
        val startupThreshold = min(requestedBufferMs, target)
        val playoutDurationMs = bufferedForwardMs / max(1f, playbackSpeed)

        return playoutDurationMs >= startupThreshold || bufferedForwardMs >= target
    }

    fun isMemoryLimited(allocatedBytes: Long): Boolean {
        return allocatedBytes >= policy.memoryBudgetBytes
    }
}
