package com.lagradost.cloudstream3

import com.lagradost.cloudstream3.actions.ExternalPlaybackClock
import com.lagradost.cloudstream3.actions.ExternalPlaybackExpired
import com.lagradost.cloudstream3.actions.ExternalPlaybackHistoryWriter
import com.lagradost.cloudstream3.actions.ExternalPlaybackPendingStore
import com.lagradost.cloudstream3.actions.ExternalPlaybackProgressEngine
import com.lagradost.cloudstream3.actions.ExternalPlaybackProgressSink
import com.lagradost.cloudstream3.actions.ExternalPlaybackSaveReason
import com.lagradost.cloudstream3.actions.ExternalPlaybackScanOutcome
import com.lagradost.cloudstream3.actions.ExternalPlaybackSession
import com.lagradost.cloudstream3.actions.ExternalPlaybackSessionSelection
import com.lagradost.cloudstream3.actions.ExternalPlaybackSessionSelector
import com.lagradost.cloudstream3.actions.ExternalPlaybackSessionSource
import com.lagradost.cloudstream3.actions.ExternalPlaybackSessionStore
import com.lagradost.cloudstream3.actions.ExternalPlaybackSnapshot
import com.lagradost.cloudstream3.actions.ExternalPlaybackState
import com.lagradost.cloudstream3.actions.ExternalPlaybackTrackerCore
import com.lagradost.cloudstream3.actions.ExternalPlaybackUpdate
import com.lagradost.cloudstream3.actions.PendingExternalPlayback
import com.lagradost.cloudstream3.services.ExternalPlaybackListenerCoordinator
import com.lagradost.cloudstream3.ui.result.ResultEpisode
import com.lagradost.cloudstream3.ui.result.VideoWatchState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deterministic integration tests for the actual tracker orchestration.
 *
 * The Android adapter is replaced with a fake MediaSession source, fake
 * SharedPreferences marker, fake DataStore sink and fake elapsed clock. No
 * device, URL, credential or real player is involved.
 */
class ExternalPlaybackTrackerIntegrationTest {

    private companion object {
        const val PACKAGE = "com.genuine.leone"
        const val TOKEN_A = "token-a"
        const val TOKEN_B = "token-b"
        const val TOKEN_C = "token-c"
        const val START = 1_000_000L
        const val DURATION = 100_000L
        const val EPISODE_ID = 12345
    }

    private class FakeClock(var now: Long = START) : ExternalPlaybackClock {
        override fun nowElapsedMs(): Long = now
    }

    private class FakeSessionSource : ExternalPlaybackSessionSource {
        var sessions: List<ExternalPlaybackSession> = emptyList()
        var failure: Throwable? = null

        override fun activeSessions(): List<ExternalPlaybackSession> {
            failure?.let { throw it }
            return sessions
        }
    }

    private class FakePendingStore(
        var current: PendingExternalPlayback?,
    ) : ExternalPlaybackPendingStore {
        val clears = mutableListOf<String>()
        val observed = mutableListOf<Pair<Long, Long>>()

        override fun get(): PendingExternalPlayback? = current

        override fun clear(reason: String) {
            clears += reason
            current = null
        }

        override fun markResumeSeekAttempted(
            pending: PendingExternalPlayback,
        ): PendingExternalPlayback? {
            val existing = current ?: return null
            if (existing.sessionId != pending.sessionId) return null
            current = existing.copy(resumeSeekAttempted = true)
            return current
        }

        override fun updateObserved(
            pending: PendingExternalPlayback,
            positionMs: Long,
            durationMs: Long,
        ): PendingExternalPlayback? {
            val existing = current ?: return null
            if (existing.sessionId != pending.sessionId) return null
            val duration = durationMs.takeIf { it > 0L } ?: existing.lastObservedDurationMs
            current = existing.copy(
                lastObservedPositionMs = positionMs.coerceAtLeast(0L),
                lastObservedDurationMs = duration,
                hasObservedPlayback = true,
            )
            observed += positionMs to duration
            return current
        }
    }

    private class FakeProgressSink : ExternalPlaybackProgressSink {
        val updates = mutableListOf<ExternalPlaybackUpdate>()
        var succeed: Boolean = true

        override fun save(
            pending: PendingExternalPlayback,
            update: ExternalPlaybackUpdate,
        ): Boolean {
            if (!succeed) return false
            updates += update
            return true
        }
    }

    private fun episode(
        name: String = "Test Movie",
        duration: Long = DURATION,
    ): ResultEpisode {
        return ResultEpisode(
            headerName = "Test Show",
            name = name,
            poster = "https://example.com/poster.jpg",
            episode = 1,
            seasonIndex = 1,
            season = 1,
            data = "episode_data",
            apiName = "TestApi",
            id = EPISODE_ID,
            index = 0,
            position = 0L,
            duration = duration,
            score = null,
            description = "Test description",
            isFiller = false,
            tvType = TvType.Movie,
            parentId = 123,
            videoWatchState = VideoWatchState.None,
        )
    }

    private fun pending(
        sessionId: String = "pending-1",
        initialPositionMs: Long = 0L,
        existingPositionMs: Long = initialPositionMs,
        expectedDurationMs: Long = DURATION,
        name: String = "Test Movie",
        lastObservedPositionMs: Long = 0L,
        lastObservedDurationMs: Long = 0L,
        hasObservedPlayback: Boolean = false,
    ): PendingExternalPlayback {
        return PendingExternalPlayback(
            sessionId = sessionId,
            packageName = PACKAGE,
            episode = episode(name = name, duration = expectedDurationMs),
            streamIdentifier = "abc123",
            initialPositionMs = initialPositionMs,
            existingPositionMs = existingPositionMs,
            expectedDurationMs = expectedDurationMs,
            startedAtWallClockMs = 1_000L,
            startedAtElapsedRealtimeMs = START,
            lastObservedPositionMs = lastObservedPositionMs,
            lastObservedDurationMs = lastObservedDurationMs,
            hasObservedPlayback = hasObservedPlayback,
        )
    }

    private fun snapshot(
        token: String = TOKEN_A,
        state: ExternalPlaybackState = ExternalPlaybackState.PLAYING,
        positionMs: Long,
        durationMs: Long = DURATION,
        observedAtElapsedMs: Long,
        lastPositionUpdateElapsedMs: Long = observedAtElapsedMs,
        title: String? = "Test Movie",
        mediaId: String? = null,
        queueItemId: Long = -1L,
        packageName: String = PACKAGE,
    ): ExternalPlaybackSnapshot {
        return ExternalPlaybackSnapshot(
            packageName = packageName,
            sessionToken = token,
            state = state,
            positionMs = positionMs,
            durationMs = durationMs,
            speed = 1f,
            actions = 256L,
            lastPositionUpdateElapsedMs = lastPositionUpdateElapsedMs,
            observedAtElapsedMs = observedAtElapsedMs,
            title = title,
            mediaId = mediaId,
            queueItemId = queueItemId,
        )
    }

    private fun session(
        snapshot: ExternalPlaybackSnapshot,
        token: String = snapshot.sessionToken,
        seek: ((Long) -> Unit)? = null,
    ): ExternalPlaybackSession {
        return ExternalPlaybackSession(
            packageName = snapshot.packageName,
            sessionToken = token,
            snapshot = snapshot,
            seekTo = seek,
        )
    }

    private fun tracker(
        clock: FakeClock,
        source: FakeSessionSource,
        store: FakePendingStore,
        sink: FakeProgressSink = FakeProgressSink(),
        enabled: Boolean = true,
        diagnostics: MutableList<String> = mutableListOf(),
    ): ExternalPlaybackTrackerCore {
        return ExternalPlaybackTrackerCore(
            sessionSource = source,
            pendingStore = store,
            progressSink = sink,
            clock = clock,
            isTrackingEnabled = { enabled },
            diagnostics = { diagnostics += it },
        )
    }

    // ==================== EXISTING TESTS (preserved) ====================

    @Test
    fun `notification listener connection starts and restarts tracking`() {
        var starts = 0
        var stops = 0
        var scans = 0
        val reasons = mutableListOf<String>()
        val coordinator = ExternalPlaybackListenerCoordinator(
            start = { starts++ },
            stop = {
                stops++
                reasons += it
            },
            requestScan = { scans++ },
            targetPackage = PACKAGE,
        )

        coordinator.onConnected()
        coordinator.onNotification(PACKAGE)
        coordinator.onNotification("com.example.other")
        assertEquals(1, starts)
        assertEquals(1, scans)

        coordinator.onDisconnected()
        assertEquals(1, stops)
        assertEquals("listener-disconnected", reasons.last())

        coordinator.onConnected()
        assertEquals(2, starts)

        coordinator.onDestroy()
        assertEquals(2, stops)
        assertEquals("service-destroyed", reasons.last())
    }

