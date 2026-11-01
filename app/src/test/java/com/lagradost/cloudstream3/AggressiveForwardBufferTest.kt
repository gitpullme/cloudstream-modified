package com.lagradost.cloudstream3

import com.lagradost.cloudstream3.ui.player.buffering.AggressiveBufferController
import com.lagradost.cloudstream3.ui.player.buffering.AggressiveBufferSettings
import com.lagradost.cloudstream3.ui.player.buffering.AggressiveCacheQuota
import com.lagradost.cloudstream3.ui.player.buffering.AggressiveMemoryBudget
import com.lagradost.cloudstream3.ui.player.buffering.BufferTargetReason
import com.lagradost.cloudstream3.ui.player.buffering.BufferingPolicyResolver
import com.lagradost.cloudstream3.ui.player.buffering.DeviceBufferSnapshot
import com.lagradost.cloudstream3.ui.player.buffering.LegacyBufferSettings
import com.lagradost.cloudstream3.ui.player.buffering.MB
import com.lagradost.cloudstream3.ui.player.buffering.PlayerBufferingPolicy
import com.lagradost.cloudstream3.ui.player.buffering.PlayerCacheManager
import com.lagradost.cloudstream3.ui.player.buffering.PlayerCacheSelection
import com.lagradost.cloudstream3.ui.player.buffering.AggressiveBufferDiagnosticsSnapshot
import com.lagradost.cloudstream3.ui.player.buffering.PrefetchRuntimeSnapshot
import com.lagradost.cloudstream3.ui.player.buffering.PrefetchStatus
import com.lagradost.cloudstream3.ui.player.buffering.PrefetchSupport
import com.lagradost.cloudstream3.ui.player.buffering.PrefetchTask
import com.lagradost.cloudstream3.ui.player.buffering.PrefetchTaskSelector
import com.lagradost.cloudstream3.ui.player.buffering.HlsVariantSelector
import com.lagradost.cloudstream3.ui.player.buffering.ChunkedCacheDataSourceFactory
import com.lagradost.cloudstream3.ui.player.buffering.progressivePrefetchStart
import com.lagradost.cloudstream3.ui.player.buffering.shouldKeepPrefetchTask
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
import com.lagradost.cloudstream4.PlayerPreferences
import com.mihon.common.preference.PreferenceData
import com.mihon.common.preference.PreferenceStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@UnstableApi
class AggressiveForwardBufferTest {
    private val device = DeviceBufferSnapshot(
        maxHeapBytes = 512L * MB,
        usedHeapBytes = 128L * MB,
        availableStorageBytes = 100_000L * MB,
    )

    private val legacy = LegacyBufferSettings(ramMb = 300, timeSec = 300, diskMb = 300)

    private fun aggressive(minutes: Int = 5, enabled: Boolean = true) =
        AggressiveBufferSettings(
            enabled = enabled,
            minutes = minutes,
            minutesWereValid = minutes in 1..10,
        )

    private fun aggressivePolicy(minutes: Int = 5): PlayerBufferingPolicy.Aggressive {
        return BufferingPolicyResolver.resolve(
            legacy = legacy,
            aggressive = aggressive(minutes),
            device = device,
        ) as PlayerBufferingPolicy.Aggressive
    }

    @Test
    fun defaultsAreDisabledAndFiveMinutes() {
        val settings = AggressiveBufferSettings.fromMap(emptyMap<String, Any?>())

        assertFalse(settings.enabled)
        assertEquals(5, settings.minutes)
        assertFalse(settings.minutesWereValid)
    }

    @Test
    fun validMinutesAreAcceptedAndInvalidValuesAreClamped() {
        for (minutes in 1..10) {
            val settings = AggressiveBufferSettings.fromMap(
                mapOf(AggressiveBufferSettings.MINUTES_KEY to minutes)
            )
            assertEquals(minutes, settings.minutes)
            assertTrue(settings.minutesWereValid)
        }

        assertEquals(1, AggressiveBufferSettings.sanitizeMinutes(0))
        assertEquals(1, AggressiveBufferSettings.sanitizeMinutes(-5))
        assertEquals(10, AggressiveBufferSettings.sanitizeMinutes(11))
        assertEquals(5, AggressiveBufferSettings.sanitizeMinutes(null))
        assertEquals(
            5,
            AggressiveBufferSettings.fromMap(
                mapOf(AggressiveBufferSettings.MINUTES_KEY to "not-a-number")
            ).minutes
        )
    }

