package com.lagradost.cloudstream3

import com.lagradost.cloudstream3.actions.ExternalPlaybackExpired
import com.lagradost.cloudstream3.actions.ExternalPlaybackProgressEngine
import com.lagradost.cloudstream3.actions.ExternalPlaybackSaveReason
import com.lagradost.cloudstream3.actions.ExternalPlaybackSnapshot
import com.lagradost.cloudstream3.actions.ExternalPlaybackState
import com.lagradost.cloudstream3.actions.PendingExternalPlayback
import com.lagradost.cloudstream3.ui.result.ResultEpisode
import com.lagradost.cloudstream3.ui.result.VideoWatchState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the pure MediaSession progress rules. No real player, URL,
 * credential or device is used; every input is a fake snapshot.
 */
class ExternalPlaybackTrackingTest {

    private companion object {
        const val PACKAGE = "com.genuine.leone"
        const val TOKEN = "session-token-1"
        const val START = 1_000_000L
        const val DURATION = 100_000L
    }

    private fun episode(
        id: Int = 12345,
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
            id = id,
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
        initialPositionMs: Long = 0L,
        expectedDurationMs: Long = DURATION,
        name: String = "Test Movie",
    ): PendingExternalPlayback {
        return PendingExternalPlayback(
            sessionId = "pending-1",
            packageName = PACKAGE,
            episode = episode(name = name, duration = expectedDurationMs),
            streamIdentifier = "abc123",
            initialPositionMs = initialPositionMs,
            expectedDurationMs = expectedDurationMs,
            startedAtWallClockMs = 1_000L,
            startedAtElapsedRealtimeMs = START,
        )
    }

    private fun snapshot(
        state: ExternalPlaybackState,
        positionMs: Long,
        durationMs: Long = DURATION,
        speed: Float = 1f,
        observedAtElapsedMs: Long,
        lastPositionUpdateElapsedMs: Long = observedAtElapsedMs,
        sessionToken: String = TOKEN,
        packageName: String = PACKAGE,
        title: String? = "Test Movie",
    ): ExternalPlaybackSnapshot {
        return ExternalPlaybackSnapshot(
            packageName = packageName,
            sessionToken = sessionToken,
            state = state,
            positionMs = positionMs,
            durationMs = durationMs,
            speed = speed,
            lastPositionUpdateElapsedMs = lastPositionUpdateElapsedMs,
            observedAtElapsedMs = observedAtElapsedMs,
            title = title,
        )
    }

    private fun at(seconds: Long): Long = START + seconds * 1_000L

    private fun bind(
        engine: ExternalPlaybackProgressEngine,
        positionMs: Long = 0L,
        state: ExternalPlaybackState = ExternalPlaybackState.PLAYING,
        durationMs: Long = DURATION,
        speed: Float = 1f,
        title: String? = "Test Movie",
        seconds: Long = 1L,
    ): ExternalPlaybackSnapshot {
        val snap = snapshot(
            state = state,
            positionMs = positionMs,
            durationMs = durationMs,
            speed = speed,
            observedAtElapsedMs = at(seconds),
            title = title,
        )
        assertNull(engine.onSnapshot(snap))
        return snap
    }

    @Test
    fun `first snapshot binds the expected package and does not write progress immediately`() {
        val engine = ExternalPlaybackProgressEngine(pending())
        val snap = snapshot(
            state = ExternalPlaybackState.PLAYING,
            positionMs = 0L,
            observedAtElapsedMs = at(1),
        )

        assertNull(engine.onSnapshot(snap))
        assertEquals(TOKEN, engine.boundSessionToken)
        assertEquals(-1L, engine.lastPersistedPositionMs)
        assertEquals(0L, engine.watchedContentMs)
    }

    @Test
    fun `snapshots from another package are ignored`() {
        val engine = ExternalPlaybackProgressEngine(pending())
        val snap = snapshot(
            state = ExternalPlaybackState.PLAYING,
            positionMs = 5_000L,
            observedAtElapsedMs = at(1),
            packageName = "com.example.other",
        )

        assertNull(engine.onSnapshot(snap))
        assertNull(engine.boundSessionToken)
    }