    @Test
    fun `a pending launch binds only the Network Stream Player package`() {
        val clock = FakeClock(START + 1_000L)
        val source = FakeSessionSource()
        val store = FakePendingStore(pending())
        val sink = FakeProgressSink()
        source.sessions = listOf(
            session(
                token = "other-token",
                snapshot = snapshot(
                    token = "other-token",
                    positionMs = 50_000L,
                    observedAtElapsedMs = clock.now,
                    packageName = "com.example.other",
                ),
            ),
            session(
                token = TOKEN_A,
                snapshot = snapshot(
                    token = TOKEN_A,
                    positionMs = 0L,
                    observedAtElapsedMs = clock.now,
                ),
            ),
        )

        val core = tracker(clock, source, store, sink)
        val outcome = core.scan()

        assertEquals(ExternalPlaybackScanOutcome.TRACKING, outcome)
        assertEquals(TOKEN_A, core.boundSessionToken)
        assertTrue(sink.updates.isEmpty())
        assertTrue(store.clears.isEmpty())
    }

    @Test
    fun `consecutive MediaSession scans persist progress through the tracker`() {
        val clock = FakeClock(START + 1_000L)
        val source = FakeSessionSource()
        val store = FakePendingStore(pending())
        val sink = FakeProgressSink()
        val core = tracker(clock, source, store, sink)

        source.sessions = listOf(
            session(snapshot(positionMs = 0L, observedAtElapsedMs = clock.now))
        )
        assertEquals(ExternalPlaybackScanOutcome.TRACKING, core.scan())

        clock.now = START + 6_000L
        source.sessions = listOf(
            session(snapshot(positionMs = 5_000L, observedAtElapsedMs = clock.now))
        )
        assertEquals(ExternalPlaybackScanOutcome.SAVED, core.scan())

        clock.now = START + 11_000L
        source.sessions = listOf(
            session(snapshot(positionMs = 10_000L, observedAtElapsedMs = clock.now))
        )
        assertEquals(ExternalPlaybackScanOutcome.SAVED, core.scan())

        assertEquals(listOf(5_000L, 10_000L), sink.updates.map { it.positionMs })
        assertEquals(listOf(DURATION, DURATION), sink.updates.map { it.durationMs })
        assertEquals(5_000L to DURATION, store.observed.first())
    }

    @Test
    fun `a harmless external title mismatch does not stop tracking`() {
        val clock = FakeClock(START + 1_000L)
        val source = FakeSessionSource()
        val store = FakePendingStore(pending(name = "CloudStream Episode 1"))
        val sink = FakeProgressSink()
        val core = tracker(clock, source, store, sink)

        source.sessions = listOf(
            session(
                snapshot(
                    positionMs = 0L,
                    observedAtElapsedMs = clock.now,
                    title = "CloudStream Episode 1",
                )
            )
        )
        core.scan()

        clock.now = START + 6_000L
        source.sessions = listOf(
            session(
                snapshot(
                    positionMs = 5_000L,
                    observedAtElapsedMs = clock.now,
                    title = "Provider.File.1080p.WEB-DL.mkv",
                )
            )
        )
        val outcome = core.scan()

        assertEquals(ExternalPlaybackScanOutcome.SAVED, outcome)
        assertEquals(TOKEN_A, core.boundSessionToken)
        assertEquals(1, sink.updates.size)
        assertTrue(store.clears.isEmpty())
    }

    @Test
    fun `a replacement MediaSession is rebound and progress continues`() {
        val clock = FakeClock(START + 1_000L)
        val source = FakeSessionSource()
        val store = FakePendingStore(pending())
        val sink = FakeProgressSink()
        val core = tracker(clock, source, store, sink)

        source.sessions = listOf(
            session(snapshot(token = TOKEN_A, positionMs = 0L, observedAtElapsedMs = clock.now))
        )
        core.scan()

        clock.now = START + 6_000L
        source.sessions = listOf(
            session(
                snapshot(
                    token = TOKEN_A,
                    positionMs = 5_000L,
                    observedAtElapsedMs = clock.now,
                    mediaId = "episode-1",
                )
            )
        )
        core.scan()

        clock.now = START + 7_000L
        source.sessions = listOf(
            session(
                snapshot(
                    token = TOKEN_B,
                    positionMs = 6_000L,
                    observedAtElapsedMs = clock.now,
                    mediaId = "episode-1",
                )
            )
        )
        core.scan()
        assertEquals(TOKEN_B, core.boundSessionToken)

        clock.now = START + 12_000L
        source.sessions = listOf(
            session(
                snapshot(
                    token = TOKEN_B,
                    positionMs = 11_000L,
                    observedAtElapsedMs = clock.now,
                    mediaId = "episode-1",
                )
            )
        )
        val outcome = core.scan()

        assertEquals(ExternalPlaybackScanOutcome.SAVED, outcome)
        assertEquals(listOf(5_000L, 11_000L), sink.updates.map { it.positionMs })
        assertEquals(10_000L, sink.updates.last().watchedContentMs)
        assertEquals(TOKEN_B, core.boundSessionToken)
    }

    @Test
    fun `a replacement session with a different media identity is rejected`() {
        val clock = FakeClock(START + 1_000L)
        val source = FakeSessionSource()
        val store = FakePendingStore(pending())
        val sink = FakeProgressSink()
        val core = tracker(clock, source, store, sink)

        source.sessions = listOf(
            session(
                snapshot(
                    token = TOKEN_A,
                    positionMs = 0L,
                    observedAtElapsedMs = clock.now,
                    mediaId = "episode-1",
                )
            )
        )
        core.scan()

        clock.now = START + 6_000L
        source.sessions = listOf(
            session(
                snapshot(
                    token = TOKEN_A,
                    positionMs = 5_000L,
                    observedAtElapsedMs = clock.now,
                    mediaId = "episode-1",
                )
            )
        )
        core.scan()
        assertEquals(1, sink.updates.size)

        clock.now = START + 7_000L
        source.sessions = listOf(
            session(
                snapshot(
                    token = TOKEN_B,
                    positionMs = 6_000L,
                    observedAtElapsedMs = clock.now,
                    mediaId = "episode-2",
                )
            )
        )
        val outcome = core.scan()

        assertEquals(ExternalPlaybackScanOutcome.NO_SESSION, outcome)
        assertEquals(TOKEN_A, core.boundSessionToken)
        assertEquals(1, sink.updates.size)
        assertTrue(store.clears.isEmpty())
    }

    @Test
    fun `multiple ambiguous sessions are left unbound`() {
        val clock = FakeClock(START + 1_000L)
        val source = FakeSessionSource()
        val store = FakePendingStore(pending())
        val sink = FakeProgressSink()
        val core = tracker(clock, source, store, sink)
        source.sessions = listOf(
            session(
                token = TOKEN_A,
                snapshot = snapshot(
                    token = TOKEN_A,
                    positionMs = 0L,
                    observedAtElapsedMs = clock.now,
                    lastPositionUpdateElapsedMs = 0L,
                ),
            ),
            session(
                token = TOKEN_B,
                snapshot = snapshot(
                    token = TOKEN_B,
                    positionMs = 0L,
                    observedAtElapsedMs = clock.now,
                    lastPositionUpdateElapsedMs = 0L,
                ),
            ),
        )

        val outcome = core.scan()

        assertEquals(ExternalPlaybackScanOutcome.AMBIGUOUS, outcome)
        assertNull(core.boundSessionToken)
        assertTrue(sink.updates.isEmpty())
        assertTrue(store.clears.isEmpty())
    }

    @Test
    fun `a replacement session that was clearly active before launch is rejected`() {
        val clock = FakeClock(START + 1_000L)
        val source = FakeSessionSource()
        val store = FakePendingStore(pending())
        val sink = FakeProgressSink()
        val core = tracker(clock, source, store, sink)

        source.sessions = listOf(
            session(
                snapshot(
                    token = TOKEN_B,
                    positionMs = 70_000L,
                    observedAtElapsedMs = clock.now,
                    lastPositionUpdateElapsedMs = START - 60_000L,
                )
            )
        )
        assertEquals(ExternalPlaybackScanOutcome.NO_SESSION, core.scan())
        assertNull(core.boundSessionToken)
        assertTrue(sink.updates.isEmpty())
        assertTrue(store.clears.isEmpty())

        clock.now = START + 6_000L
        source.sessions = listOf(
            session(
                snapshot(
                    token = TOKEN_B,
                    positionMs = 5_000L,
                    observedAtElapsedMs = clock.now,
                    lastPositionUpdateElapsedMs = clock.now,
                )
            )
        )
        assertEquals(ExternalPlaybackScanOutcome.TRACKING, core.scan())
        assertEquals(TOKEN_B, core.boundSessionToken)
    }

    @Test
    fun `zero MediaSession update timestamps still persist progress`() {
        val clock = FakeClock(START + 1_000L)
        val source = FakeSessionSource()
        val store = FakePendingStore(pending())
        val sink = FakeProgressSink()
        val core = tracker(clock, source, store, sink)

        source.sessions = listOf(
            session(
                snapshot(
                    positionMs = 0L,
                    observedAtElapsedMs = clock.now,
                    lastPositionUpdateElapsedMs = 0L,
                )
            )
        )
        core.scan()

        clock.now = START + 6_000L
        source.sessions = listOf(
            session(
                snapshot(
                    positionMs = 0L,
                    observedAtElapsedMs = clock.now,
                    lastPositionUpdateElapsedMs = 0L,
                )
            )
        )
        assertEquals(ExternalPlaybackScanOutcome.SAVED, core.scan())

        clock.now = START + 11_000L
        source.sessions = listOf(
            session(
                snapshot(
                    positionMs = 0L,
                    observedAtElapsedMs = clock.now,
                    lastPositionUpdateElapsedMs = 0L,
                )
            )
        )
        assertEquals(ExternalPlaybackScanOutcome.SAVED, core.scan())

        assertEquals(listOf(5_000L, 10_000L), sink.updates.map { it.positionMs })
    }

