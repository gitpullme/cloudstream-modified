package com.lagradost.cloudstream3.ui.player.buffering

import android.net.Uri
import android.os.SystemClock
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.UriUtil
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheKeyFactory
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.exoplayer.dash.manifest.DashManifest
import androidx.media3.exoplayer.dash.manifest.DashManifestParser
import androidx.media3.exoplayer.dash.manifest.Representation
import androidx.media3.exoplayer.dash.DashUtil
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParser
import com.lagradost.cloudstream3.utils.AppDebug
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.max

private const val MAX_MANIFEST_BYTES = 8 * 1024 * 1024

enum class PrefetchStatus {
    IDLE,
    TARGET_REACHED,
    BUFFER_BELOW_TARGET,
    NETWORK_LIMITED,
    SOURCE_UNAVAILABLE,
    SERVER_THROTTLED,
    RANGE_UNSUPPORTED,
    INVALID_RANGE_RESPONSE,
    AUTH_OR_URL_FAILURE,
    CACHE_READ_OR_WRITE_FAILURE,
    CACHE_QUOTA_REACHED,
    STORAGE_PRESSURE,
    MEMORY_PRESSURE,
    MEDIA_END_REACHED,
    FORMAT_UNSUPPORTED_FOR_PREFETCH,
    PREFETCH_CANCELLED_BY_SEEK,
    PREFETCH_CANCELLED_BY_RELEASE,
    PLAYBACK_LOADING_PENDING,
    UNKNOWN_FAILURE,
}

enum class PrefetchSupport {
    HLS_VOD,
    DASH_VOD,
    PROGRESSIVE_VOD,
    LIVE_UNSUPPORTED,
    MANIFEST_UNSUPPORTED,
    FORMAT_UNSUPPORTED,
}

@UnstableApi
data class PrefetchTask(
    val uriString: String,
    val position: Long,
    val length: Long,
    val startMs: Long,
    val endMs: Long,
    val label: String,
    val cacheKey: String? = null,
) {
    val uri: Uri
        get() = uriString.toUri()

    val key: String = "$uriString|$position|$length|${cacheKey.orEmpty()}"

    fun dataSpec(): DataSpec {
        return DataSpec.Builder()
            .setUri(uri)
            .setPosition(position.coerceAtLeast(0L))
            .setLength(if (length > 0L) length else C.LENGTH_UNSET.toLong())
            .apply { cacheKey?.let { setKey(it) } }
            .build()
    }

    fun cacheKey(): String = CacheKeyFactory.DEFAULT.buildCacheKey(dataSpec())
}

@UnstableApi
internal fun isTaskCached(cache: Cache, task: PrefetchTask): Boolean {
    val key = task.cacheKey()
    val pos = task.position.coerceAtLeast(0L)
    if (task.length > 0L) {
        return cache.isCached(key, pos, task.length)
    }
    // For open-ended or whole-segment requests (length <= 0L), check ContentMetadata
    // to ensure the full resource has actually been downloaded.
    val contentLength = ContentMetadata.getContentLength(cache.getContentMetadata(key))
    if (contentLength > 0L && contentLength != C.LENGTH_UNSET.toLong()) {
        val remaining = contentLength - pos
        return remaining > 0L && cache.isCached(key, pos, remaining)
    }
    // HLS/DASH servers often omit Content-Length. A completed segment is stored as a
    // closed span (isOpenEnded == false), while a partially written span is open-ended.
    // Only a closed span proves that the whole segment is in the cache.
    return runCatching {
        cache.getCachedSpans(key).any { span ->
            span.isCached &&
                    !span.isOpenEnded &&
                    span.length > 0L &&
                    span.position <= pos &&
                    pos < span.position + span.length
        }
    }.getOrDefault(false)
}

/**
 * A task remains useful while any part of its media range is at or ahead of the playhead.
 * Slow downloads must not be cancelled merely because playback has reached their start.
 */
@UnstableApi
internal fun shouldKeepPrefetchTask(task: PrefetchTask, currentMs: Long): Boolean {
    return task.endMs > currentMs
}

@UnstableApi
data class PrefetchPlan(
    val tasks: List<PrefetchTask>,
    val support: PrefetchSupport,
    val status: PrefetchStatus? = null,
)

data class PrefetchRuntimeSnapshot(
    val status: PrefetchStatus,
    val support: PrefetchSupport?,
    val activeRequests: Int,
    val maxParallelRequests: Int,
    val completedTasks: Int,
    val pendingTasks: Int,
    val cachedAheadMs: Long,
    val prefetchedBytes: Long,
    val lastFailure: String?,
    val prefetchBytesPerSecond: Long = 0L,
) {
    companion object {
        val idle = PrefetchRuntimeSnapshot(
            status = PrefetchStatus.IDLE,
            support = null,
            activeRequests = 0,
            maxParallelRequests = 2,
            completedTasks = 0,
            pendingTasks = 0,
            cachedAheadMs = 0L,
            prefetchedBytes = 0L,
            lastFailure = null,
        )
    }
}

private data class PlayerSnapshot(
    val currentMs: Long,
    val bufferedMs: Long,
    val playWhenReady: Boolean,
    val isLive: Boolean,
    val memoryLimited: Boolean,
    val storageLimited: Boolean,
    val isPlayerLoading: Boolean,
    val durationMs: Long?,
)

@UnstableApi
private data class ActiveTask(
    val task: PrefetchTask,
    val job: Job,
    var writer: CacheWriter? = null,
)

