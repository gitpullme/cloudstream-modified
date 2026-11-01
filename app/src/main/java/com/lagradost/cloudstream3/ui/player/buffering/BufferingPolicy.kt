package com.lagradost.cloudstream3.ui.player.buffering

import android.content.Context
import android.os.StatFs
import androidx.preference.PreferenceManager
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.mvvm.logError

const val MB = 1024L * 1024L

data class LegacyBufferConfig(
    val targetBufferBytes: Long,
    val maxBufferMs: Long,
    val diskCacheBytes: Long,
)

data class LegacyBufferSettings(
    val ramMb: Int,
    val timeSec: Int,
    val diskMb: Int,
) {
    companion object {
        fun fromMap(
            values: Map<String, *>,
            ramKey: String,
            timeKey: String,
            diskKey: String,
        ): LegacyBufferSettings {
            return LegacyBufferSettings(
                ramMb = values[ramKey].asSafeInt(0),
                timeSec = values[timeKey].asSafeInt(0),
                diskMb = values[diskKey].asSafeInt(0),
            )
        }
    }
}

data class AggressiveBufferSettings(
    val enabled: Boolean,
    val minutes: Int,
    val minutesWereValid: Boolean,
    val maxParallelRequests: Int = FIXED_PARALLEL_REQUESTS,
) {
    companion object {
        const val ENABLED_KEY = "aggressive_forward_buffer_enabled"
        const val MINUTES_KEY = "aggressive_forward_buffer_minutes"

        const val MIN_MINUTES = 1
        const val MAX_MINUTES = 10
        const val DEFAULT_MINUTES = 5

        /**
         * Aggressive prefetch deliberately has a fixed, small connection budget.
         * Two readers are enough to hide segment latency without starving playback
         * or causing cache-write contention on constrained devices.
         */
        const val FIXED_PARALLEL_REQUESTS = 2

        /** Retained only so previously persisted values remain harmless. */
        const val MAX_PARALLEL_REQUESTS_KEY = "aggressive_forward_buffer_max_parallel_requests"

        fun sanitizeParallelRequests(@Suppress("UNUSED_PARAMETER") value: Int?): Int {
            return FIXED_PARALLEL_REQUESTS
        }

        fun sanitizeMinutes(value: Int?): Int {
            return (value ?: DEFAULT_MINUTES).coerceIn(MIN_MINUTES, MAX_MINUTES)
        }

        fun shouldPromptForDuration(minutesWereStored: Boolean, minutes: Int?): Boolean {
            return !minutesWereStored || minutes == null || minutes !in MIN_MINUTES..MAX_MINUTES
        }

        fun canEnableWithSelection(minutes: Int?): Boolean {
            return minutes != null && minutes in MIN_MINUTES..MAX_MINUTES
        }

        fun fromMap(values: Map<String, *>): AggressiveBufferSettings {
            val rawMinutes = values[MINUTES_KEY].asSafeIntOrNull()
            val rawParallelRequests = values[MAX_PARALLEL_REQUESTS_KEY].asSafeIntOrNull()
            return AggressiveBufferSettings(
                enabled = values[ENABLED_KEY].asSafeBoolean(false),
                minutes = sanitizeMinutes(rawMinutes),
                minutesWereValid = rawMinutes != null &&
                        rawMinutes in MIN_MINUTES..MAX_MINUTES,
                maxParallelRequests = sanitizeParallelRequests(rawParallelRequests),
            )
        }
    }
}

data class DeviceBufferSnapshot(
    val maxHeapBytes: Long,
    val usedHeapBytes: Long,
    val availableStorageBytes: Long,
)

data class CacheQuota(
    val bytes: Long,
    val storageLimited: Boolean,
)

sealed interface PlayerBufferingPolicy {
    data class Legacy(
        val targetBufferBytes: Long,
        val maxBufferMs: Long,
        val diskCacheBytes: Long,
    ) : PlayerBufferingPolicy