    @Test
    fun firstActivationRequiresDurationButStoredSelectionDoesNot() {
        assertTrue(AggressiveBufferSettings.shouldPromptForDuration(false, 5))
        assertTrue(AggressiveBufferSettings.shouldPromptForDuration(true, 0))
        assertTrue(AggressiveBufferSettings.shouldPromptForDuration(true, 11))
        assertFalse(AggressiveBufferSettings.shouldPromptForDuration(true, 5))
        assertFalse(AggressiveBufferSettings.canEnableWithSelection(null))
        assertTrue(AggressiveBufferSettings.canEnableWithSelection(1))
        assertTrue(AggressiveBufferSettings.canEnableWithSelection(10))
    }

    @Test
    fun toggleAndDurationPersistIndependentlyAndDoNotTouchLegacyKeys() {
        val store = MutablePreferenceStore(
            mutableMapOf(
                "video_buffer_disk_key" to 300,
                "video_buffer_size_key" to 300,
                "video_buffer_length_key" to 300,
            )
        )
        val preferences = PlayerPreferences(store)

        assertFalse(preferences.aggressiveBufferEnabled.get())
        assertEquals(5, preferences.aggressiveBufferMinutes.get())

        preferences.aggressiveBufferMinutes.set(7)
        preferences.aggressiveBufferEnabled.set(true)
        assertTrue(preferences.aggressiveBufferEnabled.get())
        assertEquals(
            7,
            preferences.aggressiveBufferMinutes.get()
        )

        preferences.aggressiveBufferEnabled.set(false)
        assertFalse(preferences.aggressiveBufferEnabled.get())
        assertEquals(7, preferences.aggressiveBufferMinutes.get())

        preferences.aggressiveBufferEnabled.set(true)
        assertTrue(preferences.aggressiveBufferEnabled.get())
        assertEquals(300, store.values["video_buffer_disk_key"])
        assertEquals(300, store.values["video_buffer_size_key"])
        assertEquals(300, store.values["video_buffer_length_key"])
    }

    @Test
    fun disabledModeResolvesToExactlyTheLegacyConfiguration() {
        val policy = BufferingPolicyResolver.resolve(
            legacy = legacy,
            aggressive = aggressive(enabled = false),
            device = device,
        ) as PlayerBufferingPolicy.Legacy

        assertEquals(300L * MB, policy.targetBufferBytes)
        assertEquals(300_000L, policy.maxBufferMs)
        assertEquals(300L * MB, policy.diskCacheBytes)
    }

    @Test
    fun enabledModeUsesItsOwnTargetMemoryAndCache() {
        val policy = aggressivePolicy()

        assertEquals(5, policy.targetMinutes)
        assertEquals(300_000L, policy.targetForwardMs)
        assertEquals(3_000L, policy.startupBufferMs)
        assertEquals(5_000L, policy.rebufferBufferMs)
        assertEquals(30_000L, policy.pausedBufferMs)
        assertEquals(60_000L, policy.liveBufferMs)
        assertTrue(policy.memoryBudgetBytes > 0L)
        assertTrue(policy.memoryBudgetBytes <= 512L * MB)
        assertTrue(policy.cacheQuotaBytes > 300L * MB)
        assertTrue(policy.cacheQuotaBytes <= 6L * 1024L * MB)
        assertEquals(300L * MB, policy.fallbackLegacy.targetBufferBytes)
        assertEquals(300_000L, policy.fallbackLegacy.maxBufferMs)
        assertEquals(300L * MB, policy.fallbackLegacy.diskCacheBytes)
    }

    @Test
    fun legacyDurationAndDiskValuesDoNotOverrideAggressivePolicy() {
        val shortLegacy = LegacyBufferSettings(ramMb = 0, timeSec = 60, diskMb = 50)
        val policy = BufferingPolicyResolver.resolve(
            legacy = shortLegacy,
            aggressive = aggressive(5),
            device = device,
        ) as PlayerBufferingPolicy.Aggressive

        assertEquals(300_000L, policy.targetForwardMs)
        assertTrue(policy.memoryBudgetBytes != 0L)
        assertTrue(policy.cacheQuotaBytes > 50L * MB)
        assertEquals(60_000L, policy.fallbackLegacy.maxBufferMs)
    }

    @Test
    fun everyMinuteSelectionMapsToItsTimelineTarget() {
        for (minutes in 1..10) {
            assertEquals(minutes * 60_000L, aggressivePolicy(minutes).targetForwardMs)
        }
    }

    @Test
    fun invalidStoredMinutesAreClampedBeforePlayerConfiguration() {
        val policy = BufferingPolicyResolver.resolve(
            legacy = legacy,
            aggressive = AggressiveBufferSettings.fromMap(
                mapOf(
                    AggressiveBufferSettings.ENABLED_KEY to true,
                    AggressiveBufferSettings.MINUTES_KEY to 99,
                )
            ),
            device = device,
        ) as PlayerBufferingPolicy.Aggressive

        assertEquals(10, policy.targetMinutes)
        assertEquals(600_000L, policy.targetForwardMs)
    }

