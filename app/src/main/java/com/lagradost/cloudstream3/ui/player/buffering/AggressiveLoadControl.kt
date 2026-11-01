package com.lagradost.cloudstream3.ui.player.buffering

import androidx.media3.common.C
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.DefaultAllocator

/**
 * Media3 adapter for [AggressiveBufferController]. The allocator has a hard byte budget, while
 * shouldContinueLoading() is driven by forward media time, not the legacy byte target.
 */
@UnstableApi
class AggressiveLoadControl(
    val controller: AggressiveBufferController,
    private val allocator: DefaultAllocator = DefaultAllocator(
        true,
        C.DEFAULT_BUFFER_SEGMENT_SIZE
    ),
) : DefaultLoadControl(
    allocator,
    controller.policy.targetForwardMs.toInt(),
    controller.policy.targetForwardMs.toInt(),
    controller.policy.targetForwardMs.toInt(),
    controller.policy.targetForwardMs.toInt(),
    controller.policy.startupBufferMs.toInt(),
    controller.policy.startupBufferMs.toInt(),
    controller.policy.rebufferBufferMs.toInt(),
    controller.policy.rebufferBufferMs.toInt(),
    controller.policy.memoryBudgetBytes.toInt(),
    true,
    true,
    30_000,
    true,
) {
    val allocatedBytes: Long
        get() = allocator.totalBytesAllocated.toLong()

    private val timelinePeriod = Timeline.Period()
    private val timelineWindow = Timeline.Window()

    override fun onTracksSelected(
        parameters: LoadControl.Parameters,
        trackGroups: TrackGroupArray,
        trackSelections: Array<ExoTrackSelection?>,
    ) {
        super.onTracksSelected(parameters, trackGroups, trackSelections)
        val bitrateBps = trackSelections
            .mapNotNull { selection ->
                selection?.selectedFormat?.bitrate?.takeIf { it > 0 }?.toLong()
            }
            .takeIf { it.isNotEmpty() }
            ?.sum()

        controller.updateBitrate(bitrateBps)
        if (bitrateBps != null) {
            val quota = AggressiveCacheQuota.calculateForBitrate(
                targetMinutes = controller.policy.targetMinutes,
                bitrateBps = bitrateBps,
                availableStorageBytes = controller.policy.availableStorageBytes,
            )
            if (quota.bytes > 0L) {
                PlayerCacheManager.updateQuota(
                    PlayerCacheManager.AGGRESSIVE_DIRECTORY,
                    quota.bytes
                )
            }
        }
    }

    override fun shouldContinueLoading(parameters: LoadControl.Parameters): Boolean {
        return controller.shouldContinueLoading(
            bufferedForwardMs = parameters.bufferedDurationUs / 1_000L,
            allocatedBytes = allocator.totalBytesAllocated.toLong(),
            playWhenReady = parameters.playWhenReady,
            isLive = isLive(parameters),
            remainingMediaMs = remainingMediaMs(parameters),
        )
    }

    override fun shouldStartPlayback(parameters: LoadControl.Parameters): Boolean {
        if (isLive(parameters)) {
            return super.shouldStartPlayback(parameters)
        }
        return controller.shouldStartPlayback(
            bufferedForwardMs = parameters.bufferedDurationUs / 1_000L,
            allocatedBytes = allocator.totalBytesAllocated.toLong(),
            playbackSpeed = parameters.playbackSpeed,
            rebuffering = parameters.rebuffering,
            playWhenReady = parameters.playWhenReady,
            isLive = false,
            remainingMediaMs = remainingMediaMs(parameters),
        )
    }

    @Volatile
    var isVodOverride: Boolean = false

    private fun isLive(parameters: LoadControl.Parameters): Boolean {
        if (isVodOverride) return false
        if (parameters.timeline.isEmpty) return false
        val periodIndex = parameters.timeline.getIndexOfPeriod(parameters.mediaPeriodId.periodUid)
        if (periodIndex == C.INDEX_UNSET) return false
        val period = parameters.timeline.getPeriod(periodIndex, timelinePeriod)
        val window = parameters.timeline.getWindow(period.windowIndex, timelineWindow)
        if (window.durationUs != C.TIME_UNSET && window.durationUs > 0L) return false
        return window.isLive
    }

    private fun remainingMediaMs(parameters: LoadControl.Parameters): Long? {
        if (parameters.timeline.isEmpty) return null
        val periodIndex = parameters.timeline.getIndexOfPeriod(parameters.mediaPeriodId.periodUid)
        if (periodIndex == C.INDEX_UNSET) return null
        val period = parameters.timeline.getPeriod(periodIndex, timelinePeriod)
        val window = parameters.timeline.getWindow(period.windowIndex, timelineWindow)
        if (window.durationUs == C.TIME_UNSET || window.durationUs <= 0L) return null

        val positionInWindowUs = period.positionInWindowUs + parameters.playbackPositionUs
        return (window.durationUs - positionInWindowUs)
            .coerceAtLeast(0L)
            .div(1_000L)
    }
}