    @Test
    fun `a temporarily missing duration keeps the pending episode alive`() {
        val clock = FakeClock(START + 1_000L)
        val source = FakeSessionSource()
        val store = FakePendingStore(
            pending(expectedDurationMs = 0L, lastObservedDurationMs = 0L)
        )
        val sink = FakeProgressSink()
        val core = tracker(clock, source, store, sink)

        source.sessions = listOf(
            session(
                snapshot(
                    positionMs = 0L,
                    durationMs = 0L,
                    observedAtElapsedMs = clock.now,
                    lastPositionUpdateElapsedMs = 0L,
                )
            )
        )
        core.scan()

        clock.now = START + 6_000L
        source.sessions = listOf(
            session(
                snapshot(
                    positionMs = 5_000L,
                    durationMs = 0L,
                    observedAtElapsedMs = clock.now,
                    lastPositionUpdateElapsedMs = 0L,
                )
            )
        )
        assertEquals(ExternalPlaybackScanOutcome.TRACKING, core.scan())
        assertTrue(sink.updates.isEmpty())
        assertTrue(store.clears.isEmpty())
        assertEquals(5_000L, store.current?.lastObservedPositionMs)

        clock.now = START + 11_000L
        source.sessions = listOf(
            session(
                snapshot(
                    positionMs = 10_000L,
                    durationMs = DURATION,
                    observedAtElapsedMs = clock.now,
                    lastPositionUpdateElapsedMs = 0L,
                )
            )
        )
        assertEquals(ExternalPlaybackScanOutcome.SAVED, core.scan())
        assertEquals(1, sink.updates.size)
        assertEquals(10_000L, sink.updates.single().positionMs)
        assertEquals(DURATION, sink.updates.single().durationMs)
        assertTrue(store.clears.isEmpty())
    }

    @Test
    fun `a persistence failure keeps the pending episode for retry`() {
        val clock = FakeClock(START + 1_000L)
        val source = FakeSessionSource()
        val store = FakePendingStore(pending())
        val sink = FakeProgressSink()
        val core = tracker(clock, source, store, sink)

        source.sessions = listOf(
            session(snapshot(positionMs = 0L, observedAtElapsedMs = clock.now))
        )
        core.scan()

        clock.now = START + 2_000L
        source.sessions = listOf(
            session(snapshot(positionMs = 1_000L, observedAtElapsedMs = clock.now))
        )
        core.scan()
        assertTrue(sink.updates.isEmpty())

        sink.succeed = false
        clock.now = START + 3_000L
        assertFalse(core.onActivityResult("pending-1", null, null))
        assertTrue(store.clears.isEmpty())
        assertNotNull(store.current)

        sink.succeed = true
        clock.now = START + 4_000L
        source.sessions = emptyList()
        core.scan()

        assertEquals(1, sink.updates.size)
        assertEquals(1_000L, sink.updates.single().positionMs)
        assertEquals(ExternalPlaybackSaveReason.SESSION_LOST, sink.updates.single().reason)
        assertTrue(store.clears.isEmpty())
        assertNotNull(store.current)
    }

    @Test
    fun `an empty activity result saves the last observed position and never completes it`() {
        val clock = FakeClock(START + 1_000L)
        val source = FakeSessionSource()
        val store = FakePendingStore(pending())
        val sink = FakeProgressSink()
        val core = tracker(clock, source, store, sink)

        source.sessions = listOf(
            session(snapshot(positionMs = 0L, observedAtElapsedMs = clock.now))
        )
        core.scan()

        clock.now = START + 2_000L
        source.sessions = listOf(
            session(snapshot(positionMs = 1_000L, observedAtElapsedMs = clock.now))
        )
        core.scan()
        assertTrue(sink.updates.isEmpty())

        clock.now = START + 3_000L
        val handled = core.onActivityResult("pending-1", null, null)

        assertTrue(handled)
        assertEquals(1, sink.updates.size)
        assertEquals(ExternalPlaybackSaveReason.ACTIVITY_RESULT, sink.updates.single().reason)
        assertEquals(1_000L, sink.updates.single().positionMs)
        assertFalse(sink.updates.single().completed)
        assertEquals("activity-result", store.clears.single())
        assertNull(store.current)
    }

    @Test
    fun `an activity result for an older launch never finalizes the current episode`() {
        val clock = FakeClock(START + 1_000L)
        val source = FakeSessionSource()
        val current = pending(sessionId = "pending-new")
        val store = FakePendingStore(current)
        val sink = FakeProgressSink()
        val core = tracker(clock, source, store, sink)

        assertFalse(core.onActivityResult("pending-old", 6_000L, DURATION))
        assertTrue(store.clears.isEmpty())
        assertEquals("pending-new", store.current?.sessionId)

        assertTrue(core.onActivityResult("pending-new", 6_000L, DURATION))
        assertEquals(1, sink.updates.size)
        assertEquals(6_000L, sink.updates.single().positionMs)
        assertEquals("activity-result", store.clears.single())
        assertNull(store.current)
    }

    @Test
    fun `tracking disabled clears the marker without saving false progress`() {
        val clock = FakeClock(START + 1_000L)
        val source = FakeSessionSource()
        val store = FakePendingStore(pending())
        val sink = FakeProgressSink()
        val core = tracker(clock, source, store, sink, enabled = false)
        source.sessions = listOf(
            session(snapshot(positionMs = 50_000L, observedAtElapsedMs = clock.now))
        )

        val outcome = core.scan()

        assertEquals(ExternalPlaybackScanOutcome.DISABLED, outcome)
        assertTrue(sink.updates.isEmpty())
        assertEquals("tracking-disabled", store.clears.single())
        assertNull(store.current)
    }

    @Test
    fun `missing notification access keeps the pending episode and recovers later`() {
        val clock = FakeClock(START + 1_000L)
        val source = FakeSessionSource()
        val store = FakePendingStore(pending())
        val sink = FakeProgressSink()
        val core = tracker(clock, source, store, sink)
        source.failure = SecurityException("notification listener not connected")

        val missing = core.scan()
        assertEquals(ExternalPlaybackScanOutcome.PERMISSION_MISSING, missing)
        assertTrue(core.permissionMissing)
        assertTrue(store.clears.isEmpty())
        assertNotNull(store.current)

        source.failure = null
        clock.now = START + 6_000L
        source.sessions = listOf(
            session(snapshot(positionMs = 0L, observedAtElapsedMs = clock.now))
        )
        val recovered = core.scan()

        assertEquals(ExternalPlaybackScanOutcome.TRACKING, recovered)
        assertFalse(core.permissionMissing)
        assertEquals(TOKEN_A, core.boundSessionToken)
    }

    @Test
    fun `service recreation rebinds from the retained pending position`() {
        val clock = FakeClock(START + 1_000L)
        val source = FakeSessionSource()
        val store = FakePendingStore(pending())
        val sink = FakeProgressSink()
        val firstCore = tracker(clock, source, store, sink)

        source.sessions = listOf(
            session(snapshot(positionMs = 0L, observedAtElapsedMs = clock.now))
        )
        firstCore.scan()

        clock.now = START + 6_000L
        source.sessions = listOf(
            session(snapshot(positionMs = 5_000L, observedAtElapsedMs = clock.now))
        )
        firstCore.scan()
        assertEquals(5_000L, store.current?.lastObservedPositionMs)
        assertTrue(store.current?.hasObservedPlayback == true)

        firstCore.reset("listener-disconnected")
        clock.now = START + 20_000L
        source.sessions = listOf(
            session(
                snapshot(
                    token = TOKEN_B,
                    positionMs = 20_000L,
                    observedAtElapsedMs = clock.now,
                )
            )
        )
        val secondCore = tracker(clock, source, store, sink)
        val outcome = secondCore.scan()

        assertEquals(ExternalPlaybackScanOutcome.TRACKING, outcome)
        assertEquals(TOKEN_B, secondCore.boundSessionToken)

        clock.now = START + 25_000L
        source.sessions = listOf(
            session(
                snapshot(
                    token = TOKEN_B,
                    positionMs = 25_000L,
                    observedAtElapsedMs = clock.now,
                )
            )
        )
        assertEquals(ExternalPlaybackScanOutcome.SAVED, secondCore.scan())
        assertEquals(25_000L, sink.updates.last().positionMs)
    }