    @Test
    fun memoryBudgetIsBoundedInBothDirections() {
        assertEquals(8L * MB, AggressiveMemoryBudget.calculate(100L * MB, 100L * MB))
        assertEquals(512L * MB, AggressiveMemoryBudget.calculate(8_000L * MB, 0L))
        assertTrue(AggressiveMemoryBudget.calculate(512L * MB, 128L * MB) <= 512L * MB)
    }

    @Test
    fun cacheQuotaIsBoundedAndStorageAware() {
        val normal = AggressiveCacheQuota.calculate(5, 100_000L * MB)
        assertTrue(normal.bytes <= 6L * 1024L * MB)
        assertFalse(normal.storageLimited)

        val highBitrate = AggressiveCacheQuota.calculateForBitrate(
            5,
            100_000_000L,
            100_000L * MB,
        )
        assertTrue(highBitrate.bytes > normal.bytes)
        assertTrue(highBitrate.bytes <= 6L * 1024L * MB)

        val lowStorage = AggressiveCacheQuota.calculate(5, 500L * MB)
        assertEquals(0L, lowStorage.bytes)
        assertTrue(lowStorage.storageLimited)

        val unknownStorage = AggressiveCacheQuota.calculate(5, -1L)
        assertTrue(unknownStorage.bytes > 0L)
        assertTrue(unknownStorage.storageLimited)
    }

    @Test
    fun aggressiveAndLegacyUseDifferentDirectoriesAndDatabases() {
        val legacyPolicy = BufferingPolicyResolver.resolve(
            legacy = legacy,
            aggressive = aggressive(enabled = false),
            device = device,
        )
        val legacyCache = PlayerCacheSelection.forPolicy(legacyPolicy, online = true)
        val aggressiveCache = PlayerCacheSelection.forPolicy(aggressivePolicy(), online = true)

        assertNotNull(legacyCache)
        assertNotNull(aggressiveCache)
        assertEquals(PlayerCacheManager.NORMAL_DIRECTORY, legacyCache?.directoryName)
        assertEquals(PlayerCacheManager.NORMAL_DATABASE, legacyCache?.databaseName)
        assertEquals(300L * MB, legacyCache?.quotaBytes)
        assertEquals(PlayerCacheManager.AGGRESSIVE_DIRECTORY, aggressiveCache?.directoryName)
        assertEquals(PlayerCacheManager.AGGRESSIVE_DATABASE, aggressiveCache?.databaseName)
        assertTrue(aggressiveCache!!.quotaBytes > 300L * MB)
        assertTrue(legacyCache!!.directoryName != aggressiveCache.directoryName)
        assertTrue(legacyCache.databaseName != aggressiveCache.databaseName)
        assertNull(PlayerCacheSelection.forPolicy(aggressivePolicy(), online = false))
    }

    @Test
    fun controllerContinuouslyTargetsForwardMediaTime() {
        val controller = AggressiveBufferController(aggressivePolicy())

        assertTrue(
            controller.shouldContinueLoading(
                bufferedForwardMs = 299_999,
                allocatedBytes = 20L * MB,
                playWhenReady = true,
                isLive = false,
                remainingMediaMs = 60 * 60_000L,
            )
        )
        assertFalse(
            controller.shouldContinueLoading(
                bufferedForwardMs = 300_000,
                allocatedBytes = 0L,
                playWhenReady = true,
                isLive = false,
                remainingMediaMs = 60 * 60_000L,
            )
        )
    }

    @Test
    fun loadingContinuesPastLegacyBytesButStopsAtMemoryGuard() {
        val largeHeapPolicy = BufferingPolicyResolver.resolve(
            legacy = legacy,
            aggressive = aggressive(),
            device = DeviceBufferSnapshot(
                maxHeapBytes = 2_000L * MB,
                usedHeapBytes = 0L,
                availableStorageBytes = 100_000L * MB,
            ),
        ) as PlayerBufferingPolicy.Aggressive
        val controller = AggressiveBufferController(largeHeapPolicy)

        assertTrue(largeHeapPolicy.memoryBudgetBytes > 300L * MB)
        assertTrue(
            controller.shouldContinueLoading(
                bufferedForwardMs = 10_000,
                allocatedBytes = 300L * MB,
                playWhenReady = true,
                isLive = false,
                remainingMediaMs = null,
            )
        )
        assertFalse(
            controller.shouldContinueLoading(
                bufferedForwardMs = 10_000,
                allocatedBytes = controller.policy.memoryBudgetBytes,
                playWhenReady = true,
                isLive = false,
                remainingMediaMs = null,
            )
        )
    }

