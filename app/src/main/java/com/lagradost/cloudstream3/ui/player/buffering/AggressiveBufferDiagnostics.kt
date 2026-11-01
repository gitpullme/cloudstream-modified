package com.lagradost.cloudstream3.ui.player.buffering

import android.os.SystemClock
import android.util.Log
import com.lagradost.cloudstream3.utils.AppDebug

data class AggressiveBufferDiagnosticsSnapshot(
    val targetMinutes: Int,
    val targetMs: Long,
    val effectiveTargetMs: Long,
    val currentPositionMs: Long,
    val bufferedPositionMs: Long,
    val forwardBufferedMs: Long,
    val allocatedBytes: Long,
    val memoryBudgetBytes: Long,
    val bitrateEstimateBps: Long?,
    val cacheSpaceBytes: Long,
    val cacheQuotaBytes: Long,
    val availableStorageBytes: Long,
    val isLive: Boolean,
    val isPlaying: Boolean,
    val playWhenReady: Boolean,
    val isLoading: Boolean,
    val targetReasons: Set<BufferTargetReason>,
    val memoryLimited: Boolean,
    val storageLimited: Boolean,
    val sourceLimited: Boolean,
    val sourceType: String,
    val prefetchStatus: PrefetchStatus = PrefetchStatus.IDLE,
    val prefetchSupport: PrefetchSupport? = null,
    val activePrefetchRequests: Int = 0,
    val maxParallelRequests: Int = 0,
    val completedPrefetchTasks: Int = 0,
    val pendingPrefetchTasks: Int = 0,
    val prefetchedCachedAheadMs: Long = 0L,
    val prefetchedBytes: Long = 0L,
    val prefetchBytesPerSecond: Long = 0L,
    val lastPrefetchFailure: String? = null,
)

/**
 * Low-overhead, debug-only diagnostics. The full snapshot is updated by CS3IPlayer roughly every
 * two seconds; log lines are emitted at most every ten seconds and contain no URLs or headers.
 */
object AggressiveBufferDiagnostics {
    private const val TAG = "AggressiveBuffer"
    private const val LOG_INTERVAL_MS = 10_000L

    @Volatile
    var latest: AggressiveBufferDiagnosticsSnapshot? = null
        private set

    private var lastLogAtMs = 0L

    @Synchronized
    fun update(snapshot: AggressiveBufferDiagnosticsSnapshot) {
        latest = snapshot
        if (!AppDebug.isDebug) return

        // Prefetch logging has its own low-frequency owner; avoid duplicating high-frequency logs.

        val now = SystemClock.elapsedRealtime()
        if (now - lastLogAtMs < LOG_INTERVAL_MS) return
        lastLogAtMs = now

        Log.d(
            TAG,
            "target=${snapshot.targetMs}ms effective=${snapshot.effectiveTargetMs}ms " +
                    "forward=${snapshot.forwardBufferedMs}ms " +
                    "pos=${snapshot.currentPositionMs}/${snapshot.bufferedPositionMs} " +
                    "alloc=${snapshot.allocatedBytes}/${snapshot.memoryBudgetBytes} " +
                    "bitrate=${snapshot.bitrateEstimateBps ?: "unknown"} " +
                    "cache=${snapshot.cacheSpaceBytes}/${snapshot.cacheQuotaBytes} " +
                    "freeStorage=${snapshot.availableStorageBytes} " +
                    "live=${snapshot.isLive} playing=${snapshot.isPlaying} " +
                    "loading=${snapshot.isLoading} " +
                    "reasons=${snapshot.targetReasons.joinToString(",")} " +
                    "memoryLimited=${snapshot.memoryLimited} " +
                    "storageLimited=${snapshot.storageLimited} " +
                    "sourceLimited=${snapshot.sourceLimited} type=${snapshot.sourceType} " +
                    "prefetch=${snapshot.prefetchStatus} support=${snapshot.prefetchSupport} " +
                    "prefetchActive=${snapshot.activePrefetchRequests}/" +
                    "${snapshot.maxParallelRequests} " +
                    "prefetchDone=${snapshot.completedPrefetchTasks} " +
                    "prefetchPending=${snapshot.pendingPrefetchTasks} " +
                    "prefetchedAhead=${snapshot.prefetchedCachedAheadMs}ms " +
                    "prefetchedBytes=${snapshot.prefetchedBytes} " +
                    "prefetchBps=${snapshot.prefetchBytesPerSecond} " +
                    "prefetchFailure=${snapshot.lastPrefetchFailure}"
        )
    }

    fun updatePrefetch(prefetch: PrefetchRuntimeSnapshot) {
        val snapshot = latest ?: return
        update(
            snapshot.copy(
                prefetchStatus = prefetch.status,
                prefetchSupport = prefetch.support,
                activePrefetchRequests = prefetch.activeRequests,
                maxParallelRequests = prefetch.maxParallelRequests,
                completedPrefetchTasks = prefetch.completedTasks,
                pendingPrefetchTasks = prefetch.pendingTasks,
                prefetchedCachedAheadMs = prefetch.cachedAheadMs,
                prefetchedBytes = prefetch.prefetchedBytes,
                prefetchBytesPerSecond = prefetch.prefetchBytesPerSecond,
                lastPrefetchFailure = prefetch.lastFailure,
            )
        )
    }

    @Synchronized
    fun clear() {
        latest = null
    }
}

