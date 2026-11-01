package com.lagradost.cloudstream3.ui.player.buffering

import androidx.media3.common.util.UnstableApi

/**
 * Pure ordering/deduplication policy for speculative work. Network execution and cache locking
 * stay in SmartPrefetchEngine; this class is intentionally testable without Android or Media3.
 */
@UnstableApi
internal object PrefetchTaskSelector {
    fun eligible(
        tasks: List<PrefetchTask>,
        desiredKeys: Set<String>,
        activeKeys: Set<String>,
        completedKeys: Set<String>,
        blockedKeys: Set<String>,
        backoffUntil: Map<String, Long>,
        nowMs: Long,
    ): List<PrefetchTask> {
        return tasks.asSequence()
            .filter { it.key in desiredKeys }
            .filter { it.key !in activeKeys }
            .filter { it.key !in completedKeys }
            .filter { it.key !in blockedKeys }
            .filter { (backoffUntil[it.key] ?: 0L) <= nowMs }
            .distinctBy { it.key }
            .sortedWith(compareBy<PrefetchTask> { it.startMs }.thenBy { it.endMs })
            .toList()
    }

    fun dispatchable(
        tasks: List<PrefetchTask>,
        desiredKeys: Set<String>,
        activeKeys: Set<String>,
        completedKeys: Set<String>,
        blockedKeys: Set<String>,
        backoffUntil: Map<String, Long>,
        nowMs: Long,
        activeCount: Int,
        maxConcurrent: Int,
    ): List<PrefetchTask> {
        val slots = (maxConcurrent.coerceAtLeast(1) - activeCount).coerceAtLeast(0)
        if (slots == 0) return emptyList()
        return eligible(
            tasks = tasks,
            desiredKeys = desiredKeys,
            activeKeys = activeKeys,
            completedKeys = completedKeys,
            blockedKeys = blockedKeys,
            backoffUntil = backoffUntil,
            nowMs = nowMs,
        ).take(slots)
    }
}