    @Test
    fun windowIsBoundedNearEndLiveAndWhilePaused() {
        val controller = AggressiveBufferController(aggressivePolicy())

        val end = controller.effectiveTarget(
            playWhenReady = true,
            isLive = false,
            remainingMediaMs = 120_000L,
        )
        assertEquals(120_000L, end.targetMs)
        assertTrue(BufferTargetReason.END_OF_MEDIA in end.reasons)

        val live = controller.effectiveTarget(
            playWhenReady = true,
            isLive = true,
            remainingMediaMs = null,
        )
        assertEquals(60_000L, live.targetMs)
        assertTrue(BufferTargetReason.LIVE_WINDOW in live.reasons)

        val paused = controller.effectiveTarget(
            playWhenReady = false,
            isLive = false,
            remainingMediaMs = null,
        )
        assertEquals(30_000L, paused.targetMs)
        assertTrue(BufferTargetReason.PAUSED in paused.reasons)

        val resumed = controller.effectiveTarget(
            playWhenReady = true,
            isLive = false,
            remainingMediaMs = null,
        )
        assertEquals(300_000L, resumed.targetMs)
    }

    @Test
    fun startupAndRebufferThresholdsDoNotWaitForTheFullTarget() {
        val controller = AggressiveBufferController(aggressivePolicy())

        assertFalse(
            controller.shouldStartPlayback(
                bufferedForwardMs = 2_999,
                allocatedBytes = 0L,
                playbackSpeed = 1f,
                rebuffering = false,
                playWhenReady = true,
                isLive = false,
                remainingMediaMs = null,
            )
        )
        assertTrue(
            controller.shouldStartPlayback(
                bufferedForwardMs = 3_000,
                allocatedBytes = 0L,
                playbackSpeed = 1f,
                rebuffering = false,
                playWhenReady = true,
                isLive = false,
                remainingMediaMs = null,
            )
        )
        assertFalse(
            controller.shouldStartPlayback(
                bufferedForwardMs = 5_000,
                allocatedBytes = 0L,
                playbackSpeed = 2f,
                rebuffering = false,
                playWhenReady = true,
                isLive = false,
                remainingMediaMs = null,
            )
        )
        assertFalse(
            controller.shouldStartPlayback(
                bufferedForwardMs = 4_999,
                allocatedBytes = 0L,
                playbackSpeed = 1f,
                rebuffering = true,
                playWhenReady = true,
                isLive = false,
                remainingMediaMs = null,
            )
        )
        assertTrue(
            controller.shouldStartPlayback(
                bufferedForwardMs = 5_000,
                allocatedBytes = 0L,
                playbackSpeed = 1f,
                rebuffering = true,
                playWhenReady = true,
                isLive = false,
                remainingMediaMs = null,
            )
        )
    }

    @Test
    fun memoryPressureCannotDeadlockStartup() {
        val controller = AggressiveBufferController(aggressivePolicy())

        assertTrue(
            controller.shouldStartPlayback(
                bufferedForwardMs = 100,
                allocatedBytes = controller.policy.memoryBudgetBytes,
                playbackSpeed = 1f,
                rebuffering = true,
                playWhenReady = true,
                isLive = false,
                remainingMediaMs = null,
            )
        )
    }

    @Test
    fun seekingRecomputesTheWindowFromTheNewPlayhead() {
        val controller = AggressiveBufferController(aggressivePolicy())
        val afterForwardSeek = controller.effectiveTarget(
            playWhenReady = true,
            isLive = false,
            remainingMediaMs = 120_000L,
        )

        assertEquals(120_000L, afterForwardSeek.targetMs)
        assertEquals(
            90_000L,
            AggressiveBufferController.forwardBufferedMs(
                currentPositionMs = 210_000L,
                bufferedPositionMs = 300_000L,
            )
        )
        assertEquals(
            0L,
            AggressiveBufferController.forwardBufferedMs(
                currentPositionMs = 310_000L,
                bufferedPositionMs = 300_000L,
            )
        )
    }

    @Test
    fun bitrateChangesReplaceStaleEstimates() {
        val controller = AggressiveBufferController(aggressivePolicy())

        controller.updateBitrate(1_000_000L)
        assertEquals(1_000_000L, controller.bitrateEstimateBps)
        controller.updateBitrate(20_000_000L)
        assertEquals(20_000_000L, controller.bitrateEstimateBps)
        controller.updateBitrate(null)
        assertNull(controller.bitrateEstimateBps)
    }

