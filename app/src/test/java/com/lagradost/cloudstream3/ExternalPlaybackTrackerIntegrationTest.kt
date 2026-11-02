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
}