    @Test
    fun `a stale session created before launch is ignored`() {
        val engine = ExternalPlaybackProgressEngine(pending())
        val snap = snapshot(
            state = ExternalPlaybackState.PLAYING,
            positionMs = 80_000L,
            observedAtElapsedMs = at(1),
            lastPositionUpdateElapsedMs = START - 60_000L,
        )

        assertNull(engine.onSnapshot(snap))
        assertNull(engine.boundSessionToken)
    }

    @Test
    fun `a different session token is ignored after binding`() {
        val engine = ExternalPlaybackProgressEngine(pending())
        bind(engine)

        val other = snapshot(
            state = ExternalPlaybackState.PLAYING,
            positionMs = 50_000L,
            observedAtElapsedMs = at(6),
            sessionToken = "another-token",
        )

        assertNull(engine.onSnapshot(other))
        assertEquals(TOKEN, engine.boundSessionToken)
    }

    @Test
    fun `out of order snapshots are ignored`() {
        val engine = ExternalPlaybackProgressEngine(pending())
        bind(engine)
        val newer = snapshot(
            state = ExternalPlaybackState.PLAYING,
            positionMs = 5_000L,
            observedAtElapsedMs = at(6),
        )
        assertNotNull(engine.onSnapshot(newer))

        val older = snapshot(
            state = ExternalPlaybackState.PLAYING,
            positionMs = 1_000L,
            observedAtElapsedMs = at(3),
            lastPositionUpdateElapsedMs = at(3),
        )
        assertNull(engine.onSnapshot(older))
        assertEquals(5_000L, engine.lastKnownPositionMs)
    }

    @Test
    fun `playing progress is persisted after the five second cadence`() {
        val engine = ExternalPlaybackProgressEngine(pending())
        bind(engine)

        val update = engine.onSnapshot(
            snapshot(
                state = ExternalPlaybackState.PLAYING,
                positionMs = 5_000L,
                observedAtElapsedMs = at(6),
            )
        )

        assertNotNull(update)
        assertEquals(5_000L, update!!.positionMs)
        assertEquals(DURATION, update.durationMs)
        assertEquals(5_000L, update.watchedContentMs)
        assertEquals(ExternalPlaybackSaveReason.PERIODIC, update.reason)
        assertFalse(update.completed)
    }

    @Test
    fun `periodic writes are throttled and require position movement`() {
        val engine = ExternalPlaybackProgressEngine(pending())
        bind(engine)

        assertNull(
            engine.onSnapshot(
                snapshot(
                    state = ExternalPlaybackState.PLAYING,
                    positionMs = 2_000L,
                    observedAtElapsedMs = at(3),
                )
            )
        )

        assertNotNull(
            engine.onSnapshot(
                snapshot(
                    state = ExternalPlaybackState.PLAYING,
                    positionMs = 5_000L,
                    observedAtElapsedMs = at(6),
                )
            )
        )

        // Same cadence window and less than one second of movement.
        assertNull(
            engine.onSnapshot(
                snapshot(
                    state = ExternalPlaybackState.PLAYING,
                    positionMs = 5_500L,
                    observedAtElapsedMs = at(7),
                )
            )
        )

        val next = engine.onSnapshot(
            snapshot(
                state = ExternalPlaybackState.PLAYING,
                positionMs = 11_000L,
                observedAtElapsedMs = at(12),
            )
        )
        assertNotNull(next)
        assertEquals(11_000L, next!!.positionMs)
    }

    @Test
    fun `pause forces an immediate write and paused time is not watched`() {
        val engine = ExternalPlaybackProgressEngine(pending())
        bind(engine, state = ExternalPlaybackState.PAUSED)

        val update = engine.onSnapshot(
            snapshot(
                state = ExternalPlaybackState.PAUSED,
                positionMs = 0L,
                observedAtElapsedMs = at(6),
            )
        )

        assertNotNull(update)
        assertEquals(ExternalPlaybackSaveReason.PAUSE, update!!.reason)
        assertTrue(update.immediate)
        assertEquals(0L, update.watchedContentMs)
    }