    @Test
    fun `session loss grace expires the pending marker`() {
        val clock = FakeClock(START + 1_000L)
        val source = FakeSessionSource()
        val store = FakePendingStore(pending())
        val sink = FakeProgressSink()
        val core = tracker(clock, source, store, sink)

        source.sessions = listOf(
            session(snapshot(positionMs = 0L, observedAtElapsedMs = clock.now))
        )
        core.scan()

        source.sessions = emptyList()
        clock.now = START + 6_000L
        assertEquals(ExternalPlaybackScanOutcome.NO_SESSION, core.scan())
        assertTrue(store.clears.isEmpty())

        clock.now = START + 36_000L
        val outcome = core.scan()

        assertEquals(ExternalPlaybackScanOutcome.EXPIRED, outcome)
        assertEquals("expired", store.clears.single())
        assertNull(store.current)
    }

    @Test
    fun `resume seek is issued once and the guard releases after confirmation`() {
        val clock = FakeClock(START + 1_000L)
        val source = FakeSessionSource()
        val store = FakePendingStore(pending(initialPositionMs = 60_000L))
        val sink = FakeProgressSink()
        val seeks = mutableListOf<Long>()
        val core = tracker(clock, source, store, sink)

        source.sessions = listOf(
            session(
                snapshot(positionMs = 0L, observedAtElapsedMs = clock.now),
                seek = { seeks += it },
            )
        )
        core.scan()
        assertEquals(listOf(60_000L), seeks)
        assertTrue(store.current?.resumeSeekAttempted == true)

        clock.now = START + 6_000L
        source.sessions = listOf(
            session(
                snapshot(positionMs = 5_000L, observedAtElapsedMs = clock.now),
                seek = { seeks += it },
            )
        )
        core.scan()
        assertTrue(sink.updates.isEmpty())
        assertEquals(1, seeks.size)

        clock.now = START + 12_000L
        source.sessions = listOf(
            session(
                snapshot(positionMs = 62_000L, observedAtElapsedMs = clock.now),
                seek = { seeks += it },
            )
        )
        assertEquals(ExternalPlaybackScanOutcome.SAVED, core.scan())
        assertEquals(62_000L, sink.updates.single().positionMs)
        assertEquals(1, seeks.size)
    }

    @Test
    fun `the existing 95 percent completion threshold is preserved`() {
        val clock = FakeClock(START + 1_000L)
        val source = FakeSessionSource()
        val store = FakePendingStore(pending())
        val sink = FakeProgressSink()
        val core = tracker(clock, source, store, sink)

        source.sessions = listOf(
            session(snapshot(positionMs = 0L, observedAtElapsedMs = clock.now))
        )
        core.scan()

        clock.now = START + 6_000L
        source.sessions = listOf(
            session(snapshot(positionMs = 95_000L, observedAtElapsedMs = clock.now))
        )
        core.scan()

        assertEquals(1, sink.updates.size)
        assertTrue(sink.updates.single().completed)
        assertEquals(95_000L, sink.updates.single().positionMs)
    }

    @Test
    fun `production progress persistence delegates milliseconds to the existing history layer`() {
        var written: Pair<PendingExternalPlayback, ExternalPlaybackUpdate>? = null
        val writer = ExternalPlaybackHistoryWriter { pending, update ->
            written = pending to update
        }
        val pending = pending()
        val update = ExternalPlaybackUpdate(
            positionMs = 62_000L,
            durationMs = DURATION,
            watchedContentMs = 30_000L,
            state = ExternalPlaybackState.PAUSED,
            reason = ExternalPlaybackSaveReason.PAUSE,
            immediate = true,
            completed = false,
            sessionEnded = false,
            clearPending = false,
        )

        assertTrue(ExternalPlaybackSessionStore.saveProgress(pending, update, writer))
        assertEquals(pending, written?.first)
        assertEquals(62_000L, written?.second?.positionMs)
        assertEquals(DURATION, written?.second?.durationMs)
        assertEquals(30_000L, written?.second?.watchedContentMs)

        var invalidWritten = false
        val invalidWriter = ExternalPlaybackHistoryWriter { _, _ -> invalidWritten = true }
        assertFalse(
            ExternalPlaybackSessionStore.saveProgress(
                pending,
                update.copy(durationMs = 0L),
                invalidWriter,
            )
        )
        assertFalse(invalidWritten)
    }

    @Test
    fun `selector keeps the bound token while other sessions are active`() {
        val pending = pending()
        val clock = FakeClock(START + 10_000L)
        val bound = ExternalPlaybackSession(
            packageName = PACKAGE,
            sessionToken = TOKEN_A,
            snapshot = snapshot(
                token = TOKEN_A,
                positionMs = 1_000L,
                observedAtElapsedMs = clock.now,
            ),
        )
        val other = ExternalPlaybackSession(
            packageName = PACKAGE,
            sessionToken = TOKEN_B,
            snapshot = snapshot(
                token = TOKEN_B,
                positionMs = 90_000L,
                observedAtElapsedMs = clock.now,
            ),
        )

        val selection = ExternalPlaybackSessionSelector.select(
            sessions = listOf(other, bound),
            pending = pending,
            boundToken = TOKEN_A,
            nowElapsedMs = clock.now,
        )

        assertTrue(selection is ExternalPlaybackSessionSelection.Bound)
        assertEquals(TOKEN_A, (selection as ExternalPlaybackSessionSelection.Bound)
            .session.sessionToken)
    }

    @Test
    fun `selector waits for stronger evidence when candidates are ambiguous`() {
        val pending = pending()
        val clock = FakeClock(START + 10_000L)
        val first = ExternalPlaybackSession(
            packageName = PACKAGE,
            sessionToken = TOKEN_A,
            snapshot = snapshot(
                token = TOKEN_A,
                positionMs = 0L,
                observedAtElapsedMs = clock.now,
                lastPositionUpdateElapsedMs = 0L,
            ),
        )
        val second = ExternalPlaybackSession(
            packageName = PACKAGE,
            sessionToken = TOKEN_B,
            snapshot = snapshot(
                token = TOKEN_B,
                positionMs = 0L,
                observedAtElapsedMs = clock.now,
                lastPositionUpdateElapsedMs = 0L,
            ),
        )

        val selection = ExternalPlaybackSessionSelector.select(
            sessions = listOf(first, second),
            pending = pending,
            boundToken = null,
            nowElapsedMs = clock.now,
        )

        assertEquals(ExternalPlaybackSessionSelection.Ambiguous, selection)
    }

    @Test
    fun `expired engine reports expiration without touching persistence`() {
        val engine = ExternalPlaybackProgressEngine(pending())

        assertEquals(
            ExternalPlaybackExpired,
            engine.onTick(START + ExternalPlaybackProgressEngine.DEFAULT_UNBOUND_EXPIRY_MS),
        )
    }

    // ==================== NEW REGRESSION TESTS ====================

    // --- 1. Notification listener disconnect requests a rebind ---
    @Test
    fun `notification listener disconnect requests a rebind`() {
        var rebindRequested = false
        val coordinator = ExternalPlaybackListenerCoordinator(
            start = {},
            stop = {},
            requestScan = {},
            targetPackage = PACKAGE,
            hasNotificationAccess = { true },
            requestRebind = { rebindRequested = true; true },
        )

        coordinator.onConnected()
        coordinator.onDisconnected()

        assertTrue("Rebind should be requested after disconnect", rebindRequested)
        assertEquals(1, coordinator.rebindRequestCount)
        assertTrue(coordinator.lastRebindSucceeded)
    }

    // --- 2. Listener reconnect restarts the tracker ---
    @Test
    fun `listener reconnect restarts the tracker`() {
        var starts = 0
        val coordinator = ExternalPlaybackListenerCoordinator(
            start = { starts++ },
            stop = {},
            requestScan = {},
            targetPackage = PACKAGE,
            hasNotificationAccess = { true },
            requestRebind = { true },
        )

        coordinator.onConnected()
        assertEquals(1, starts)

        coordinator.onDisconnected()
        assertFalse(coordinator.connected)

        coordinator.onConnected()
        assertEquals(2, starts)
        assertTrue(coordinator.connected)
    }

    // --- 3. Disconnect does not delete pending episode context ---
    @Test
    fun `disconnect does not delete pending episode context`() {
        val store = FakePendingStore(pending())
        val core = tracker(FakeClock(), FakeSessionSource(), store)

        // Simulate what happens in the service: coordinator calls stop,
        // which calls core.reset, but the pending store is NOT cleared.
        core.reset("listener-disconnected")

        // The pending episode is still present.
        assertNotNull(store.current)
        assertTrue(store.clears.isEmpty())
    }

    // --- 4. Tracking resumes after a service lifecycle restart ---
    @Test
    fun `tracking resumes after a service lifecycle restart`() {
        val clock = FakeClock(START + 1_000L)
        val source = FakeSessionSource()
        val store = FakePendingStore(
            pending(
                lastObservedPositionMs = 30_000L,
                lastObservedDurationMs = DURATION,
                hasObservedPlayback = true,
            )
        )
        val sink = FakeProgressSink()

        // First core tracked, then listener disconnected.
        val firstCore = tracker(clock, source, store, sink)
        firstCore.reset("listener-disconnected")

        // Listener reconnects with a new core.
        clock.now = START + 30_000L
        source.sessions = listOf(
            session(
                snapshot(
                    token = TOKEN_B,
                    positionMs = 40_000L,
                    observedAtElapsedMs = clock.now,
                )
            )
        )
        val secondCore = tracker(clock, source, store, sink)
        secondCore.scan()
        assertEquals(TOKEN_B, secondCore.boundSessionToken)

        clock.now = START + 35_000L
        source.sessions = listOf(
            session(
                snapshot(
                    token = TOKEN_B,
                    positionMs = 45_000L,
                    observedAtElapsedMs = clock.now,
                )
            )
        )
        assertEquals(ExternalPlaybackScanOutcome.SAVED, secondCore.scan())
        assertEquals(45_000L, sink.updates.last().positionMs)
    }