@UnstableApi
class SmartPrefetchEngine(
    private val cache: Cache,
    private val upstreamFactory: HttpDataSource.Factory,
    private val sourceUri: Uri,
    private val sourceType: ExtractorLinkType,
    private val targetMs: Long,
    private var maxParallelRequests: Int,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val active = ConcurrentHashMap<String, ActiveTask>()
    private val completedKeys = ConcurrentHashMap.newKeySet<String>()
    private val attempts = ConcurrentHashMap<String, Int>()
    private val backoffUntil = ConcurrentHashMap<String, Long>()
    private val blockedKeys = ConcurrentHashMap.newKeySet<String>()
    private val prefetchedBytes = AtomicLong(0L)
    private val cachedTasks = ConcurrentHashMap<String, PrefetchTask>()
    private val completedCount = AtomicLong(0L)
    private val lastThroughputBps = AtomicLong(0L)
    private val taskStartedAt = ConcurrentHashMap<String, Long>()
    private val taskStartBytes = ConcurrentHashMap<String, Long>()

    private var planner: MediaPrefetchPlanner? = null
    private var lastPlanAt = 0L
    private var lastFormat: Format? = null
    @Volatile
    private var lastSelectedFormat: Format? = null
    @Volatile
    private var lastSnapshot = PlayerSnapshot(
        currentMs = 0L,
        bufferedMs = 0L,
        playWhenReady = false,
        isLive = false,
        memoryLimited = false,
        storageLimited = false,
        isPlayerLoading = false,
        durationMs = null,
    )
    private var currentLimit = minOf(maxParallelRequests, 2).coerceAtLeast(1)
    private var consecutiveSuccesses = 0
    private var lastStatus = PrefetchStatus.IDLE
    private var lastSupport: PrefetchSupport? = null
    private var lastFailure: String? = null
    private var lastFailureStatus: PrefetchStatus? = null
    private var pendingTasks = 0
    private var quotaLimited = false
    private var concurrencyCooldownUntil = 0L
    private var released = false

    init {
        // This is a hard ceiling, not a user preference. Even a stale caller cannot
        // start more than two speculative readers.
        maxParallelRequests = maxParallelRequests.coerceIn(1, 2)
        currentLimit = maxParallelRequests
    }

    @Volatile
    var runtimeSnapshot: PrefetchRuntimeSnapshot = PrefetchRuntimeSnapshot.idle
        private set

    init {
        liveEngines.add(this)
    }

    fun start() {
        if (released) return
        scope.launch {
            while (isActive && !released) {
                runCatching { tick() }
                    .onFailure {
                        if (it is CancellationException) throw it
                        // Do not log the throwable: it may contain a signed media URI.
                        // The classified status is exposed through diagnostics instead.
                        lastFailure = classifyFailure(it).name
                        lastStatus = classifyFailure(it)
                    }
                delay(250L)
            }
        }
    }

    fun updatePlayerState(
        currentMs: Long,
        bufferedMs: Long,
        playWhenReady: Boolean,
        isLive: Boolean,
        memoryLimited: Boolean,
        storageLimited: Boolean,
        isPlayerLoading: Boolean,
        durationMs: Long?,
        selectedFormat: Format?,
    ) {
        lastSelectedFormat = selectedFormat
        val previous = lastSnapshot
        lastSnapshot = PlayerSnapshot(
            currentMs = currentMs,
            bufferedMs = bufferedMs,
            playWhenReady = playWhenReady,
            isLive = isLive,
            memoryLimited = memoryLimited,
            storageLimited = storageLimited,
            isPlayerLoading = isPlayerLoading,
            durationMs = durationMs,
        )

        if (previous.currentMs != C.TIME_UNSET &&
            (currentMs < previous.currentMs - 500L || abs(currentMs - previous.currentMs) > 4_000L)
        ) {
            cancelObsolete(forceAll = true, status = PrefetchStatus.PREFETCH_CANCELLED_BY_SEEK)
            lastPlanAt = 0L
        }
    }

    fun release() {
        if (released) return
        released = true
        cancelObsolete(forceAll = true, status = PrefetchStatus.PREFETCH_CANCELLED_BY_RELEASE)
        scope.cancel()
        runtimeSnapshot = runtimeSnapshot.copy(
            status = PrefetchStatus.PREFETCH_CANCELLED_BY_RELEASE,
            activeRequests = 0,
            pendingTasks = 0,
        )
        liveEngines.remove(this)
    }

    private suspend fun tick() {
        if (released) return
        val snapshot = lastSnapshot
        val format = lastSelectedFormat
        if (format == null) {
            cancelObsolete(forceAll = true)
            publish(PrefetchStatus.PLAYBACK_LOADING_PENDING)
            return
        }
        if (snapshot.isLive) {
            cancelObsolete(forceAll = true)
            setStatus(PrefetchStatus.FORMAT_UNSUPPORTED_FOR_PREFETCH, PrefetchSupport.LIVE_UNSUPPORTED)
            return
        }
        if (!snapshot.playWhenReady) {
            // Do not grow the speculative cache indefinitely while paused. Existing
            // useful spans stay available and are reused after resume.
            cancelObsolete(forceAll = true)
            publish(PrefetchStatus.BUFFER_BELOW_TARGET)
            return
        }
        val duration = snapshot.durationMs
        if (duration != null && duration > 0L && snapshot.currentMs >= duration - 1_000L) {
            cancelObsolete(forceAll = true)
            publish(PrefetchStatus.MEDIA_END_REACHED)
            return
        }
        if (snapshot.storageLimited) {
            cancelObsolete(forceAll = true)
            publish(PrefetchStatus.STORAGE_PRESSURE)
            return
        }

        val forward = AggressiveBufferController.forwardBufferedMs(
            snapshot.currentMs,
            snapshot.bufferedMs
        )
        if (forward >= targetMs - 1_000L) {
            cancelObsolete(forceAll = true)
            pendingTasks = 0
            publish(PrefetchStatus.TARGET_REACHED)
            return
        }
        // Prefetch must remain available while the player is still filling its
        // near-term buffer. Gating it on a cushion the source cannot reach leaves a
        // slow CDN with one connection forever. Resource limits are enforced by the
        // cache quota, memory budget and the two-reader ceiling instead.
        currentLimit = if (snapshot.memoryLimited ||
            System.currentTimeMillis() < concurrencyCooldownUntil
        ) 1 else maxParallelRequests
        limitActiveTo(currentLimit)
        if (snapshot.memoryLimited) {
            lastStatus = PrefetchStatus.MEMORY_PRESSURE
        }
        quotaLimited = false

        if (planner == null) {
            planner = createPlanner()
            lastPlanAt = 0L
        }
        val activePlanner = planner
        if (activePlanner == null) {
            val support = when (sourceType) {
                ExtractorLinkType.M3U8 -> PrefetchSupport.HLS_VOD
                ExtractorLinkType.DASH -> PrefetchSupport.DASH_VOD
                else -> PrefetchSupport.PROGRESSIVE_VOD
            }
            setStatus(PrefetchStatus.FORMAT_UNSUPPORTED_FOR_PREFETCH, support)
            cancelObsolete(forceAll = true)
            return
        }

        val now = System.currentTimeMillis()
        if (lastPlanAt == 0L || now - lastPlanAt >= 500L ||
            format != lastFormat || forward <= 0L
        ) {
            if (lastFormat != null && format != null && lastFormat != format) {
                // Quality switched by user or adaptive selection; cancel previous quality downloads immediately.
                cancelObsolete(forceAll = true)
            }
            lastFormat = format
            val plan = runCatching {
                activePlanner.buildPlan(
                    cache = cache,
                    selectedFormat = format,
                    currentMs = snapshot.currentMs,
                    bufferedMs = snapshot.bufferedMs,
                    targetMs = targetMs,
                    durationMs = snapshot.durationMs,
                    completedKeys = completedKeys,
                )
            }.getOrElse {
                lastFailure = classifyFailure(it).name
                lastStatus = classifyFailure(it)
                null
            }
            if (plan == null) {
                cancelObsolete(forceAll = true)
                lastPlanAt = now + 3_000L
                publish(lastStatus)
                return
            }
            // The plan only contains ranges that are not fully cached. If such a range
            // is still marked completed, its span was evicted or only partially written;
            // let it be scheduled again instead of trusting stale bookkeeping.
            if (plan.tasks.isNotEmpty()) {
                val planKeys = plan.tasks.mapTo(HashSet(plan.tasks.size)) { it.key }
                completedKeys.removeAll(planKeys)
            }
            lastSupport = plan.support
            if (plan.tasks.isEmpty()) {
                lastPlanAt = if (plan.status == PrefetchStatus.SOURCE_UNAVAILABLE) now + 3_000L else now
                cancelObsolete(forceAll = true)
                val statusToPublish = when {
                    plan.support == PrefetchSupport.LIVE_UNSUPPORTED -> PrefetchStatus.FORMAT_UNSUPPORTED_FOR_PREFETCH
                    plan.status != null -> plan.status
                    // No future task means either all useful media is cached or the
                    // source is exhausted. Never claim the playable target was reached
                    // here; only the actual forward-buffer measurement can do that.
                    else -> PrefetchStatus.BUFFER_BELOW_TARGET
                }
                publish(statusToPublish)
                return
            }
            val desiredKeys = plan.tasks.asSequence().map { it.key }.toHashSet()
            // Keep in-flight work whose media range still overlaps or leads the
            // playhead. Using only the task start time cancels a slow 8 MiB download
            // the moment playback catches its beginning, which is exactly when the
            // player is about to consume it. Only work that has fallen entirely behind
            // the playhead is useless and safe to drop.
            val keepKeys = desiredKeys + active.values
                .asSequence()
                .filter { shouldKeepPrefetchTask(it.task, snapshot.currentMs) }
                .map { it.task.key }
                .toList()
            cancelObsolete(keepKeys)
            lastPlanAt = now
            schedule(plan.tasks, desiredKeys)
            pendingTasks = plan.tasks.count { task ->
                task.key !in active.keys &&
                        task.key !in completedKeys &&
                        task.key !in blockedKeys
            }
        }

        publish(
            when {
                snapshot.memoryLimited -> PrefetchStatus.MEMORY_PRESSURE
                quotaLimited -> PrefetchStatus.CACHE_QUOTA_REACHED
                snapshot.isPlayerLoading -> PrefetchStatus.PLAYBACK_LOADING_PENDING
                active.isNotEmpty() -> PrefetchStatus.BUFFER_BELOW_TARGET
                blockedKeys.isNotEmpty() -> lastFailureStatus ?: PrefetchStatus.UNKNOWN_FAILURE
                else -> PrefetchStatus.BUFFER_BELOW_TARGET
            }
        )
    }

    private fun createPlanner(): MediaPrefetchPlanner? {
        val url = sourceUri.toString()
        return when {
            sourceType == ExtractorLinkType.M3U8 || url.contains(".m3u8", ignoreCase = true) ->
                HlsPrefetchPlanner(sourceUri, upstreamFactory)
            sourceType == ExtractorLinkType.DASH || url.contains(".mpd", ignoreCase = true) ->
                DashPrefetchPlanner(sourceUri, upstreamFactory)
            // Direct files are prefetched as sequential byte ranges only after a
            // validated 206/Content-Range probe. The range positions are never used as
            // a claim about exact media time; the time estimate only sizes the rolling
            // byte window. Unsupported servers fall back to normal Media3 loading.
            else -> ProgressivePrefetchPlanner(sourceUri, upstreamFactory)
        }
    }

    private fun schedule(tasks: List<PrefetchTask>, desiredKeys: Set<String>) {
        val now = System.currentTimeMillis()
        val candidates = PrefetchTaskSelector.dispatchable(
            tasks = tasks,
            desiredKeys = desiredKeys,
            activeKeys = active.keys,
            completedKeys = completedKeys,
            blockedKeys = blockedKeys,
            backoffUntil = backoffUntil,
            nowMs = now,
            activeCount = active.size,
            maxConcurrent = currentLimit,
        )
        for (task in candidates) {
            if (task.key in completedKeys || isTaskCached(cache, task)) {
                completedKeys.add(task.key)
                continue
            }
            if (cacheSpaceNearQuota()) {
                quotaLimited = true
                break
            }

            val job = scope.launch(start = CoroutineStart.LAZY) { execute(task) }
            val handle = ActiveTask(task, job)
            active[task.key] = handle
            job.invokeOnCompletion {
                active.remove(task.key)
                handle.writer = null
            }
            job.start()
        }
    }

    private suspend fun execute(task: PrefetchTask) {
        val startedAt = SystemClock.elapsedRealtime()
        val startBytes = prefetchedBytes.get()
        try {
            if (!currentCoroutineContext().isActive || released) return
            taskStartedAt[task.key] = startedAt
            taskStartBytes[task.key] = startBytes
            val dataSource = CacheDataSource.Factory()
                .setCache(cache)
                .setUpstreamDataSourceFactory(upstreamFactory)
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
                .createDataSource()
            var writer: CacheWriter? = null
            try {
                writer = CacheWriter(
                    dataSource,
                    task.dataSpec(),
                    null,
                    CacheWriter.ProgressListener { _, _, newBytes ->
                        if (newBytes > 0L) prefetchedBytes.addAndGet(newBytes)
                    }
                )
                active[task.key]?.writer = writer
                writer.cache()
                currentCoroutineContext().ensureActive()
                // CacheWriter can finish without storing anything if another reader
                // holds the same range. Treat only a verified cached span as success
                // so the rolling window does not silently develop holes.
                if (!isTaskCached(cache, task)) {
                    val attempt = (attempts[task.key] ?: 0) + 1
                    attempts[task.key] = attempt
                    if (attempt < 3) {
                        backoffUntil[task.key] = System.currentTimeMillis() + 2_000L
                    } else {
                        blockedKeys.add(task.key)
                        backoffUntil.remove(task.key)
                    }
                    lastFailure = "CACHE_WRITE_INCOMPLETE"
                    lastFailureStatus = PrefetchStatus.CACHE_READ_OR_WRITE_FAILURE
                    lastStatus = PrefetchStatus.CACHE_READ_OR_WRITE_FAILURE
                    taskStartedAt.remove(task.key)
                    taskStartBytes.remove(task.key)
                    publish(PrefetchStatus.CACHE_READ_OR_WRITE_FAILURE)
                    return
                }
                completedKeys.add(task.key)
                completedCount.incrementAndGet()
                cachedTasks[task.key] = task
                attempts.remove(task.key)
                backoffUntil.remove(task.key)
                onTaskSuccess(task.key, startedAt, startBytes)
            } finally {
                runCatching { writer?.cancel() }
                runCatching { dataSource.close() }
            }
        } catch (e: CancellationException) {
            taskStartedAt.remove(task.key)
            taskStartBytes.remove(task.key)
            throw e
        } catch (e: Exception) {
            if (!currentCoroutineContext().isActive || released) return
            val failure = classifyFailure(e)
            val http = findHttpException(e)
            val retryable = http?.responseCode?.let {
                it == 408 || it == 425 || it == 429 || it in 500..599
            } ?: true
            val attempt = (attempts[task.key] ?: 0) + 1
            attempts[task.key] = attempt
            if (retryable && attempt < 3) {
                val retryAfter = retryAfterMs(http)
                val delayMs = retryAfter
                    ?: ((1_000L shl (attempt - 1)).coerceAtMost(8_000L) +
                            kotlin.random.Random.nextLong(0L, 500L))
                backoffUntil[task.key] = System.currentTimeMillis() + delayMs
            } else {
                blockedKeys.add(task.key)
                backoffUntil.remove(task.key)
            }
            taskStartedAt.remove(task.key)
            taskStartBytes.remove(task.key)
            lastFailure = failure.name
            lastFailureStatus = failure
            lastStatus = failure
            onTaskFailure(http?.responseCode)
            publish(failure)
        }
    }

    private fun onTaskSuccess(taskKey: String, startedAt: Long, startBytes: Long) {
        val elapsedMs = (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(1L)
        val bytes = (prefetchedBytes.get() - startBytes).coerceAtLeast(0L)
        taskStartedAt.remove(taskKey)
        taskStartBytes.remove(taskKey)
        if (bytes <= 0L) return

        val instantBps = bytes * 1_000L / elapsedMs
        val previous = lastThroughputBps.get()
        lastThroughputBps.set(if (previous <= 0L) instantBps else (previous * 3L + instantBps) / 4L)
        consecutiveSuccesses += 1
        if (consecutiveSuccesses >= 1 &&
            currentLimit < maxParallelRequests &&
            System.currentTimeMillis() >= concurrencyCooldownUntil
        ) {
            currentLimit = (currentLimit + 1).coerceAtMost(2)
            consecutiveSuccesses = 0
        }
    }

    private fun onTaskFailure(responseCode: Int?) {
        consecutiveSuccesses = 0
        currentLimit = 1
        concurrencyCooldownUntil = System.currentTimeMillis() +
                if (responseCode == 429) 15_000L else 5_000L
    }

    private fun cancelObsolete(
        desiredKeys: Set<String> = emptySet(),
        forceAll: Boolean = false,
        status: PrefetchStatus? = null,
    ) {
        val stale = active.values.filter { forceAll || it.task.key !in desiredKeys }
        for (handle in stale) {
            runCatching { handle.writer?.cancel() }
            handle.job.cancel()
        }
        if (forceAll && status != null) {
            lastStatus = status
        }
    }

    /**
     * Keeps the nearest-to-playback readers when the player needs bandwidth. Cancelling a
     * speculative reader only abandons cache-fill progress; CacheWriter safely resumes a
     * partially written span on the next plan.
     */
    private fun limitActiveTo(limit: Int) {
        if (active.size <= limit) return
        val excess = active.values
            .sortedBy { it.task.startMs }
            .drop(limit.coerceAtLeast(0))
        for (handle in excess) {
            runCatching { handle.writer?.cancel() }
            handle.job.cancel()
        }
    }

    private fun publish(status: PrefetchStatus = lastStatus) {
        lastStatus = status
        val cachedAhead = cachedTasks.values
            .filter { it.key in completedKeys || isTaskCached(cache, it) }
            .map { (it.endMs - it.startMs).coerceAtLeast(0L) }
            .sum()
            .coerceAtMost(targetMs)
        runtimeSnapshot = PrefetchRuntimeSnapshot(
            status = status,
            support = lastSupport,
            activeRequests = active.size,
            maxParallelRequests = maxParallelRequests,
            completedTasks = completedCount.get().toInt(),
            pendingTasks = pendingTasks,
            cachedAheadMs = cachedAhead,
            prefetchedBytes = prefetchedBytes.get(),
            lastFailure = lastFailure,
            prefetchBytesPerSecond = lastThroughputBps.get(),
        )
        if (AppDebug.isDebug) {
            // Deliberately do not log task URIs, headers or signed queries.
            AggressiveBufferDiagnostics.updatePrefetch(runtimeSnapshot)
        }
    }

    private fun setStatus(status: PrefetchStatus, support: PrefetchSupport?) {
        lastSupport = support
        publish(status)
    }

    private fun cacheSpaceNearQuota(): Boolean {
        val quota = PlayerCacheManager.currentQuota(
            PlayerCacheManager.directoryForCache(cache) ?: return false
        ) ?: return false
        return quota > 0L && cache.cacheSpace >= (quota * 0.85).toLong()
    }

    private fun classifyFailure(error: Throwable): PrefetchStatus {
        val http = findHttpException(error)
        return when (http?.responseCode) {
            401, 403 -> PrefetchStatus.AUTH_OR_URL_FAILURE
            404, 410 -> PrefetchStatus.SOURCE_UNAVAILABLE
            416 -> PrefetchStatus.INVALID_RANGE_RESPONSE
            429 -> PrefetchStatus.SERVER_THROTTLED
            in 500..599 -> PrefetchStatus.NETWORK_LIMITED
            else -> when (error) {
                is CancellationException -> PrefetchStatus.PREFETCH_CANCELLED_BY_RELEASE
                is androidx.media3.datasource.cache.Cache.CacheException ->
                    PrefetchStatus.CACHE_READ_OR_WRITE_FAILURE
                is IOException -> PrefetchStatus.NETWORK_LIMITED
                else -> PrefetchStatus.UNKNOWN_FAILURE
            }
        }
    }

    private fun retryAfterMs(error: HttpDataSource.InvalidResponseCodeException?): Long? {
        val value = error?.headerFields?.entries
            ?.firstOrNull { it.key.equals("Retry-After", ignoreCase = true) }
            ?.value?.firstOrNull() ?: return null
        return value.toLongOrNull()?.coerceIn(1L, 30L)?.times(1_000L)
    }

    companion object {
        private val liveEngines = java.util.Collections.newSetFromMap(
            ConcurrentHashMap<SmartPrefetchEngine, Boolean>()
        )

        fun cancelAllActive() {
            liveEngines.toList().forEach { engine ->
                runCatching { engine.release() }
            }
        }
    }
}

private fun findHttpException(error: Throwable): HttpDataSource.InvalidResponseCodeException? {
    var current: Throwable? = error
    while (current != null) {
        if (current is HttpDataSource.InvalidResponseCodeException) return current
        current = current.cause
    }
    return null
}

/**
 * Speculative media starts just beyond the player's current buffer edge. A large gap
 * would leave a hole that the player still has to download while it is under pressure;
 * a tiny gap would race the player for the segment it is about to request. Ten seconds
 * (or less for short targets) is enough to stay clear without wasting the cache window.
 */
private fun prefetchGapMs(targetMs: Long): Long {
    return minOf(10_000L, maxOf(2_000L, targetMs / 30L))
}

private fun alignUp(value: Long, alignment: Long): Long {
    if (alignment <= 1L || value <= 0L) return value
    return ((value + alignment - 1L) / alignment) * alignment
}

/**
 * Picks the first byte a progressive prefetch may lock. Two player chunks of separation
 * keep the player's current and next cache requests out of the speculative window; a
 * CacheDataSource read blocks on a locked range until the prefetch writer finishes.
 */
@UnstableApi
internal fun progressivePrefetchStart(
    cacheFrontierByte: Long,
    totalLength: Long,
    playerChunkBytes: Long = ChunkedCacheDataSourceFactory.DEFAULT_CHUNK_SIZE,
    gapChunks: Long = 2L,
    alignment: Long = ChunkedCacheDataSourceFactory.DEFAULT_CHUNK_SIZE,
): Long {
    if (totalLength <= 0L) return 0L
    val frontier = cacheFrontierByte.coerceIn(0L, totalLength)
    val gap = gapChunks.coerceAtLeast(0L) * playerChunkBytes.coerceAtLeast(0L)
    return alignUp((frontier + gap).coerceAtMost(totalLength), alignment)
        .coerceIn(0L, totalLength)
}

/**
 * Finds how far cached spans extend contiguously from [from]. This is the player's
 * actual byte read-ahead frontier for a progressive file, independent of prefetched
 * spans that may exist much farther ahead.
 */
@UnstableApi
private fun contiguousCachedEnd(
    cache: Cache,
    key: String,
    from: Long,
    totalLength: Long,
): Long {
    val start = from.coerceIn(0L, totalLength)
    val spans = runCatching { cache.getCachedSpans(key) }
        .getOrDefault(emptySet())
        .asSequence()
        .filter { it.isCached && !it.isOpenEnded && it.length > 0L }
        .sortedBy { it.position }
        .toList()
    var end = start
    for (span in spans) {
        val spanEnd = (span.position + span.length).coerceAtMost(totalLength)
        if (spanEnd <= end) continue
        if (span.position > end) break
        end = spanEnd
    }
    return end.coerceIn(0L, totalLength)
}

@UnstableApi
private interface MediaPrefetchPlanner {
    suspend fun buildPlan(
        cache: Cache,
        selectedFormat: Format?,
        currentMs: Long,
        bufferedMs: Long,
        targetMs: Long,
        durationMs: Long? = null,
        completedKeys: Set<String> = emptySet(),
    ): PrefetchPlan
}

@UnstableApi
internal object HlsVariantSelector {
    fun selectVariant(
        playlist: HlsMultivariantPlaylist,
        selectedFormat: Format?,
    ): HlsMultivariantPlaylist.Variant? {
        if (playlist.variants.isEmpty()) return null
        if (selectedFormat == null) {
            return playlist.variants.maxByOrNull { it.format.bitrate } ?: playlist.variants.first()
        }
        // 1. Exact format ID match if available
        if (!selectedFormat.id.isNullOrBlank()) {
            playlist.variants.firstOrNull { it.format.id == selectedFormat.id }?.let { return it }
        }
        val width = selectedFormat.width
        val height = selectedFormat.height
        // 2. Exact resolution match
        if (width > 0 && height > 0) {
            val exactRes = playlist.variants.filter { it.format.width == width && it.format.height == height }
            if (exactRes.isNotEmpty()) {
                return exactRes.minByOrNull {
                    abs(it.format.bitrate.coerceAtLeast(0) - selectedFormat.bitrate.coerceAtLeast(0))
                }
            }
        }
        // 3. Aspect ratio / letterbox tolerant height match (e.g. cinema crop 3840x1600 vs 3840x2160)
        if (height > 0) {
            val closeHeight = playlist.variants.filter { abs(it.format.height - height) <= 120 }
            if (closeHeight.isNotEmpty()) {
                return closeHeight.minByOrNull {
                    abs(it.format.bitrate.coerceAtLeast(0) - selectedFormat.bitrate.coerceAtLeast(0))
                }
            }
        }
        // 4. Closest width match if width > 0
        if (width > 0) {
            val closeWidth = playlist.variants.filter { abs(it.format.width - width) <= 200 }
            if (closeWidth.isNotEmpty()) {
                return closeWidth.minByOrNull {
                    abs(it.format.bitrate.coerceAtLeast(0) - selectedFormat.bitrate.coerceAtLeast(0))
                }
            }
        }
        // 5. Closest bitrate match if bitrate > 0
        val bitrate = selectedFormat.bitrate
        if (bitrate > 0) {
            playlist.variants.minByOrNull {
                abs(it.format.bitrate.coerceAtLeast(0) - bitrate)
            }?.let { return it }
        }
        // 6. Closest resolution overall
        if (height > 0) {
            playlist.variants.minByOrNull { abs(it.format.height - height) }?.let { return it }
        }
        // 7. Resilient fallback: highest bitrate or first
        return playlist.variants.maxByOrNull { it.format.bitrate } ?: playlist.variants.first()
    }
}

@UnstableApi
private class HlsPrefetchPlanner(
    private val sourceUri: Uri,
    private val upstreamFactory: HttpDataSource.Factory,
) : MediaPrefetchPlanner {
    private var master: HlsMultivariantPlaylist? = null
    private var masterBaseUri: String? = null
    private var directMediaPlaylist: HlsMediaPlaylist? = null
    private var directPlaylistFetchTime = 0L
    private var masterLoaded = false
    private var selectedVariantUrl: String? = null
    private val mediaPlaylists = mutableMapOf<Uri, HlsMediaPlaylist>()
    private val playlistFetchTimes = mutableMapOf<Uri, Long>()

    override suspend fun buildPlan(
        cache: Cache,
        selectedFormat: Format?,
        currentMs: Long,
        bufferedMs: Long,
        targetMs: Long,
        durationMs: Long?,
        completedKeys: Set<String>,
    ): PrefetchPlan {
        val playlists = loadMediaPlaylists(selectedFormat)
        if (playlists.isEmpty()) {
            return PrefetchPlan(
                emptyList(),
                PrefetchSupport.FORMAT_UNSUPPORTED,
                PrefetchStatus.SOURCE_UNAVAILABLE
            )
        }
        val primary = playlists.first()
        if (primary.playlistType != HlsMediaPlaylist.PLAYLIST_TYPE_VOD && !primary.hasEndTag) {
            return PrefetchPlan(emptyList(), PrefetchSupport.LIVE_UNSUPPORTED)
        }

        // Leave a safety gap behind the playhead. Those segments belong to the player;
        // speculative writers must not contend for the same cache spans/connection.
        val gapMs = prefetchGapMs(targetMs)
        val startMs = max(currentMs, bufferedMs) + gapMs
        val endMs = currentMs + targetMs
        val tasks = ArrayList<PrefetchTask>()
        val seen = HashSet<String>()

        for (playlist in playlists) {
            val playlistStartMs = playlist.startOffsetUs
                .takeIf { it != C.TIME_UNSET && it >= 0L }
                ?.div(1_000L) ?: 0L

            for (segment in playlist.segments) {
                if (segment.hasGapTag) continue
                val segmentStart = playlistStartMs + segment.relativeStartTimeUs.coerceAtLeast(0L) / 1_000L
                val segmentEnd = segmentStart + segment.durationUs.coerceAtLeast(0L) / 1_000L
                if (segmentEnd <= startMs || segmentStart >= endMs) continue
                segment.initializationSegment?.let { init ->
                    addHlsTask(init, playlist, cache, seen, tasks, 0L, 0L, "hls init")
                }
                addHlsTask(segment, playlist, cache, seen, tasks, segmentStart, segmentEnd, "hls segment")
                if (tasks.size >= MAX_TASKS) break
            }
            if (tasks.size >= MAX_TASKS) break
        }

        return PrefetchPlan(tasks.sortedBy { it.startMs }, PrefetchSupport.HLS_VOD)
    }

    private fun addHlsTask(
        segment: HlsMediaPlaylist.SegmentBase,
        playlist: HlsMediaPlaylist,
        cache: Cache,
        seen: MutableSet<String>,
        tasks: MutableList<PrefetchTask>,
        startMs: Long,
        endMs: Long,
        label: String = "hls segment",
    ) {
        val uri = resolve(playlist.baseUri, segment.url) ?: return
        val task = PrefetchTask(
            uriString = uri.toString(),
            position = if (segment.byteRangeLength > 0L) segment.byteRangeOffset else 0L,
            length = if (segment.byteRangeLength > 0L) segment.byteRangeLength else C.LENGTH_UNSET.toLong(),
            startMs = startMs,
            endMs = endMs,
            label = label,
        )
        if (!seen.add(task.key)) return
        if (isTaskCached(cache, task)) return
        tasks.add(task)
    }

    private suspend fun loadMediaPlaylists(selectedFormat: Format?): List<HlsMediaPlaylist> {
        val now = System.currentTimeMillis()
        directMediaPlaylist?.let { direct ->
            if (direct.hasEndTag || now - directPlaylistFetchTime < 10_000L) {
                return listOf(direct)
            }
        }
        if (!masterLoaded || (directMediaPlaylist != null && !directMediaPlaylist!!.hasEndTag)) {
            val fetched = readAll(sourceUri) ?: return directMediaPlaylist?.let { listOf(it) } ?: emptyList()
            val parsed = runCatching {
                HlsPlaylistParser().parse(fetched.first, ByteArrayInputStream(fetched.second))
            }.getOrNull() ?: return directMediaPlaylist?.let { listOf(it) } ?: emptyList()
            if (parsed is HlsMediaPlaylist) {
                directMediaPlaylist = parsed
                directPlaylistFetchTime = now
                mediaPlaylists[fetched.first] = parsed
                playlistFetchTimes[fetched.first] = now
                return listOf(parsed)
            }
            master = parsed as? HlsMultivariantPlaylist ?: return emptyList()
            masterBaseUri = fetched.first.toString()
            masterLoaded = true
        }
        val selectedMaster = master ?: return emptyList()
        val variant = HlsVariantSelector.selectVariant(selectedMaster, selectedFormat) ?: return emptyList()
        val results = ArrayList<HlsMediaPlaylist>()
        val base = masterBaseUri ?: sourceUri.toString()

        // 1. Fetch video variant playlist
        val videoUri = resolve(base, variant.url.toString())
        if (videoUri != null) {
            val variantUrl = videoUri.toString()
            if (selectedVariantUrl != null && selectedVariantUrl != variantUrl) {
                // Quality switches should not retain older, potentially large media
                // playlists. Only the currently selected representation is needed.
                mediaPlaylists.clear()
                playlistFetchTimes.clear()
            }
            selectedVariantUrl = variantUrl
            fetchMediaPlaylist(videoUri)?.let { results.add(it) }
        }

        // Only the selected video variant is speculatively fetched. A demuxed audio
        // rendition is selected separately by the player; guessing its language or
        // group would spend bandwidth on media that is not actually playing.
        return results
    }

    private suspend fun fetchMediaPlaylist(uri: Uri): HlsMediaPlaylist? {
        val now = System.currentTimeMillis()
        val cached = mediaPlaylists[uri]
        val lastFetched = playlistFetchTimes[uri] ?: 0L
        if (cached != null && (cached.hasEndTag || now - lastFetched < 10_000L)) {
            return cached
        }
        val fetched = readAll(uri) ?: return cached
        val parsed = runCatching {
            HlsPlaylistParser().parse(fetched.first, ByteArrayInputStream(fetched.second))
        }.getOrNull() as? HlsMediaPlaylist ?: return cached
        mediaPlaylists[uri] = parsed
        mediaPlaylists[fetched.first] = parsed
        playlistFetchTimes[uri] = now
        playlistFetchTimes[fetched.first] = now
        return parsed
    }

    private suspend fun readAll(uri: Uri): Pair<Uri, ByteArray>? = withContext(Dispatchers.IO) {
        runCatching {
            val dataSource = upstreamFactory.createDataSource()
            try {
                dataSource.open(DataSpec(uri))
                val finalUri = dataSource.uri ?: uri
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val read = dataSource.read(buffer, 0, buffer.size)
                    if (read == C.RESULT_END_OF_INPUT) break
                    if (read <= 0) continue
                    output.write(buffer, 0, read)
                    if (output.size() > MAX_MANIFEST_BYTES) {
                        throw IOException("manifest exceeds size limit")
                    }
                }
                finalUri to output.toByteArray()
            } finally {
                dataSource.close()
            }
        }.getOrNull()
    }

    private fun resolve(base: String?, child: String?): Uri? {
        if (child.isNullOrBlank()) return null
        return runCatching {
            if (base.isNullOrBlank()) child.toUri() else UriUtil.resolveToUri(base, child)
        }.getOrNull()
    }

    companion object {
        private const val MAX_TASKS = 1024
    }
}