    @Test
    fun `buffering time is not counted as watched`() {
        val engine = ExternalPlaybackProgressEngine(pending())
        bind(engine)

        engine.onSnapshot(
            snapshot(
                state = ExternalPlaybackState.BUFFERING,
                positionMs = 0L,
                observedAtElapsedMs = at(6),
            )
        )
        engine.onSnapshot(
            snapshot(
                state = ExternalPlaybackState.PLAYING,
                positionMs = 0L,
                observedAtElapsedMs = at(16),
            )
        )
        val update = engine.onSnapshot(
            snapshot(
                state = ExternalPlaybackState.PLAYING,
                positionMs = 10_000L,
                observedAtElapsedMs = at(26),
            )
        )

        assertNotNull(update)
        assertEquals(10_000L, update!!.watchedContentMs)
    }

    @Test
    fun `speed changes scale watch time with media time`() {
        val engine = ExternalPlaybackProgressEngine(pending())
        bind(engine, speed = 1f)

        engine.onSnapshot(
            snapshot(
                state = ExternalPlaybackState.PLAYING,
                positionMs = 5_000L,
                speed = 2f,
                observedAtElapsedMs = at(6),
            )
        )
        val update = engine.onSnapshot(
            snapshot(
                state = ExternalPlaybackState.PLAYING,
                positionMs = 25_000L,
                speed = 2f,
                observedAtElapsedMs = at(16),
            )
        )

        assertNotNull(update)
        assertEquals(25_000L, update!!.watchedContentMs)
    }

    @Test
    fun `forward seek does not count skipped content as watched`() {
        val engine = ExternalPlaybackProgressEngine(pending())
        bind(engine)

        val update = engine.onSnapshot(
            snapshot(
                state = ExternalPlaybackState.PLAYING,
                positionMs = 50_000L,
                observedAtElapsedMs = at(6),
            )
        )

        assertNotNull(update)
        assertEquals(50_000L, update!!.positionMs)
        assertEquals(0L, update.watchedContentMs)
    }

    @Test
    fun `backward seek never makes watch time negative`() {
        val engine = ExternalPlaybackProgressEngine(pending())
        bind(engine, positionMs = 50_000L)

        val update = engine.onSnapshot(
            snapshot(
                state = ExternalPlaybackState.PLAYING,
                positionMs = 10_000L,
                observedAtElapsedMs = at(6),
            )
        )

        assertNotNull(update)
        assertEquals(10_000L, update!!.positionMs)
        assertEquals(0L, update.watchedContentMs)
    }

    @Test
    fun `session loss saves the last progress and never marks completion by itself`() {
        val engine = ExternalPlaybackProgressEngine(pending())
        bind(engine)
        assertNotNull(
            engine.onSnapshot(
                snapshot(
                    state = ExternalPlaybackState.PLAYING,
                    positionMs = 5_000L,
                    observedAtElapsedMs = at(6),
                )
            )
        )
        assertNull(
            engine.onSnapshot(
                snapshot(
                    state = ExternalPlaybackState.PLAYING,
                    positionMs = 6_000L,
                    observedAtElapsedMs = at(7),
                )
            )
        )

        val update = engine.onSessionLost(at(8))

        assertNotNull(update)
        assertEquals(6_000L, update!!.positionMs)
        assertEquals(ExternalPlaybackSaveReason.SESSION_LOST, update.reason)
        assertFalse(update.completed)
    }

    @Test
    fun `session disappearance starts a grace period before the pending marker expires`() {
        val engine = ExternalPlaybackProgressEngine(pending())
        bind(engine)
        engine.onSessionLost(at(6))

        assertNull(engine.onTick(at(6) + 29_999L))
        assertEquals(ExternalPlaybackExpired, engine.onTick(at(6) + 30_000L))
    }