    @Test
    fun parallelPrefetchIsAlwaysFixedAtTwoRegardlessOfStoredValues() {
        assertEquals(
            2,
            AggressiveBufferSettings.fromMap(emptyMap<String, Any?>()).maxParallelRequests
        )
        for (stored in listOf(0, 1, 3, 7, 8, 99, -4)) {
            val settings = AggressiveBufferSettings.fromMap(
                mapOf(AggressiveBufferSettings.MAX_PARALLEL_REQUESTS_KEY to stored)
            )
            assertEquals(2, settings.maxParallelRequests)
        }
        assertEquals(
            2,
            AggressiveBufferSettings.fromMap(
                mapOf(AggressiveBufferSettings.MAX_PARALLEL_REQUESTS_KEY to "invalid")
            ).maxParallelRequests
        )
        assertEquals(2, AggressiveBufferSettings.sanitizeParallelRequests(null))
        assertEquals(2, AggressiveBufferSettings.sanitizeParallelRequests(7))
    }

    @Test
    fun policyAlwaysUsesTwoParallelPrefetches() {
        val policy = BufferingPolicyResolver.resolve(
            legacy = legacy,
            aggressive = AggressiveBufferSettings(
                enabled = true,
                minutes = 5,
                minutesWereValid = true,
                maxParallelRequests = 7,
            ),
            device = device,
        ) as PlayerBufferingPolicy.Aggressive
        assertEquals(2, policy.maxParallelRequests)
        assertEquals(2, AggressiveBufferSettings.FIXED_PARALLEL_REQUESTS)

        // Turning aggressive mode off must not alter the dormant legacy values.
        val disabled = BufferingPolicyResolver.resolve(
            legacy = legacy,
            aggressive = AggressiveBufferSettings(
                enabled = false,
                minutes = 5,
                minutesWereValid = true,
                maxParallelRequests = 7,
            ),
            device = device,
        )
        assertTrue(disabled is PlayerBufferingPolicy.Legacy)
        assertEquals(legacy.ramMb * 1024L * 1024L, disabled.legacyConfig().targetBufferBytes)
    }

    @Test
    fun selectorDeduplicatesAndPrioritizesNearTermWork() {
        val tasks = listOf(
            PrefetchTask("https://example/seg4", 0, 10, 4_000, 8_000, "far"),
            PrefetchTask("https://example/seg2", 0, 10, 2_000, 4_000, "near"),
            PrefetchTask("https://example/seg2", 0, 10, 2_000, 4_000, "duplicate"),
            PrefetchTask("https://example/seg3", 0, 10, 3_000, 6_000, "middle"),
        )
        val selected = PrefetchTaskSelector.eligible(
            tasks = tasks,
            desiredKeys = tasks.map { it.key }.toSet(),
            activeKeys = emptySet(),
            completedKeys = emptySet(),
            blockedKeys = emptySet(),
            backoffUntil = emptyMap(),
            nowMs = 0L,
        )

        assertEquals(3, selected.size)
        assertEquals(2_000L, selected[0].startMs)
        assertEquals(3_000L, selected[1].startMs)
        assertEquals(4_000L, selected[2].startMs)
    }

    @Test
    fun prefetchTaskIdentityIncludesTheMedia3CacheKey() {
        val hls = PrefetchTask("https://example/segment", 0, 10, 1_000, 2_000, "hls")
        val dash = PrefetchTask(
            "https://example/segment",
            0,
            10,
            1_000,
            2_000,
            "dash",
            cacheKey = "representation-key",
        )
        assertTrue(hls.key != dash.key)
        assertTrue(dash.key.contains("representation-key"))
    }

    @Test
    fun dispatchableWorkNeverExceedsTheConfiguredNumberOfSlots() {
        val tasks = (1..8).map { index ->
            PrefetchTask("https://example/$index", 0, 10, index * 1_000L, index * 1_000L, "task$index")
        }
        val keys = tasks.map { it.key }.toSet()

        for (limit in 1..8) {
            val selected = PrefetchTaskSelector.dispatchable(
                tasks = tasks,
                desiredKeys = keys,
                activeKeys = emptySet(),
                completedKeys = emptySet(),
                blockedKeys = emptySet(),
                backoffUntil = emptyMap(),
                nowMs = 0L,
                activeCount = 0,
                maxConcurrent = limit,
            )
            assertEquals(limit, selected.size)
        }

        val remaining = PrefetchTaskSelector.dispatchable(
            tasks = tasks,
            desiredKeys = keys,
            activeKeys = setOf(tasks[0].key, tasks[1].key),
            completedKeys = emptySet(),
            blockedKeys = emptySet(),
            backoffUntil = emptyMap(),
            nowMs = 0L,
            activeCount = 2,
            maxConcurrent = 3,
        )
        assertEquals(1, remaining.size)
        assertEquals("task3", remaining.single().label)
    }