@UnstableApi
private class ProgressivePrefetchPlanner(
    private val sourceUri: Uri,
    private val upstreamFactory: HttpDataSource.Factory,
) : MediaPrefetchPlanner {
    private var totalLength = C.LENGTH_UNSET.toLong()
    private var rangeSupported = false
    private var probed = false
    private var nextProbeAtMs = 0L
    private var probeFailure: PrefetchStatus? = null

    override suspend fun buildPlan(
        cache: Cache,
        selectedFormat: Format?,
        currentMs: Long,
        bufferedMs: Long,
        targetMs: Long,
        durationMs: Long?,
        completedKeys: Set<String>,
    ): PrefetchPlan {
        ensureRangeSupport(cache)
        val failure = probeFailure
        if (failure != null) {
            return PrefetchPlan(emptyList(), PrefetchSupport.PROGRESSIVE_VOD, failure)
        }
        if (!rangeSupported || totalLength <= 0L || totalLength == C.LENGTH_UNSET.toLong()) {
            return PrefetchPlan(
                emptyList(),
                PrefetchSupport.PROGRESSIVE_VOD,
                PrefetchStatus.RANGE_UNSUPPORTED,
            )
        }

        val key = CacheKeyFactory.DEFAULT.buildCacheKey(
            DataSpec.Builder().setUri(sourceUri).build()
        )
        val duration = durationMs?.takeIf { it > 0L }
        val bytesPerMs = when {
            duration != null -> totalLength.toDouble() / duration.toDouble()
            selectedFormat != null && selectedFormat.bitrate > 0 ->
                selectedFormat.bitrate / 8_000.0
            else -> 0.0
        }
        val targetBytes = if (bytesPerMs > 0.0) {
            (targetMs * bytesPerMs * 1.2).toLong()
                .coerceIn(32L * MB, 1_500L * MB)
        } else {
            256L * MB
        }
        val currentByte = if (duration != null) {
            (currentMs.coerceAtLeast(0L).toDouble() / duration.toDouble() * totalLength)
                .toLong()
                .coerceIn(0L, totalLength)
        } else {
            contiguousCachedEnd(cache, key, 0L, totalLength)
        }
        val cacheFrontierByte = contiguousCachedEnd(cache, key, currentByte, totalLength)
        // Never lock the chunk the player is reading now or the one it will request
        // next. CacheDataSource blocks on a locked range until the writer completes,
        // so starting at the immediate frontier would make playback wait behind a
        // speculative reader instead of using its own connection. Two player chunks
        // of separation keep this pipeline ahead without competing for near-term data.
        // The playable buffer can be ahead of the cache frontier, but it is not a cache
        // lock boundary; using it here would add a second dead gap.
        // Use stable chunk boundaries so repeated plans address the same ranges. This
        // lets isTaskCached skip work that the player (or a previous prefetch) already
        // completed instead of re-downloading shifted, overlapping ranges.
        val startByte = progressivePrefetchStart(
            cacheFrontierByte = cacheFrontierByte,
            totalLength = totalLength,
        )
        val endByte = minOf(totalLength, currentByte + targetBytes).coerceAtLeast(startByte)

        val tasks = ArrayList<PrefetchTask>()
        var position = startByte
        // Estimate media time from the validated byte range only for ordering and
        // rolling-window bookkeeping. This is not a claim that the byte position maps
        // exactly to the decoder timeline, especially for variable-bitrate files.
        val msPerByte = when {
            duration != null && totalLength > 0L -> duration.toDouble() / totalLength
            bytesPerMs > 0.0 -> 1.0 / bytesPerMs
            else -> 5_000.0 / CHUNK_BYTES
        }
        fun estimatedMs(byte: Long): Long {
            val delta = (byte - currentByte).coerceAtLeast(0L)
            return currentMs + (delta * msPerByte).toLong()
        }
        while (position < endByte && tasks.size < MAX_TASKS) {
            val length = minOf(CHUNK_BYTES, endByte - position)
            val startMs = estimatedMs(position)
            val endMs = estimatedMs(position + length)
            val task = PrefetchTask(
                uriString = sourceUri.toString(),
                position = position,
                length = length,
                startMs = startMs,
                endMs = endMs,
                label = "progressive range",
            )
            if (!isTaskCached(cache, task)) {
                tasks.add(task)
            }
            position += length
        }
        return PrefetchPlan(tasks.sortedBy { it.startMs }, PrefetchSupport.PROGRESSIVE_VOD)
    }

    private suspend fun ensureRangeSupport(cache: Cache) {
        if (probed) return
        if (System.currentTimeMillis() < nextProbeAtMs) return
        probed = true
        probeFailure = null
        val key = CacheKeyFactory.DEFAULT.buildCacheKey(
            DataSpec.Builder().setUri(sourceUri).build()
        )
        val metadata = runCatching {
            ContentMetadata.getContentLength(cache.getContentMetadata(key))
        }.getOrNull()
        if (metadata != null && metadata > 0L && metadata != C.LENGTH_UNSET.toLong()) {
            totalLength = metadata
        }

        val dataSource = upstreamFactory.createDataSource()
        try {
            val probePosition = if (totalLength > 1L) 1L else 0L
            dataSource.open(
                DataSpec.Builder()
                    .setUri(sourceUri)
                    .setPosition(probePosition)
                    .setLength(1L)
                    .build()
            )
            val headers = dataSource.responseHeaders
            val contentRange = headers.entries
                .firstOrNull { it.key.equals("Content-Range", ignoreCase = true) }
                ?.value
                ?.firstOrNull()
            val match = contentRange?.let {
                Regex("""bytes\s+(\d+)-(\d+)/(\d+)""").find(it)
            }
            if (match == null) {
                rangeSupported = false
                probeFailure = PrefetchStatus.RANGE_UNSUPPORTED
                return
            }
            val start = match.groupValues[1].toLongOrNull()
            val total = match.groupValues[3].toLongOrNull()
            if (start == null || total == null || total <= 0L || start != probePosition) {
                rangeSupported = false
                probeFailure = PrefetchStatus.INVALID_RANGE_RESPONSE
                return
            }
            totalLength = total
            rangeSupported = true
        } catch (e: Exception) {
            rangeSupported = false
            val failure = classifyProbeFailure(e)
            probeFailure = failure
            // A temporary network or throttling failure should not disable prefetch
            // for the entire playback session. Retry the probe after a quiet period.
            if (failure == PrefetchStatus.NETWORK_LIMITED ||
                failure == PrefetchStatus.SERVER_THROTTLED ||
                failure == PrefetchStatus.UNKNOWN_FAILURE
            ) {
                probed = false
                nextProbeAtMs = System.currentTimeMillis() + 30_000L
            }
        } finally {
            runCatching { dataSource.close() }
        }
    }

    private fun classifyProbeFailure(e: Throwable): PrefetchStatus {
        val http = findHttpException(e)
        if (http != null) {
            return when (http.responseCode) {
                401, 403 -> PrefetchStatus.AUTH_OR_URL_FAILURE
                404, 410 -> PrefetchStatus.SOURCE_UNAVAILABLE
                416 -> PrefetchStatus.INVALID_RANGE_RESPONSE
                429 -> PrefetchStatus.SERVER_THROTTLED
                in 500..599 -> PrefetchStatus.NETWORK_LIMITED
                else -> PrefetchStatus.RANGE_UNSUPPORTED
            }
        }
        // A timeout or closed connection is not proof that the server rejects
        // ranges. Keep the probe retryable instead of disabling prefetch for the
        // rest of the session.
        return if (e is IOException) {
            PrefetchStatus.NETWORK_LIMITED
        } else {
            PrefetchStatus.RANGE_UNSUPPORTED
        }
    }

    companion object {
        /** One prefetch task maps one-to-one with a bounded player cache chunk. */
        private const val CHUNK_BYTES = ChunkedCacheDataSourceFactory.DEFAULT_CHUNK_SIZE
        private const val MAX_TASKS = 1024
    }
}