    // --- 5. Stale bound token reports STOPPED but still listed ---
    @Test
    fun `stale bound token reports STOPPED while still listed`() {
        val clock = FakeClock(START + 1_000L)
        val source = FakeSessionSource()
        val store = FakePendingStore(pending())
        val sink = FakeProgressSink()
        val core = tracker(clock, source, store, sink)

        // Bind session A
        source.sessions = listOf(
            session(snapshot(token = TOKEN_A, positionMs = 0L, observedAtElapsedMs = clock.now))
        )
        core.scan()
        assertEquals(TOKEN_A, core.boundSessionToken)

        clock.now = START + 6_000L
        source.sessions = listOf(
            session(
                snapshot(
                    token = TOKEN_A,
                    positionMs = 5_000L,
                    observedAtElapsedMs = clock.now,
                    mediaId = "ep-1",
                )
            )
        )
        core.scan()

        // Session A goes STOPPED but remains in the active list; session B starts PLAYING
        clock.now = START + 7_000L
        source.sessions = listOf(
            session(
                snapshot(
                    token = TOKEN_A,
                    state = ExternalPlaybackState.STOPPED,
                    positionMs = 5_000L,
                    observedAtElapsedMs = clock.now,
                    mediaId = "ep-1",
                )
            ),
            session(
                snapshot(
                    token = TOKEN_B,
                    state = ExternalPlaybackState.PLAYING,
                    positionMs = 6_000L,
                    observedAtElapsedMs = clock.now,
                    mediaId = "ep-1",
                )
            ),
        )
        core.scan()

        // The selector should have abandoned the stale A and bound the valid B.
        assertEquals(TOKEN_B, core.boundSessionToken)
    }

    // --- 6. Newer replacement token reports PLAYING ---
    @Test
    fun `newer replacement token with PLAYING state is selected over STOPPED bound`() {
        val clock = FakeClock(START + 1_000L)
        val source = FakeSessionSource()
        val store = FakePendingStore(pending())
        val sink = FakeProgressSink()
        val core = tracker(clock, source, store, sink)

        source.sessions = listOf(
            session(snapshot(token = TOKEN_A, positionMs = 0L, observedAtElapsedMs = clock.now))
        )
        core.scan()

        clock.now = START + 6_000L
        source.sessions = listOf(
            session(
                snapshot(
                    token = TOKEN_A,
                    state = ExternalPlaybackState.STOPPED,
                    positionMs = 5_000L,
                    observedAtElapsedMs = clock.now,
                    lastPositionUpdateElapsedMs = START + 3_000L,
                )
            ),
            session(
                snapshot(
                    token = TOKEN_B,
                    state = ExternalPlaybackState.PLAYING,
                    positionMs = 6_000L,
                    observedAtElapsedMs = clock.now,
                )
            ),
        )
        core.scan()

        assertEquals(TOKEN_B, core.boundSessionToken)
    }

    // --- 7. Selector abandons stale token and binds valid replacement ---
    @Test
    fun `selector abandons stale token and binds valid replacement`() {
        val pending = pending()
        val clock = FakeClock(START + 10_000L)

        val stale = ExternalPlaybackSession(
            packageName = PACKAGE,
            sessionToken = TOKEN_A,
            snapshot = snapshot(
                token = TOKEN_A,
                state = ExternalPlaybackState.STOPPED,
                positionMs = 5_000L,
                observedAtElapsedMs = clock.now,
            ),
        )
        val fresh = ExternalPlaybackSession(
            packageName = PACKAGE,
            sessionToken = TOKEN_B,
            snapshot = snapshot(
                token = TOKEN_B,
                state = ExternalPlaybackState.PLAYING,
                positionMs = 8_000L,
                observedAtElapsedMs = clock.now,
            ),
        )

        val selection = ExternalPlaybackSessionSelector.select(
            sessions = listOf(stale, fresh),
            pending = pending,
            boundToken = TOKEN_A,
            nowElapsedMs = clock.now,
        )

        assertTrue(
            "Should select replacement over stale bound",
            selection is ExternalPlaybackSessionSelection.Select
        )
        assertEquals(TOKEN_B, (selection as ExternalPlaybackSessionSelection.Select).session.sessionToken)
        assertTrue(selection.replacingBoundSession)
    }

    // --- 8. Fresh PLAYING outranks equivalent PAUSED ---
    @Test
    fun `fresh PLAYING session outranks an otherwise equivalent PAUSED session`() {
        val pending = pending()
        val clock = FakeClock(START + 10_000L)

        val paused = ExternalPlaybackSession(
            packageName = PACKAGE,
            sessionToken = TOKEN_A,
            snapshot = snapshot(
                token = TOKEN_A,
                state = ExternalPlaybackState.PAUSED,
                positionMs = 5_000L,
                observedAtElapsedMs = clock.now,
            ),
        )
        val playing = ExternalPlaybackSession(
            packageName = PACKAGE,
            sessionToken = TOKEN_B,
            snapshot = snapshot(
                token = TOKEN_B,
                state = ExternalPlaybackState.PLAYING,
                positionMs = 5_000L,
                observedAtElapsedMs = clock.now,
            ),
        )

        val selection = ExternalPlaybackSessionSelector.select(
            sessions = listOf(paused, playing),
            pending = pending,
            boundToken = null,
            nowElapsedMs = clock.now,
        )

        assertTrue(selection is ExternalPlaybackSessionSelection.Select)
        assertEquals(TOKEN_B, (selection as ExternalPlaybackSessionSelection.Select).session.sessionToken)
    }

    // --- 9. Two fresh sessions with different update timestamps ranked by recency ---
    @Test
    fun `two fresh sessions with different update timestamps are ranked using recency`() {
        val pending = pending()
        val clock = FakeClock(START + 10_000L)

        val older = ExternalPlaybackSession(
            packageName = PACKAGE,
            sessionToken = TOKEN_A,
            snapshot = snapshot(
                token = TOKEN_A,
                state = ExternalPlaybackState.PLAYING,
                positionMs = 5_000L,
                observedAtElapsedMs = clock.now,
                lastPositionUpdateElapsedMs = START + 5_000L,
            ),
        )
        val newer = ExternalPlaybackSession(
            packageName = PACKAGE,
            sessionToken = TOKEN_B,
            snapshot = snapshot(
                token = TOKEN_B,
                state = ExternalPlaybackState.PLAYING,
                positionMs = 5_000L,
                observedAtElapsedMs = clock.now,
                lastPositionUpdateElapsedMs = START + 8_000L,
            ),
        )

        val selection = ExternalPlaybackSessionSelector.select(
            sessions = listOf(older, newer),
            pending = pending,
            boundToken = null,
            nowElapsedMs = clock.now,
        )

        assertTrue(selection is ExternalPlaybackSessionSelection.Select)
        assertEquals(TOKEN_B, (selection as ExternalPlaybackSessionSelection.Select).session.sessionToken)
    }

    // --- 10. Truly indistinguishable sessions remain ambiguous ---
    @Test
    fun `truly indistinguishable sessions remain ambiguous`() {
        val pending = pending()
        val clock = FakeClock(START + 10_000L)

        val first = ExternalPlaybackSession(
            packageName = PACKAGE,
            sessionToken = TOKEN_A,
            snapshot = snapshot(
                token = TOKEN_A,
                state = ExternalPlaybackState.PLAYING,
                positionMs = 5_000L,
                observedAtElapsedMs = clock.now,
                lastPositionUpdateElapsedMs = clock.now,
            ),
        )
        val second = ExternalPlaybackSession(
            packageName = PACKAGE,
            sessionToken = TOKEN_B,
            snapshot = snapshot(
                token = TOKEN_B,
                state = ExternalPlaybackState.PLAYING,
                positionMs = 5_000L,
                observedAtElapsedMs = clock.now,
                lastPositionUpdateElapsedMs = clock.now,
            ),
        )

        val selection = ExternalPlaybackSessionSelector.select(
            sessions = listOf(first, second),
            pending = pending,
            boundToken = null,
            nowElapsedMs = clock.now,
        )

        assertEquals(ExternalPlaybackSessionSelection.Ambiguous, selection)
    }