    @Test
    fun selectorExcludesActiveCompletedBlockedAndBackedOffWork() {
        val tasks = listOf(
            PrefetchTask("https://example/a", 0, 10, 1_000, 2_000, "active"),
            PrefetchTask("https://example/b", 0, 10, 2_000, 3_000, "completed"),
            PrefetchTask("https://example/c", 0, 10, 3_000, 4_000, "blocked"),
            PrefetchTask("https://example/d", 0, 10, 4_000, 5_000, "backoff"),
            PrefetchTask("https://example/e", 0, 10, 5_000, 6_000, "eligible"),
        )
        val selected = PrefetchTaskSelector.eligible(
            tasks = tasks,
            desiredKeys = tasks.map { it.key }.toSet(),
            activeKeys = setOf(tasks[0].key),
            completedKeys = setOf(tasks[1].key),
            blockedKeys = setOf(tasks[2].key),
            backoffUntil = mapOf(tasks[3].key to 10_000L),
            nowMs = 5_000L,
        )

        assertEquals(listOf("eligible"), selected.map { it.label })
    }

    @Test
    fun prefetchRuntimeSnapshotCarriesThroughputAndDefaults() {
        val idle = PrefetchRuntimeSnapshot.idle
        assertEquals(PrefetchStatus.IDLE, idle.status)
        assertEquals(0L, idle.prefetchBytesPerSecond)
        assertEquals(0L, idle.cachedAheadMs)

        val active = PrefetchRuntimeSnapshot(
            status = PrefetchStatus.BUFFER_BELOW_TARGET,
            support = PrefetchSupport.HLS_VOD,
            activeRequests = 2,
            maxParallelRequests = 3,
            completedTasks = 5,
            pendingTasks = 10,
            cachedAheadMs = 120_000L,
            prefetchedBytes = 2_000_000L,
            lastFailure = null,
            prefetchBytesPerSecond = 1_500_000L,
        )
        assertEquals(PrefetchStatus.BUFFER_BELOW_TARGET, active.status)
        assertEquals(1_500_000L, active.prefetchBytesPerSecond)
        assertEquals(120_000L, active.cachedAheadMs)
    }

    @Test
    fun diagnosticsSnapshotSeparatesPlayableForwardBufferFromPrefetchedAhead() {
        val snapshot = AggressiveBufferDiagnosticsSnapshot(
            targetMinutes = 5,
            targetMs = 300_000L,
            effectiveTargetMs = 300_000L,
            currentPositionMs = 10_000L,
            bufferedPositionMs = 70_000L,
            forwardBufferedMs = 60_000L,
            allocatedBytes = 50L * MB,
            memoryBudgetBytes = 256L * MB,
            bitrateEstimateBps = 5_000_000L,
            cacheSpaceBytes = 100L * MB,
            cacheQuotaBytes = 2_048L * MB,
            availableStorageBytes = 50_000L * MB,
            isLive = false,
            isPlaying = true,
            playWhenReady = true,
            isLoading = true,
            targetReasons = emptySet(),
            memoryLimited = false,
            storageLimited = false,
            sourceLimited = false,
            sourceType = "HLS",
            prefetchStatus = PrefetchStatus.BUFFER_BELOW_TARGET,
            prefetchSupport = PrefetchSupport.HLS_VOD,
            activePrefetchRequests = 3,
            maxParallelRequests = 3,
            completedPrefetchTasks = 8,
            pendingPrefetchTasks = 12,
            prefetchedCachedAheadMs = 180_000L,
            prefetchedBytes = 25_000_000L,
            prefetchBytesPerSecond = 3_200_000L,
            lastPrefetchFailure = null,
        )

        assertEquals(60_000L, snapshot.forwardBufferedMs)
        assertEquals(180_000L, snapshot.prefetchedCachedAheadMs)
        assertEquals(3_200_000L, snapshot.prefetchBytesPerSecond)
        assertTrue(snapshot.forwardBufferedMs != snapshot.prefetchedCachedAheadMs)
    }

    private fun createVariant(
        id: String? = null,
        width: Int = -1,
        height: Int = -1,
        bitrate: Int = -1,
        url: String = "https://example.com/video.m3u8",
    ): HlsMultivariantPlaylist.Variant {
        val format = Format.Builder()
            .setId(id)
            .setWidth(width)
            .setHeight(height)
            .setAverageBitrate(bitrate)
            .setPeakBitrate(bitrate)
            .build()
        return HlsMultivariantPlaylist.Variant(
            android.net.Uri.parse(url),
            format,
            null,
            null,
            null,
            null,
        )
    }