@UnstableApi
private class DashPrefetchPlanner(
    private val sourceUri: Uri,
    private val upstreamFactory: HttpDataSource.Factory,
) : MediaPrefetchPlanner {
    private var manifest: DashManifest? = null

    override suspend fun buildPlan(
        cache: Cache,
        selectedFormat: Format?,
        currentMs: Long,
        bufferedMs: Long,
        targetMs: Long,
        durationMs: Long?,
        completedKeys: Set<String>,
    ): PrefetchPlan {
        val loaded = manifest ?: loadManifest() ?: return PrefetchPlan(
            emptyList(),
            PrefetchSupport.MANIFEST_UNSUPPORTED,
            PrefetchStatus.SOURCE_UNAVAILABLE
        )
        if (loaded.dynamic) return PrefetchPlan(emptyList(), PrefetchSupport.LIVE_UNSUPPORTED)

        val periodIndex = (0 until loaded.periodCount)
            .lastOrNull { loaded.getPeriod(it).startMs <= currentMs }
            ?: 0
        val period = loaded.getPeriod(periodIndex)
        val periodDurationUs = loaded.getPeriodDurationUs(periodIndex)
        if (periodDurationUs <= 0L) {
            return PrefetchPlan(emptyList(), PrefetchSupport.FORMAT_UNSUPPORTED)
        }
        val videoRepresentation = selectRepresentation(period, selectedFormat)
            ?: return PrefetchPlan(emptyList(), PrefetchSupport.FORMAT_UNSUPPORTED)

        val baseUrl = videoRepresentation.baseUrls.firstOrNull()?.url
            ?: loaded.location?.toString()
            ?: sourceUri.toString()
        val tasks = ArrayList<PrefetchTask>()
        val seen = HashSet<String>()

        val periodStartUs = period.startMs * 1_000L
        val targetEndMs = currentMs + targetMs
        val gapMs = prefetchGapMs(targetMs)
        val startUs = (max(currentMs, bufferedMs) + gapMs) * 1_000L

        // Only the selected video representation is speculatively fetched. Audio
        // representation selection is independent and guessing it would risk
        // downloading a track the player never uses.
        collectRepresentationTasks(videoRepresentation, baseUrl, periodStartUs, periodDurationUs, startUs, targetEndMs, cache, seen, tasks, "dash video")

        return PrefetchPlan(tasks.sortedBy { it.startMs }, PrefetchSupport.DASH_VOD)
    }

    private fun collectRepresentationTasks(
        representation: Representation,
        baseUrl: String,
        periodStartUs: Long,
        periodDurationUs: Long,
        startUs: Long,
        targetEndMs: Long,
        cache: Cache,
        seen: MutableSet<String>,
        tasks: MutableList<PrefetchTask>,
        labelPrefix: String,
    ) {
        val index = representation.getIndex() ?: return
        representation.getInitializationUri()?.let { init ->
            addDashTask(init, representation, baseUrl, cache, seen, tasks, 0L, 0L, "$labelPrefix init")
        }

        val firstSeg = index.getFirstSegmentNum()
        var segmentNum = index.getSegmentNum(startUs - periodStartUs, periodDurationUs)
            .coerceAtLeast(firstSeg)
        var guard = 0
        while (guard++ < MAX_TASKS) {
            val relativeTimeUs = index.getTimeUs(segmentNum)
            if (relativeTimeUs == C.TIME_UNSET) break
            val segmentTimeUs = periodStartUs + relativeTimeUs
            val durationUs = index.getDurationUs(segmentNum, periodDurationUs)
            if (durationUs <= 0L) break
            val segmentStartMs = segmentTimeUs / 1_000L
            val segmentEndMs = (segmentTimeUs + durationUs) / 1_000L
            if (segmentStartMs >= targetEndMs) break
            if (segmentEndMs <= startUs / 1_000L) {
                segmentNum++
                continue
            }
            index.getSegmentUrl(segmentNum).let { rangedUri ->
                addDashTask(
                    rangedUri,
                    representation,
                    baseUrl,
                    cache,
                    seen,
                    tasks,
                    segmentStartMs,
                    segmentEndMs,
                    "$labelPrefix segment",
                )
            }
            segmentNum++
            if (tasks.size >= MAX_TASKS) break
        }
    }

    private fun addDashTask(
        rangedUri: androidx.media3.exoplayer.dash.manifest.RangedUri,
        representation: Representation,
        baseUrl: String,
        cache: Cache,
        seen: MutableSet<String>,
        tasks: MutableList<PrefetchTask>,
        startMs: Long,
        endMs: Long,
        label: String,
    ) {
        val uri = runCatching { rangedUri.resolveUri(baseUrl) }.getOrNull() ?: return
        val cacheKey = runCatching {
            DashUtil.resolveCacheKey(representation, rangedUri)
        }.getOrNull()
        val task = PrefetchTask(
            uriString = uri.toString(),
            position = rangedUri.start.coerceAtLeast(0L),
            length = rangedUri.length,
            startMs = startMs,
            endMs = endMs,
            label = label,
            cacheKey = cacheKey,
        )
        if (!seen.add(task.key)) return
        if (isTaskCached(cache, task)) return
        tasks.add(task)
    }

    private suspend fun loadManifest(): DashManifest? = withContext(Dispatchers.IO) {
        runCatching {
            val dataSource = upstreamFactory.createDataSource()
            try {
                dataSource.open(DataSpec(sourceUri))
                val finalUri = dataSource.uri ?: sourceUri
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val read = dataSource.read(buffer, 0, buffer.size)
                    if (read == C.RESULT_END_OF_INPUT) break
                    if (read <= 0) continue
                    output.write(buffer, 0, read)
                    if (output.size() > MAX_MANIFEST_BYTES) {
                        throw IOException("manifest exceeds size limit")
                    }
                }
                DashManifestParser().parse(finalUri, ByteArrayInputStream(output.toByteArray()))
                    .also { manifest = it }
            } finally {
                dataSource.close()
            }
        }.getOrNull()
    }

    private fun selectRepresentation(
        period: androidx.media3.exoplayer.dash.manifest.Period,
        selectedFormat: Format?,
    ): Representation? {
        val videoSets = period.adaptationSets.filter { it.type == C.TRACK_TYPE_VIDEO }
        val representations = videoSets.flatMap { it.representations }
        if (representations.isEmpty()) return null
        if (selectedFormat == null) {
            return representations.maxByOrNull { it.format.bitrate } ?: representations.first()
        }
        if (!selectedFormat.id.isNullOrBlank()) {
            representations.firstOrNull { it.format.id == selectedFormat.id }?.let { return it }
        }
        val width = selectedFormat.width
        val height = selectedFormat.height
        if (width > 0 && height > 0) {
            val exact = representations.filter { it.format.width == width && it.format.height == height }
            if (exact.isNotEmpty()) {
                return exact.minByOrNull {
                    abs(it.format.bitrate.coerceAtLeast(0) - selectedFormat.bitrate.coerceAtLeast(0))
                }
            }
        }
        if (height > 0) {
            val closeHeight = representations.filter { abs(it.format.height - height) <= 120 }
            if (closeHeight.isNotEmpty()) {
                return closeHeight.minByOrNull {
                    abs(it.format.bitrate.coerceAtLeast(0) - selectedFormat.bitrate.coerceAtLeast(0))
                }
            }
        }
        val bitrate = selectedFormat.bitrate
        if (bitrate > 0) {
            representations.minByOrNull {
                abs(it.format.bitrate.coerceAtLeast(0) - bitrate)
            }?.let { return it }
        }
        return representations.maxByOrNull { it.format.bitrate } ?: representations.first()
    }

    companion object {
        private const val MAX_TASKS = 1024
    }
}