    data class Aggressive(
        val targetMinutes: Int,
        val targetForwardMs: Long,
        val startupBufferMs: Long,
        val rebufferBufferMs: Long,
        val pausedBufferMs: Long,
        val liveBufferMs: Long,
        val memoryBudgetBytes: Long,
        val cacheQuotaBytes: Long,
        val availableStorageBytes: Long,
        val cacheStorageLimited: Boolean,
        val maxParallelRequests: Int,
        val fallbackLegacy: LegacyBufferConfig,
    ) : PlayerBufferingPolicy

    fun legacyConfig(): LegacyBufferConfig = when (this) {
        is Legacy -> LegacyBufferConfig(targetBufferBytes, maxBufferMs, diskCacheBytes)
        is Aggressive -> fallbackLegacy
    }
}

object AggressiveMemoryBudget {
    private const val HEAP_FRACTION = 0.60
    private const val MIN_BUDGET_BYTES = 8L * MB
    private const val MAX_BUDGET_BYTES = 512L * MB

    fun calculate(maxHeapBytes: Long, usedHeapBytes: Long): Long {
        if (maxHeapBytes <= 0L) return MIN_BUDGET_BYTES
        val headroom = (maxHeapBytes - usedHeapBytes).coerceAtLeast(0L)
        return (headroom * HEAP_FRACTION).toLong()
            .coerceIn(MIN_BUDGET_BYTES, MAX_BUDGET_BYTES)
    }
}

object AggressiveCacheQuota {
    private const val ESTIMATED_BITRATE_BPS = 40_000_000L
    private const val PAYLOAD_OVERHEAD = 1.2
    private const val MIN_CACHE_BYTES = 256L * MB
    private const val MAX_CACHE_BYTES = 6L * 1024L * MB
    private const val MIN_USABLE_STORAGE = 64L * MB
    private const val STORAGE_RESERVE_BYTES = 512L * MB
    private const val STORAGE_RESERVE_FRACTION = 0.20

    fun estimateTargetPayloadBytes(targetMinutes: Int): Long {
        val seconds = targetMinutes.coerceIn(
            AggressiveBufferSettings.MIN_MINUTES,
            AggressiveBufferSettings.MAX_MINUTES
        ) * 60L
        return (seconds * ESTIMATED_BITRATE_BPS / 8.0 * PAYLOAD_OVERHEAD).toLong()
    }

    fun calculateForBitrate(
        targetMinutes: Int,
        bitrateBps: Long?,
        availableStorageBytes: Long,
    ): CacheQuota {
        if (bitrateBps == null || bitrateBps <= 0L) {
            return calculate(targetMinutes, availableStorageBytes)
        }
        val seconds = targetMinutes.coerceIn(
            AggressiveBufferSettings.MIN_MINUTES,
            AggressiveBufferSettings.MAX_MINUTES
        ) * 60L
        val estimated = (seconds * bitrateBps / 8.0 * PAYLOAD_OVERHEAD).toLong()
        return constrain(estimated, availableStorageBytes)
    }

    fun calculate(targetMinutes: Int, availableStorageBytes: Long): CacheQuota {
        return constrain(estimateTargetPayloadBytes(targetMinutes), availableStorageBytes)
    }

    private fun constrain(estimatedBytes: Long, availableStorageBytes: Long): CacheQuota {
        val desired = estimatedBytes.coerceIn(MIN_CACHE_BYTES, MAX_CACHE_BYTES)

        if (availableStorageBytes <= 0L) {
            return CacheQuota(unknownStorageFallback(desired), storageLimited = true)
        }

        val reserve = maxOf(
            STORAGE_RESERVE_BYTES,
            (availableStorageBytes * STORAGE_RESERVE_FRACTION).toLong()
        )
        val usable = availableStorageBytes - reserve
        if (usable < MIN_USABLE_STORAGE) {
            return CacheQuota(0L, storageLimited = true)
        }

        return CacheQuota(
            bytes = minOf(desired, usable),
            storageLimited = desired > usable
        )
    }