    // --- 11. Ambiguity does not immediately clear pending tracking context ---
    @Test
    fun `ambiguity does not immediately clear pending tracking context`() {
        val clock = FakeClock(START + 1_000L)
        val source = FakeSessionSource()
        val store = FakePendingStore(pending())
        val sink = FakeProgressSink()
        val core = tracker(clock, source, store, sink)

        // Two ambiguous sessions
        source.sessions = listOf(
            session(
                snapshot(
                    token = TOKEN_A,
                    positionMs = 0L,
                    observedAtElapsedMs = clock.now,
                    lastPositionUpdateElapsedMs = 0L,
                )
            ),
            session(
                snapshot(
                    token = TOKEN_B,
                    positionMs = 0L,
                    observedAtElapsedMs = clock.now,
                    lastPositionUpdateElapsedMs = 0L,
                )
            ),
        )
        val outcome = core.scan()

        assertEquals(ExternalPlaybackScanOutcome.AMBIGUOUS, outcome)
        assertNotNull("Pending should NOT be cleared on ambiguity", store.current)
        assertTrue(store.clears.isEmpty())
    }

    // --- 12. Candidate appearing before deadline resolves successfully ---
    @Test
    fun `candidate appearing before pending expiration deadline resolves successfully`() {
        val clock = FakeClock(START + 1_000L)
        val source = FakeSessionSource()
        val store = FakePendingStore(pending())
        val sink = FakeProgressSink()
        val core = tracker(clock, source, store, sink)

        // No sessions initially
        source.sessions = emptyList()
        core.scan()
        assertEquals(ExternalPlaybackScanOutcome.NO_SESSION, core.scan())

        // Session appears well before the 120s expiry
        clock.now = START + 60_000L
        source.sessions = listOf(
            session(snapshot(positionMs = 0L, observedAtElapsedMs = clock.now))
        )
        val outcome = core.scan()

        assertEquals(ExternalPlaybackScanOutcome.TRACKING, outcome)
        assertEquals(TOKEN_A, core.boundSessionToken)
        assertNotNull(store.current)
    }

    // --- 13. No matching candidate still expires within bounded lifetime ---
    @Test
    fun `no matching candidate still expires within intended bounded lifetime`() {
        val clock = FakeClock(START + 1_000L)
        val source = FakeSessionSource()
        val store = FakePendingStore(pending())
        val sink = FakeProgressSink()
        val core = tracker(clock, source, store, sink)

        source.sessions = emptyList()
        core.scan()

        // Jump past the unbound expiry (120s)
        clock.now = START + ExternalPlaybackProgressEngine.DEFAULT_UNBOUND_EXPIRY_MS
        val outcome = core.scan()

        assertEquals(ExternalPlaybackScanOutcome.EXPIRED, outcome)
        assertEquals("expired", store.clears.single())
    }

    // --- 14. Failed active-session query does not corrupt controller map ---
    @Test
    fun `failed active-session query does not corrupt the controller map`() {
        val clock = FakeClock(START + 1_000L)
        val source = FakeSessionSource()
        val store = FakePendingStore(pending())
        val sink = FakeProgressSink()
        val core = tracker(clock, source, store, sink)

        // Successful scan, bind session A
        source.sessions = listOf(
            session(snapshot(positionMs = 0L, observedAtElapsedMs = clock.now))
        )
        core.scan()
        assertEquals(TOKEN_A, core.boundSessionToken)

        // Query fails with SecurityException
        source.failure = SecurityException("lost access")
        clock.now = START + 6_000L
        assertEquals(ExternalPlaybackScanOutcome.PERMISSION_MISSING, core.scan())

        // The pending episode is still present
        assertNotNull(store.current)
        assertTrue(store.clears.isEmpty())

        // Permission restored, session is back
        source.failure = null
        clock.now = START + 11_000L
        source.sessions = listOf(
            session(snapshot(positionMs = 10_000L, observedAtElapsedMs = clock.now))
        )
        val outcome = core.scan()

        // Should recover and track (session A is still the bound token)
        assertTrue(
            outcome == ExternalPlaybackScanOutcome.SAVED ||
                    outcome == ExternalPlaybackScanOutcome.TRACKING
        )
    }

    // --- 15. Latest observed position survives tracker recreation ---
    @Test
    fun `latest observed position survives tracker recreation`() {
        val clock = FakeClock(START + 1_000L)
        val source = FakeSessionSource()
        val store = FakePendingStore(pending())
        val sink = FakeProgressSink()

        val firstCore = tracker(clock, source, store, sink)
        source.sessions = listOf(
            session(snapshot(positionMs = 0L, observedAtElapsedMs = clock.now))
        )
        firstCore.scan()

        clock.now = START + 6_000L
        source.sessions = listOf(
            session(snapshot(positionMs = 5_000L, observedAtElapsedMs = clock.now))
        )
        firstCore.scan()

        clock.now = START + 11_000L
        source.sessions = listOf(
            session(snapshot(positionMs = 10_000L, observedAtElapsedMs = clock.now))
        )
        firstCore.scan()

        // Verify observed position was persisted
        assertEquals(10_000L, store.current?.lastObservedPositionMs)

        // Tracker is destroyed (e.g., listener disconnected)
        firstCore.reset("listener-disconnected")

        // New tracker picks up from the observed position
        val secondCore = tracker(clock, source, store, sink)
        clock.now = START + 20_000L
        source.sessions = listOf(
            session(
                snapshot(
                    token = TOKEN_B,
                    positionMs = 20_000L,
                    observedAtElapsedMs = clock.now,
                )
            )
        )
        secondCore.scan()

        clock.now = START + 25_000L
        source.sessions = listOf(
            session(
                snapshot(
                    token = TOKEN_B,
                    positionMs = 25_000L,
                    observedAtElapsedMs = clock.now,
                )
            )
        )
        secondCore.scan()
        assertEquals(25_000L, sink.updates.last().positionMs)
    }

    // --- 16. Multiple consecutive launches are independently tracked ---
    @Test
    fun `multiple consecutive NSP launches remain independently associated with their episodes`() {
        val clock = FakeClock(START + 1_000L)
        val source = FakeSessionSource()
        val store = FakePendingStore(pending(sessionId = "launch-1"))
        val sink = FakeProgressSink()
        val core = tracker(clock, source, store, sink)

        // First launch
        source.sessions = listOf(
            session(snapshot(positionMs = 0L, observedAtElapsedMs = clock.now))
        )
        core.scan()

        clock.now = START + 6_000L
        source.sessions = listOf(
            session(snapshot(positionMs = 5_000L, observedAtElapsedMs = clock.now))
        )
        core.scan()

        // Finalize first launch
        assertTrue(core.onActivityResult("launch-1", 5_000L, DURATION))
        assertEquals(5_000L, sink.updates.last().positionMs)

        // Second launch (different episode)
        store.current = pending(sessionId = "launch-2", name = "Episode 2")
        clock.now = START + 30_000L
        source.sessions = listOf(
            session(
                snapshot(
                    token = TOKEN_B,
                    positionMs = 0L,
                    observedAtElapsedMs = clock.now,
                )
            )
        )
        val newCore = tracker(clock, source, store, sink)
        newCore.scan()
        assertEquals(TOKEN_B, newCore.boundSessionToken)

        clock.now = START + 35_000L
        source.sessions = listOf(
            session(
                snapshot(
                    token = TOKEN_B,
                    positionMs = 8_000L,
                    observedAtElapsedMs = clock.now,
                )
            )
        )
        newCore.scan()
        assertEquals(8_000L, sink.updates.last().positionMs)
    }

    // --- Coordinator rebind verification ---
    @Test
    fun `coordinator test verifies rebind operation is requested after disconnection`() {
        var rebindCalled = false
        var rebindSucceeded = false
        val coordinator = ExternalPlaybackListenerCoordinator(
            start = {},
            stop = {},
            requestScan = {},
            targetPackage = PACKAGE,
            hasNotificationAccess = { true },
            requestRebind = {
                rebindCalled = true
                true
            },
        )

        coordinator.onConnected()
        assertTrue(coordinator.connected)

        coordinator.onDisconnected()
        assertFalse(coordinator.connected)
        assertTrue("rebind must be requested", rebindCalled)
        assertEquals(1, coordinator.rebindRequestCount)
        assertTrue(coordinator.lastRebindSucceeded)
    }

    @Test
    fun `coordinator does not request rebind when notification access is missing`() {
        var rebindCalled = false
        val coordinator = ExternalPlaybackListenerCoordinator(
            start = {},
            stop = {},
            requestScan = {},
            targetPackage = PACKAGE,
            hasNotificationAccess = { false },
            requestRebind = {
                rebindCalled = true
                true
            },
        )

        coordinator.onConnected()
        coordinator.onDisconnected()

        assertFalse("rebind should not be requested without notification access", rebindCalled)
        assertEquals(0, coordinator.rebindRequestCount)
        assertFalse(coordinator.lastRebindSucceeded)
    }

    @Test
    fun `coordinator logs rebind failure without crashing`() {
        val logs = mutableListOf<String>()
        val coordinator = ExternalPlaybackListenerCoordinator(
            start = {},
            stop = {},
            requestScan = {},
            targetPackage = PACKAGE,
            hasNotificationAccess = { true },
            requestRebind = { throw RuntimeException("rebind system call failed") },
            diagnostics = { logs += it },
        )

        coordinator.onConnected()
        coordinator.onDisconnected()

        assertEquals(1, coordinator.rebindRequestCount)
        assertFalse(coordinator.lastRebindSucceeded)
        assertTrue(logs.any { it.contains("rebind request failed") })
    }