    private fun createPlaylist(variants: List<HlsMultivariantPlaylist.Variant>): HlsMultivariantPlaylist {
        return HlsMultivariantPlaylist(
            "https://example.com/master.m3u8",
            emptyList(),
            variants,
            emptyList(),
            emptyList(),
            emptyList(),
            emptyList(),
            null,
            emptyList(),
            false,
            emptyMap(),
            emptyList(),
        )
    }

    @Test
    fun slowPrefetchTasksSurvivePlaybackReachingTheirStart() {
        val task = PrefetchTask(
            uriString = "https://example.com/chunk",
            position = 16L * 1024L * 1024L,
            length = 8L * 1024L * 1024L,
            startMs = 5_800L,
            endMs = 8_700L,
            label = "slow chunk",
        )

        // Playback has passed the task's start but the range is still ahead/overlapping.
        assertTrue(shouldKeepPrefetchTask(task, currentMs = 7_000L))
        assertTrue(shouldKeepPrefetchTask(task, currentMs = 8_699L))
        // Only a task whose whole range is behind the playhead is useless.
        assertFalse(shouldKeepPrefetchTask(task, currentMs = 8_700L))
        assertFalse(shouldKeepPrefetchTask(task, currentMs = 9_000L))
    }

    @Test
    fun hlsVariantSelectorMatchesExactFormatId() {
        val v1 = createVariant(id = "1080p", width = 1920, height = 1080, bitrate = 5_000_000)
        val v2 = createVariant(id = "2160p", width = 3840, height = 2160, bitrate = 15_000_000)
        val playlist = createPlaylist(listOf(v1, v2))

        val selected = HlsVariantSelector.selectVariant(
            playlist,
            Format.Builder().setId("2160p").build()
        )
        assertEquals("2160p", selected?.format?.id)
    }

    @Test
    fun hlsVariantSelectorToleratesLetterboxingAndCinemaCrop() {
        val v1 = createVariant(id = "1080p", width = 1920, height = 1080, bitrate = 5_000_000)
        val v2 = createVariant(id = "2160p", width = 3840, height = 2160, bitrate = 15_000_000)
        val playlist = createPlaylist(listOf(v1, v2))

        // Decoded video stream reports cinema 2.40:1 crop (3840x1600 instead of 3840x2160)
        val format = Format.Builder()
            .setWidth(3840)
            .setHeight(1600)
            .build()
        val selected = HlsVariantSelector.selectVariant(playlist, format)
        assertEquals("2160p", selected?.format?.id)
    }

    @Test
    fun hlsVariantSelectorSelectsHighestBitrateWhenFormatIsNull() {
        val v1 = createVariant(id = "720p", width = 1280, height = 720, bitrate = 2_000_000)
        val v2 = createVariant(id = "1080p", width = 1920, height = 1080, bitrate = 5_000_000)
        val v3 = createVariant(id = "2160p", width = 3840, height = 2160, bitrate = 15_000_000)
        val playlist = createPlaylist(listOf(v1, v2, v3))

        val selected = HlsVariantSelector.selectVariant(playlist, null)
        assertEquals("2160p", selected?.format?.id)
    }

    @Test
    fun hlsVariantSelectorNeverReturnsNullWhenVariantsExist() {
        val v1 = createVariant(id = "v1", width = 640, height = 360, bitrate = 800_000)
        val playlist = createPlaylist(listOf(v1))

        val format = Format.Builder()
            .setWidth(9999)
            .setHeight(8888)
            .build()
        val selected = HlsVariantSelector.selectVariant(playlist, format)
        assertNotNull(selected)
        assertEquals("v1", selected?.format?.id)
    }

    @Test
    fun prefetchSupportIncludesProgressiveVod() {
        val snapshot = PrefetchRuntimeSnapshot(
            status = PrefetchStatus.BUFFER_BELOW_TARGET,
            support = PrefetchSupport.PROGRESSIVE_VOD,
            activeRequests = 2,
            maxParallelRequests = 3,
            completedTasks = 1,
            pendingTasks = 4,
            cachedAheadMs = 60_000L,
            prefetchedBytes = 10_000_000L,
            lastFailure = null,
        )
        assertEquals(PrefetchSupport.PROGRESSIVE_VOD, snapshot.support)
    }

    @Test
    fun progressivePrefetchStartsAfterThePlayersCurrentAndNextChunks() {
        val chunk = ChunkedCacheDataSourceFactory.DEFAULT_CHUNK_SIZE
        val total = 1_000L * chunk

        assertEquals(
            "no cached data must leave two player chunks free",
            2L * chunk,
            progressivePrefetchStart(
                cacheFrontierByte = 0L,
                totalLength = total,
            )
        )
        assertEquals(
            "cached data must add the two-chunk gap after it",
            3L * chunk,
            progressivePrefetchStart(
                cacheFrontierByte = chunk,
                totalLength = total,
            )
        )
        assertEquals(
            "the start must never pass the end of the file",
            total,
            progressivePrefetchStart(
                cacheFrontierByte = total - 1L,
                totalLength = total,
            )
        )
    }