    private fun unknownStorageFallback(desired: Long): Long {
        return minOf(desired, 256L * MB)
    }
}

object BufferingPolicyResolver {
    private const val STARTUP_BUFFER_MS = 3_000L
    private const val REBUFFER_BUFFER_MS = 5_000L
    private const val PAUSED_BUFFER_MS = 30_000L
    private const val LIVE_BUFFER_MS = 60_000L

    fun resolve(
        legacy: LegacyBufferSettings,
        aggressive: AggressiveBufferSettings,
        device: DeviceBufferSnapshot,
    ): PlayerBufferingPolicy {
        val legacyConfig = LegacyBufferConfig(
            targetBufferBytes = legacy.ramMb.coerceAtLeast(0) * MB,
            maxBufferMs = legacy.timeSec.coerceAtLeast(0) * 1_000L,
            diskCacheBytes = legacy.diskMb.coerceAtLeast(0) * MB,
        )

        if (!aggressive.enabled) {
            return PlayerBufferingPolicy.Legacy(
                targetBufferBytes = legacyConfig.targetBufferBytes,
                maxBufferMs = legacyConfig.maxBufferMs,
                diskCacheBytes = legacyConfig.diskCacheBytes,
            )
        }

        val minutes = AggressiveBufferSettings.sanitizeMinutes(aggressive.minutes)
        val quota = AggressiveCacheQuota.calculate(minutes, device.availableStorageBytes)

        return PlayerBufferingPolicy.Aggressive(
            targetMinutes = minutes,
            targetForwardMs = minutes * 60_000L,
            startupBufferMs = STARTUP_BUFFER_MS,
            rebufferBufferMs = REBUFFER_BUFFER_MS,
            pausedBufferMs = PAUSED_BUFFER_MS,
            liveBufferMs = LIVE_BUFFER_MS,
            memoryBudgetBytes = AggressiveMemoryBudget.calculate(
                device.maxHeapBytes,
                device.usedHeapBytes
            ),
            cacheQuotaBytes = quota.bytes,
            availableStorageBytes = device.availableStorageBytes,
            cacheStorageLimited = quota.storageLimited,
            maxParallelRequests = AggressiveBufferSettings.sanitizeParallelRequests(
                aggressive.maxParallelRequests
            ),
            fallbackLegacy = legacyConfig,
        )
    }

    fun resolve(context: Context): PlayerBufferingPolicy {
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        val values = preferences.all
        val legacy = LegacyBufferSettings.fromMap(
            values = values,
            ramKey = context.getString(R.string.video_buffer_size_key),
            timeKey = context.getString(R.string.video_buffer_length_key),
            diskKey = context.getString(R.string.video_buffer_disk_key),
        )
        val aggressive = AggressiveBufferSettings.fromMap(values)
        val runtime = Runtime.getRuntime()
        val availableStorage = try {
            StatFs(context.cacheDir.absolutePath).availableBytes
        } catch (e: Exception) {
            logError(e)
            -1L
        }

        return resolve(
            legacy = legacy,
            aggressive = aggressive,
            device = DeviceBufferSnapshot(
                maxHeapBytes = runtime.maxMemory(),
                usedHeapBytes = runtime.totalMemory() - runtime.freeMemory(),
                availableStorageBytes = availableStorage,
            )
        )
    }
}

internal fun Any?.asSafeIntOrNull(): Int? {
    return when (this) {
        is Int -> this
        is Long -> if (this in Int.MIN_VALUE..Int.MAX_VALUE) toInt() else null
        is Number -> toInt()
        is String -> toIntOrNull()
        else -> null
    }
}

internal fun Any?.asSafeInt(default: Int): Int = asSafeIntOrNull() ?: default

internal fun Any?.asSafeBoolean(default: Boolean): Boolean {
    return when (this) {
        is Boolean -> this
        is String -> when (lowercase()) {
            "true" -> true
            "false" -> false
            else -> default
        }
        else -> default
    }
}