    // --- Ambiguity grace period ---
    @Test
    fun `ambiguous candidates extend pending expiration with bounded grace`() {
        val engine = ExternalPlaybackProgressEngine(
            pending(),
            candidateGraceMs = 30_000L,
        )

        // Mark candidates as present (ambiguous)
        engine.hasCandidates = true

        // At unbound expiry time, should NOT expire because candidates are present
        val atExpiry = START + ExternalPlaybackProgressEngine.DEFAULT_UNBOUND_EXPIRY_MS
        assertNull(
            "Should not expire when ambiguous candidates are present",
            engine.onTick(atExpiry)
        )

        // After grace period, should expire
        val pastGrace = atExpiry + 30_001L
        assertEquals(ExternalPlaybackExpired, engine.onTick(pastGrace))
    }

    @Test
    fun `ambiguous candidates do not prevent expiration forever`() {
        val engine = ExternalPlaybackProgressEngine(
            pending(),
            candidateGraceMs = 30_000L,
        )
        engine.hasCandidates = true

        // Tick at the unbound expiry to set the candidate-first-seen timestamp
        val atExpiry = START + ExternalPlaybackProgressEngine.DEFAULT_UNBOUND_EXPIRY_MS
        assertNull(engine.onTick(atExpiry))

        // Eventually the candidate grace must expire
        val pastTotalGrace = atExpiry + 30_001L
        assertEquals(ExternalPlaybackExpired, engine.onTick(pastTotalGrace))
    }

    // ==================== ADDITIONAL SPECIFIC REGRESSION TESTS ====================

    // --- Section 1: Expiration ordering and discovery failure safety ---

    @Test
    fun `pending episode older than 120s is not deleted when active session query returns matching PLAYING session`() {
        // Pending started at START (e.g. 1_000_000L).
        val store = FakePendingStore(pending())
        val source = FakeSessionSource()
        val sink = FakeProgressSink()
        // Clock is 150 seconds past launch (> 120s unbound expiry).
        val clock = FakeClock(START + 150_000L)
        source.sessions = listOf(
            session(
                snapshot(
                    token = TOKEN_A,
                    state = ExternalPlaybackState.PLAYING,
                    positionMs = 150_000L,
                    durationMs = 300_000L,
                    observedAtElapsedMs = clock.now,
                    lastPositionUpdateElapsedMs = clock.now,
                )
            )
        )

        val core = tracker(clock, source, store, sink)
        val outcome = core.scan()

        // Must NOT expire; must bind to the playing session
        assertTrue(
            "Expected TRACKING or SAVED but got $outcome",
            outcome == ExternalPlaybackScanOutcome.TRACKING || outcome == ExternalPlaybackScanOutcome.SAVED
        )
        assertEquals(TOKEN_A, core.boundSessionToken)
        assertNotNull("Pending episode must be retained", store.current)
        assertTrue("Pending episode must not be cleared", store.clears.isEmpty())
    }

    @Test
    fun `tracker recreated after listener disconnection can bind to an already-playing session and continue saving progress`() {
        val store = FakePendingStore(pending())
        val source = FakeSessionSource()
        val sink = FakeProgressSink()
        val clock = FakeClock(START + 1_000L)

        // First core starts tracking
        source.sessions = listOf(
            session(snapshot(token = TOKEN_A, positionMs = 0L, observedAtElapsedMs = clock.now))
        )
        val firstCore = tracker(clock, source, store, sink)
        firstCore.scan()
        assertEquals(TOKEN_A, firstCore.boundSessionToken)

        // Listener disconnects, resetting first core
        firstCore.reset("listener-disconnected")

        // 140s later (past 120s unbound expiry), listener reconnects with a fresh core
        clock.now = START + 140_000L
        source.sessions = listOf(
            session(
                snapshot(
                    token = TOKEN_A,
                    state = ExternalPlaybackState.PLAYING,
                    positionMs = 139_000L,
                    durationMs = 300_000L,
                    observedAtElapsedMs = clock.now,
                    lastPositionUpdateElapsedMs = clock.now,
                )
            )
        )
        val secondCore = tracker(clock, source, store, sink)
        val outcome1 = secondCore.scan()
        assertEquals(TOKEN_A, secondCore.boundSessionToken)
        assertNotNull(store.current)

        // 5 seconds later, progress is saved
        clock.now = START + 145_000L
        source.sessions = listOf(
            session(
                snapshot(
                    token = TOKEN_A,
                    state = ExternalPlaybackState.PLAYING,
                    positionMs = 144_000L,
                    durationMs = 300_000L,
                    observedAtElapsedMs = clock.now,
                    lastPositionUpdateElapsedMs = clock.now,
                )
            )
        )
        val outcome2 = secondCore.scan()
        assertEquals(ExternalPlaybackScanOutcome.SAVED, outcome2)
        assertEquals(144_000L, sink.updates.last().positionMs)
    }

    @Test
    fun `SecurityException from active-session discovery does not cause immediate expiration`() {
        val store = FakePendingStore(pending())
        val source = FakeSessionSource()
        val sink = FakeProgressSink()
        val clock = FakeClock(START + 130_000L)

        source.failure = SecurityException("Permission Denial")
        val core = tracker(clock, source, store, sink)
        val outcome = core.scan()

        assertEquals(ExternalPlaybackScanOutcome.PERMISSION_MISSING, outcome)
        assertTrue(core.permissionMissing)
        assertNotNull("Pending episode must be retained across SecurityException", store.current)
        assertTrue("Pending must not be cleared on permission failure", store.clears.isEmpty())
    }

    @Test
    fun `subsequent successful scan recovers and tracks after SecurityException`() {
        val store = FakePendingStore(pending())
        val source = FakeSessionSource()
        val sink = FakeProgressSink()
        val clock = FakeClock(START + 130_000L)

        source.failure = SecurityException("Permission Denial")
        val core = tracker(clock, source, store, sink)
        core.scan()

        // Access restored 5s later
        clock.now = START + 135_000L
        source.failure = null
        source.sessions = listOf(
            session(
                snapshot(
                    token = TOKEN_A,
                    state = ExternalPlaybackState.PLAYING,
                    positionMs = 135_000L,
                    durationMs = 300_000L,
                    observedAtElapsedMs = clock.now,
                    lastPositionUpdateElapsedMs = clock.now,
                )
            )
        )
        val recovered = core.scan()

        assertFalse(core.permissionMissing)
        assertEquals(TOKEN_A, core.boundSessionToken)
        assertNotNull(store.current)
    }

    // --- Section 2: Stale bound-session detection and replacement ---

    @Test
    fun `bound session remains PAUSED with timestamp zero and playing Session B is selected`() {
        val pending = pending()
        val clock = FakeClock(START + 10_000L)

        val pausedA = ExternalPlaybackSession(
            packageName = PACKAGE,
            sessionToken = TOKEN_A,
            snapshot = snapshot(
                token = TOKEN_A,
                state = ExternalPlaybackState.PAUSED,
                positionMs = 5_000L,
                observedAtElapsedMs = clock.now,
                lastPositionUpdateElapsedMs = 0L,
                mediaId = "ep-1",
            ),
        )
        val playingB = ExternalPlaybackSession(
            packageName = PACKAGE,
            sessionToken = TOKEN_B,
            snapshot = snapshot(
                token = TOKEN_B,
                state = ExternalPlaybackState.PLAYING,
                positionMs = 8_000L,
                observedAtElapsedMs = clock.now,
                lastPositionUpdateElapsedMs = clock.now,
                mediaId = "ep-1",
            ),
        )

        val selection = ExternalPlaybackSessionSelector.select(
            sessions = listOf(pausedA, playingB),
            pending = pending,
            boundToken = TOKEN_A,
            nowElapsedMs = clock.now,
        )

        assertTrue("Playing Session B must replace timestamp-less PAUSED Session A", selection is ExternalPlaybackSessionSelection.Select)
        assertEquals(TOKEN_B, (selection as ExternalPlaybackSessionSelection.Select).session.sessionToken)
        assertTrue(selection.replacingBoundSession)
    }

    @Test
    fun `bound session remains IDLE with timestamp zero and playing Session B is selected`() {
        val pending = pending()
        val clock = FakeClock(START + 10_000L)

        val idleA = ExternalPlaybackSession(
            packageName = PACKAGE,
            sessionToken = TOKEN_A,
            snapshot = snapshot(
                token = TOKEN_A,
                state = ExternalPlaybackState.IDLE,
                positionMs = 0L,
                observedAtElapsedMs = clock.now,
                lastPositionUpdateElapsedMs = 0L,
                mediaId = "ep-1",
            ),
        )
        val playingB = ExternalPlaybackSession(
            packageName = PACKAGE,
            sessionToken = TOKEN_B,
            snapshot = snapshot(
                token = TOKEN_B,
                state = ExternalPlaybackState.PLAYING,
                positionMs = 8_000L,
                observedAtElapsedMs = clock.now,
                lastPositionUpdateElapsedMs = clock.now,
                mediaId = "ep-1",
            ),
        )

        val selection = ExternalPlaybackSessionSelector.select(
            sessions = listOf(idleA, playingB),
            pending = pending,
            boundToken = TOKEN_A,
            nowElapsedMs = clock.now,
        )

        assertTrue("Playing Session B must replace timestamp-less IDLE Session A", selection is ExternalPlaybackSessionSelection.Select)
        assertEquals(TOKEN_B, (selection as ExternalPlaybackSessionSelection.Select).session.sessionToken)
    }