    @Test
    fun vodOverrideAllowsFullTargetOnM3u8WithDynamicWindow() {
        val policy = aggressivePolicy(minutes = 5)
        val controller = AggressiveBufferController(policy)

        // Without VOD override, isLive=true clamps to 60s
        val liveTarget = controller.effectiveTarget(
            playWhenReady = true,
            isLive = true,
            remainingMediaMs = null,
        )
        assertEquals(60_000L, liveTarget.targetMs)
        assertTrue(BufferTargetReason.LIVE_WINDOW in liveTarget.reasons)

        // With VOD stream detected (isLive=false), full 5 minutes forward buffer is allowed
        val vodTarget = controller.effectiveTarget(
            playWhenReady = true,
            isLive = false,
            remainingMediaMs = null,
        )
        assertEquals(300_000L, vodTarget.targetMs)
        assertFalse(BufferTargetReason.LIVE_WINDOW in vodTarget.reasons)
    }

    @Test
    fun prefetchTaskSelectorDedupesCompletedTasksAcrossTicks() {
        val task1 = PrefetchTask(
            uriString = "https://example.com/seg1.ts",
            position = 0L,
            length = -1L,
            startMs = 0L,
            endMs = 6000L,
            label = "seg1",
        )
        val task2 = PrefetchTask(
            uriString = "https://example.com/seg2.ts",
            position = 0L,
            length = -1L,
            startMs = 6000L,
            endMs = 12000L,
            label = "seg2",
        )

        val desired = setOf(task1.key, task2.key)
        val completed = setOf(task1.key)

        val eligible = PrefetchTaskSelector.eligible(
            tasks = listOf(task1, task2),
            desiredKeys = desired,
            activeKeys = emptySet(),
            completedKeys = completed,
            blockedKeys = emptySet(),
            backoffUntil = emptyMap(),
            nowMs = System.currentTimeMillis(),
        )

        assertEquals(1, eligible.size)
        assertEquals(task2.key, eligible.first().key)
    }
}

private class MutablePreferenceStore(
    val values: MutableMap<String, Any?> = mutableMapOf(),
) : PreferenceStore {
    override fun getString(key: String, defaultValue: String): PreferenceData<String> =
        MutablePreference(key, defaultValue)

    override fun getLong(key: String, defaultValue: Long): PreferenceData<Long> =
        MutablePreference(key, defaultValue)

    override fun getInt(key: String, defaultValue: Int): PreferenceData<Int> =
        MutablePreference(key, defaultValue)

    override fun getFloat(key: String, defaultValue: Float): PreferenceData<Float> =
        MutablePreference(key, defaultValue)

    override fun getBoolean(key: String, defaultValue: Boolean): PreferenceData<Boolean> =
        MutablePreference(key, defaultValue)

    override fun getStringSet(
        key: String,
        defaultValue: Set<String>,
    ): PreferenceData<Set<String>> = MutablePreference(key, defaultValue)

    override fun <T> getObjectFromString(
        key: String,
        defaultValue: T,
        serializer: (T) -> String,
        deserializer: (String) -> T,
    ): PreferenceData<T> = MutablePreference(key, defaultValue)

    override fun <T> getObjectFromInt(
        key: String,
        defaultValue: T,
        serializer: (T) -> Int,
        deserializer: (Int) -> T,
    ): PreferenceData<T> = MutablePreference(key, defaultValue)

    override fun <T> getObjectSetFromStringSet(
        key: String,
        defaultValue: Set<T>,
        serializer: (T) -> String,
        deserializer: (String) -> T?,
    ): PreferenceData<Set<T>> = MutablePreference(key, defaultValue)

    override fun getAll(): Map<String, *> = values

    private inner class MutablePreference<T>(
        private val key: String,
        private val defaultValue: T,
    ) : PreferenceData<T> {
        private val flow = MutableStateFlow(get())

        override fun key(): String = key

        @Suppress("UNCHECKED_CAST")
        override fun get(): T = (values[key] as? T) ?: defaultValue

        override fun set(value: T) {
            values[key] = value
            flow.value = value
        }

        override fun isSet(): Boolean = values.containsKey(key)

        override fun delete() {
            values.remove(key)
        }

        override fun defaultValue(): T = defaultValue

        override fun changes(): Flow<T> = flow

        override fun stateIn(scope: CoroutineScope): StateFlow<T> {
            return flow.stateIn(scope, SharingStarted.Eagerly, get())
        }
    }
}