    @Test
    fun `empty activity result saves observed progress but never completion`() {
        val engine = ExternalPlaybackProgressEngine(pending())
        bind(engine)
        assertNotNull(
            engine.onSnapshot(
                snapshot(
                    state = ExternalPlaybackState.PLAYING,
                    positionMs = 5_000L,
                    observedAtElapsedMs = at(6),
                )
            )
        )
        assertNull(
            engine.onSnapshot(
                snapshot(
                    state = ExternalPlaybackState.PLAYING,
                    positionMs = 6_000L,
                    observedAtElapsedMs = at(7),
                )
            )
        )

        val update = engine.onActivityResult(null, null, at(8))

        assertNotNull(update)
        assertEquals(6_000L, update!!.positionMs)
        assertEquals(ExternalPlaybackSaveReason.ACTIVITY_RESULT, update.reason)
        assertFalse(update.completed)
        assertTrue(update.clearPending)
    }

    @Test
    fun `verified activity result can complete an episode`() {
        val engine = ExternalPlaybackProgressEngine(pending())

        val update = engine.onActivityResult(95_000L, DURATION, at(8))

        assertNotNull(update)
        assertTrue(update!!.completed)
        assertTrue(update.clearPending)
    }

    @Test
    fun `completion is only reported at the existing ninety five percent threshold`() {
        val engine = ExternalPlaybackProgressEngine(pending())
        bind(engine)

        val before = engine.onSnapshot(
            snapshot(
                state = ExternalPlaybackState.PLAYING,
                positionMs = 94_000L,
                observedAtElapsedMs = at(6),
            )
        )
        assertNotNull(before)
        assertFalse(before!!.completed)

        val atThreshold = engine.onSnapshot(
            snapshot(
                state = ExternalPlaybackState.PLAYING,
                positionMs = 95_000L,
                observedAtElapsedMs = at(12),
            )
        )
        assertNotNull(atThreshold)
        assertTrue(atThreshold!!.completed)
    }

    @Test
    fun `resume guard preserves saved progress when the player starts at zero`() {
        val engine = ExternalPlaybackProgressEngine(pending(initialPositionMs = 60_000L))
        bind(engine, positionMs = 0L)

        assertNull(
            engine.onSnapshot(
                snapshot(
                    state = ExternalPlaybackState.PLAYING,
                    positionMs = 5_000L,
                    observedAtElapsedMs = at(6),
                )
            )
        )

        val update = engine.onSnapshot(
            snapshot(
                state = ExternalPlaybackState.PLAYING,
                positionMs = 62_000L,
                observedAtElapsedMs = at(12),
            )
        )
        assertNotNull(update)
        assertEquals(62_000L, update!!.positionMs)
    }

    @Test
    fun `media change stops tracking instead of attributing progress to the launched episode`() {
        val engine = ExternalPlaybackProgressEngine(pending(name = "Episode 1"))
        bind(engine, positionMs = 10_000L, title = "Episode 1")

        val update = engine.onSnapshot(
            snapshot(
                state = ExternalPlaybackState.PLAYING,
                positionMs = 20_000L,
                durationMs = 200_000L,
                observedAtElapsedMs = at(6),
                title = "Episode 2",
            )
        )

        assertNotNull(update)
        assertEquals(ExternalPlaybackSaveReason.MEDIA_CHANGED, update!!.reason)
        assertTrue(update.clearPending)
        assertEquals(10_000L, update.positionMs)
    }

    @Test
    fun `url derived metadata titles do not look like a media change`() {
        val engine = ExternalPlaybackProgressEngine(pending(name = "Episode 1"))
        bind(
            engine,
            positionMs = 0L,
            title = "https://cdn.example.com/shows/episode-1.m3u8",
        )

        val update = engine.onSnapshot(
            snapshot(
                state = ExternalPlaybackState.PLAYING,
                positionMs = 5_000L,
                observedAtElapsedMs = at(6),
                title = "https://cdn.example.com/shows/episode-1.m3u8",
            )
        )

        assertNotNull(update)
        assertEquals(ExternalPlaybackSaveReason.PERIODIC, update!!.reason)
        assertFalse(update.clearPending)
    }
}