    @Test
    fun `bound session with timestamp before launch is replaced by post-launch playing session`() {
        val pending = pending()
        val clock = FakeClock(START + 10_000L)

        // Session A has timestamp 5 seconds before the pending launch
        val preLaunchA = ExternalPlaybackSession(
            packageName = PACKAGE,
            sessionToken = TOKEN_A,
            snapshot = snapshot(
                token = TOKEN_A,
                state = ExternalPlaybackState.PLAYING,
                positionMs = 50_000L,
                observedAtElapsedMs = clock.now,
                lastPositionUpdateElapsedMs = START - 5_000L,
                mediaId = "ep-1",
            ),
        )
        val postLaunchB = ExternalPlaybackSession(
            packageName = PACKAGE,
            sessionToken = TOKEN_B,
            snapshot = snapshot(
                token = TOKEN_B,
                state = ExternalPlaybackState.PLAYING,
                positionMs = 8_000L,
                observedAtElapsedMs = clock.now,
                lastPositionUpdateElapsedMs = START + 8_000L,
                mediaId = "ep-1",
            ),
        )

        val selection = ExternalPlaybackSessionSelector.select(
            sessions = listOf(preLaunchA, postLaunchB),
            pending = pending,
            boundToken = TOKEN_A,
            nowElapsedMs = clock.now,
        )

        assertTrue(selection is ExternalPlaybackSessionSelection.Select)
        assertEquals(TOKEN_B, (selection as ExternalPlaybackSessionSelection.Select).session.sessionToken)
    }

    @Test
    fun `timestamp-less session that genuinely remains PLAYING and advances position continues to be tracked`() {
        val clock = FakeClock(START + 1_000L)
        val source = FakeSessionSource()
        val store = FakePendingStore(pending())
        val sink = FakeProgressSink()
        val core = tracker(clock, source, store, sink)

        source.sessions = listOf(
            session(
                snapshot(
                    token = TOKEN_A,
                    positionMs = 0L,
                    observedAtElapsedMs = clock.now,
                    lastPositionUpdateElapsedMs = 0L,
                )
            )
        )
        core.scan()

        clock.now = START + 6_000L
        source.sessions = listOf(
            session(
                snapshot(
                    token = TOKEN_A,
                    positionMs = 5_000L,
                    observedAtElapsedMs = clock.now,
                    lastPositionUpdateElapsedMs = 0L,
                )
            )
        )
        val outcome1 = core.scan()
        assertEquals(ExternalPlaybackScanOutcome.SAVED, outcome1)
        assertEquals(5_000L, sink.updates.last().positionMs)

        clock.now = START + 11_000L
        source.sessions = listOf(
            session(
                snapshot(
                    token = TOKEN_A,
                    positionMs = 10_000L,
                    observedAtElapsedMs = clock.now,
                    lastPositionUpdateElapsedMs = 0L,
                )
            )
        )
        val outcome2 = core.scan()
        assertEquals(ExternalPlaybackScanOutcome.SAVED, outcome2)
        assertEquals(10_000L, sink.updates.last().positionMs)
    }

    @Test
    fun `actual pause followed by resume on the same token does not clear pending episode`() {
        val clock = FakeClock(START + 1_000L)
        val source = FakeSessionSource()
        val store = FakePendingStore(pending())
        val sink = FakeProgressSink()
        val core = tracker(clock, source, store, sink)

        // Session A playing
        source.sessions = listOf(
            session(snapshot(token = TOKEN_A, positionMs = 5_000L, observedAtElapsedMs = clock.now))
        )
        core.scan()
        assertEquals(TOKEN_A, core.boundSessionToken)

        // Paused with timestamp 0, no other sessions
        clock.now = START + 6_000L
        source.sessions = listOf(
            session(
                snapshot(
                    token = TOKEN_A,
                    state = ExternalPlaybackState.PAUSED,
                    positionMs = 5_000L,
                    observedAtElapsedMs = clock.now,
                    lastPositionUpdateElapsedMs = 0L,
                )
            )
        )
        val pauseOutcome = core.scan()
        assertEquals(ExternalPlaybackScanOutcome.SAVED, pauseOutcome)
        assertEquals(TOKEN_A, core.boundSessionToken)
        assertNotNull("Pause must not clear pending episode", store.current)

        // Resume playing on same token
        clock.now = START + 12_000L
        source.sessions = listOf(
            session(
                snapshot(
                    token = TOKEN_A,
                    state = ExternalPlaybackState.PLAYING,
                    positionMs = 10_000L,
                    observedAtElapsedMs = clock.now,
                    lastPositionUpdateElapsedMs = clock.now,
                )
            )
        )
        val resumeOutcome = core.scan()
        assertEquals(ExternalPlaybackScanOutcome.SAVED, resumeOutcome)
        assertEquals(TOKEN_A, core.boundSessionToken)
        assertEquals(10_000L, sink.updates.last().positionMs)
    }

    // --- Section 4: History save validation ---

    @Test
    fun `saveProgress rejects writes below thirty second minimum duration`() {
        var written = false
        val writer = ExternalPlaybackHistoryWriter { _, _ -> written = true }
        val pending = pending()
        val update = ExternalPlaybackUpdate(
            positionMs = 5_000L,
            durationMs = 29_999L, // Below 30s minimum
            watchedContentMs = 5_000L,
            state = ExternalPlaybackState.PLAYING,
            reason = ExternalPlaybackSaveReason.PERIODIC,
            immediate = false,
            completed = false,
            sessionEnded = false,
            clearPending = false,
        )

        val result = ExternalPlaybackSessionStore.saveProgress(pending, update, writer)
        assertFalse("saveProgress must return false when duration is below 30s", result)
        assertFalse(written)
    }

    @Test
    fun `saveProgress accepts writes at or above thirty second minimum duration`() {
        var written = false
        val writer = ExternalPlaybackHistoryWriter { _, _ -> written = true }
        val pending = pending()
        val update = ExternalPlaybackUpdate(
            positionMs = 5_000L,
            durationMs = 30_000L, // At 30s minimum
            watchedContentMs = 5_000L,
            state = ExternalPlaybackState.PLAYING,
            reason = ExternalPlaybackSaveReason.PERIODIC,
            immediate = false,
            completed = false,
            sessionEnded = false,
            clearPending = false,
        )

        val result = ExternalPlaybackSessionStore.saveProgress(pending, update, writer)
        assertTrue("saveProgress must succeed when duration is at least 30s", result)
        assertTrue(written)
    }

    // --- Section 6: Complete end-to-end progress chain test ---

    @Test
    fun `complete progress tracking chain from launch to history write`() {
        val clock = FakeClock(START + 1_000L)
        val source = FakeSessionSource()
        val store = FakePendingStore(pending(initialPositionMs = 0L, expectedDurationMs = 120_000L))
        val sink = FakeProgressSink()
        val core = tracker(clock, source, store, sink)

        // 1. MediaSession discovered for com.genuine.leone
        source.sessions = listOf(
            session(
                snapshot(
                    token = TOKEN_A,
                    state = ExternalPlaybackState.PLAYING,
                    positionMs = 0L,
                    durationMs = 120_000L,
                    observedAtElapsedMs = clock.now,
                    title = "Test Movie",
                    mediaId = "ep-12345",
                )
            )
        )

        // 2. Scan selects and binds correct session
        val firstScan = core.scan()
        assertEquals(ExternalPlaybackScanOutcome.TRACKING, firstScan)
        assertEquals(TOKEN_A, core.boundSessionToken)
        assertNotNull(store.current)

        // 3. Playback position advances by 10s
        clock.now = START + 6_000L
        source.sessions = listOf(
            session(
                snapshot(
                    token = TOKEN_A,
                    state = ExternalPlaybackState.PLAYING,
                    positionMs = 10_000L,
                    durationMs = 120_000L,
                    observedAtElapsedMs = clock.now,
                    lastPositionUpdateElapsedMs = clock.now,
                    title = "Test Movie",
                    mediaId = "ep-12345",
                )
            )
        )

        // 4. Progress engine emits update and sink receives correct episode ID and position
        val secondScan = core.scan()
        assertEquals(ExternalPlaybackScanOutcome.SAVED, secondScan)
        assertEquals(1, sink.updates.size)
        val savedUpdate = sink.updates.single()
        assertEquals(10_000L, savedUpdate.positionMs)
        assertEquals(120_000L, savedUpdate.durationMs)
        assertEquals(EPISODE_ID, store.current?.episode?.id)

        // 5. Activity result finalizes playback cleanly
        clock.now = START + 15_000L
        assertTrue(core.onActivityResult("pending-1", 10_000L, 120_000L))
        assertNull("Pending state must be cleared after matching activity result", store.current)
    }
}
