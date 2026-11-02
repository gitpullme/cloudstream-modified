package com.lagradost.cloudstream3

import com.lagradost.cloudstream3.actions.ExternalPlaybackExpired
import com.lagradost.cloudstream3.actions.ExternalPlaybackProgressEngine
import com.lagradost.cloudstream3.actions.ExternalPlaybackResumeAction
import com.lagradost.cloudstream3.actions.ExternalPlaybackResumeController
import com.lagradost.cloudstream3.actions.ExternalPlaybackResumePolicy
import com.lagradost.cloudstream3.actions.ExternalPlaybackResumeState
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
        tvType: TvType = TvType.Movie,
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
            tvType = tvType,
            parentId = 123,
            videoWatchState = VideoWatchState.None,
        )
    }

    private fun pending(
        initialPositionMs: Long = 0L,
        existingPositionMs: Long = initialPositionMs,
        expectedDurationMs: Long = DURATION,
        name: String = "Test Movie",
        resumeSeekAttempted: Boolean = false,
        tvType: TvType = TvType.Movie,
    ): PendingExternalPlayback {
        return PendingExternalPlayback(
            sessionId = "pending-1",
            packageName = PACKAGE,
            episode = episode(name = name, duration = expectedDurationMs, tvType = tvType),
            streamIdentifier = "abc123",
            initialPositionMs = initialPositionMs,
            existingPositionMs = existingPositionMs,
            expectedDurationMs = expectedDurationMs,
            startedAtWallClockMs = 1_000L,
            startedAtElapsedRealtimeMs = START,
            resumeSeekAttempted = resumeSeekAttempted,
        )
    }

    private fun snapshot(
        state: ExternalPlaybackState,
        positionMs: Long,
        durationMs: Long = DURATION,
        speed: Float = 1f,
        actions: Long = ExternalPlaybackResumeController.ACTION_SEEK_TO,
        observedAtElapsedMs: Long,
        lastPositionUpdateElapsedMs: Long = observedAtElapsedMs,
        sessionToken: String = TOKEN,
        packageName: String = PACKAGE,
        title: String? = "Test Movie",
        mediaId: String? = null,
        queueItemId: Long = -1L,
    ): ExternalPlaybackSnapshot {
        return ExternalPlaybackSnapshot(
            packageName = packageName,
            sessionToken = sessionToken,
            state = state,
            positionMs = positionMs,
            durationMs = durationMs,
            speed = speed,
            actions = actions,
            lastPositionUpdateElapsedMs = lastPositionUpdateElapsedMs,
            observedAtElapsedMs = observedAtElapsedMs,
            title = title,
            mediaId = mediaId,
            queueItemId = queueItemId,
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
    fun `session loss near the end still never marks completion`() {
        val engine = ExternalPlaybackProgressEngine(pending())
        bind(engine, positionMs = 95_000L)

        val update = engine.onSessionLost(at(6))

        assertNotNull(update)
        assertEquals(95_000L, update!!.positionMs)
        assertFalse(update.completed)
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
    fun `a different external title is diagnostic and never terminates tracking`() {
        val engine = ExternalPlaybackProgressEngine(pending(name = "Episode 1"))
        bind(engine, positionMs = 10_000L, title = "Episode 1")

        val update = engine.onSnapshot(
            snapshot(
                state = ExternalPlaybackState.PLAYING,
                positionMs = 15_000L,
                observedAtElapsedMs = at(6),
                title = "Provider file 1080p",
            )
        )

        assertNotNull(update)
        assertEquals(ExternalPlaybackSaveReason.PERIODIC, update!!.reason)
        assertFalse(update.clearPending)
        assertEquals(15_000L, update.positionMs)
        assertEquals(TOKEN, engine.boundSessionToken)
        assertTrue(engine.titleMismatchDetected)
        assertNotNull(engine.lastTitleMismatchDescription)
    }

    @Test
    fun `a title missing at bind time can be populated later`() {
        val engine = ExternalPlaybackProgressEngine(pending(name = ""))
        bind(engine, positionMs = 0L, title = null)

        val update = engine.onSnapshot(
            snapshot(
                state = ExternalPlaybackState.PLAYING,
                positionMs = 5_000L,
                observedAtElapsedMs = at(6),
                title = "Test Show S01E01",
            )
        )

        assertNotNull(update)
        assertEquals(ExternalPlaybackSaveReason.PERIODIC, update!!.reason)
        assertFalse(update.clearPending)
        assertFalse(engine.titleMismatchDetected)
    }

    @Test
    fun `a url derived title changing to a readable title keeps tracking`() {
        val engine = ExternalPlaybackProgressEngine(pending(name = ""))
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
                title = "Test Show - Episode 1",
            )
        )

        assertNotNull(update)
        assertEquals(ExternalPlaybackSaveReason.PERIODIC, update!!.reason)
        assertFalse(update.clearPending)
        assertFalse(engine.titleMismatchDetected)
    }

    @Test
    fun `a metadata title with show name and episode number is weak evidence`() {
        val engine = ExternalPlaybackProgressEngine(pending(name = "Test Show S01E01"))
        bind(engine, positionMs = 0L, title = "Test Show S01E01")

        val update = engine.onSnapshot(
            snapshot(
                state = ExternalPlaybackState.PLAYING,
                positionMs = 5_000L,
                observedAtElapsedMs = at(6),
                title = "Test Show - Episode 1",
            )
        )

        assertNotNull(update)
        assertEquals(ExternalPlaybackSaveReason.PERIODIC, update!!.reason)
        assertFalse(update.clearPending)
        assertFalse(engine.titleMismatchDetected)
    }

    @Test
    fun `filename extensions and provider prefixes are weak title evidence`() {
        val engine = ExternalPlaybackProgressEngine(pending(name = "Test Show S01E01"))
        bind(engine, positionMs = 0L, title = "Test Show S01E01")

        val update = engine.onSnapshot(
            snapshot(
                state = ExternalPlaybackState.PLAYING,
                positionMs = 5_000L,
                observedAtElapsedMs = at(6),
                title = "[Provider] Test.Show.S01E01.1080p.WEB-DL.mkv",
            )
        )

        assertNotNull(update)
        assertEquals(ExternalPlaybackSaveReason.PERIODIC, update!!.reason)
        assertFalse(update.clearPending)
        assertFalse(engine.titleMismatchDetected)
    }

    @Test
    fun `genuine media change is detected by strong media id and preserves progress`() {
        val engine = ExternalPlaybackProgressEngine(pending(name = "Episode 1"))
        assertNull(
            engine.onSnapshot(
                snapshot(
                    state = ExternalPlaybackState.PLAYING,
                    positionMs = 10_000L,
                    observedAtElapsedMs = at(1),
                    title = "Episode 1",
                    mediaId = "episode-1",
                )
            )
        )

        val update = engine.onSnapshot(
            snapshot(
                state = ExternalPlaybackState.PLAYING,
                positionMs = 20_000L,
                durationMs = 200_000L,
                observedAtElapsedMs = at(6),
                title = "Episode 2",
                mediaId = "episode-2",
            )
        )

        assertNotNull(update)
        assertEquals(ExternalPlaybackSaveReason.MEDIA_CHANGED, update!!.reason)
        assertTrue(update.clearPending)
        assertEquals(10_000L, update.positionMs)
        assertEquals(DURATION, update.durationMs)
    }

    @Test
    fun `genuine media change is detected by queue item id`() {
        val engine = ExternalPlaybackProgressEngine(pending())
        assertNull(
            engine.onSnapshot(
                snapshot(
                    state = ExternalPlaybackState.PLAYING,
                    positionMs = 0L,
                    observedAtElapsedMs = at(1),
                    queueItemId = 1L,
                )
            )
        )

        val update = engine.onSnapshot(
            snapshot(
                state = ExternalPlaybackState.PLAYING,
                positionMs = 5_000L,
                observedAtElapsedMs = at(6),
                queueItemId = 2L,
            )
        )

        assertNotNull(update)
        assertEquals(ExternalPlaybackSaveReason.MEDIA_CHANGED, update!!.reason)
        assertTrue(update.clearPending)
    }

    @Test
    fun `rebinding a session keeps confirmed position and watched content`() {
        val engine = ExternalPlaybackProgressEngine(pending())
        bind(engine, positionMs = 0L)
        val first = engine.onSnapshot(
            snapshot(
                state = ExternalPlaybackState.PLAYING,
                positionMs = 5_000L,
                observedAtElapsedMs = at(6),
            )
        )
        assertNotNull(first)
        assertEquals(5_000L, first!!.watchedContentMs)

        engine.rebindSession(
            snapshot(
                state = ExternalPlaybackState.PLAYING,
                positionMs = 60_000L,
                observedAtElapsedMs = at(7),
                sessionToken = "replacement-token",
            ),
            at(7),
        )

        assertEquals("replacement-token", engine.boundSessionToken)
        assertEquals(5_000L, engine.watchedContentMs)
        assertEquals(5_000L, engine.lastPersistedPositionMs)

        val afterRebind = engine.onSnapshot(
            snapshot(
                state = ExternalPlaybackState.PLAYING,
                positionMs = 65_000L,
                observedAtElapsedMs = at(12),
                sessionToken = "replacement-token",
            )
        )
        assertNotNull(afterRebind)
        assertEquals(65_000L, afterRebind!!.positionMs)
        assertEquals(10_000L, afterRebind.watchedContentMs)
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

    @Test
    fun `zero update timestamps advance position between consecutive scans`() {
        val engine = ExternalPlaybackProgressEngine(pending())
        assertNull(
            engine.onSnapshot(
                snapshot(
                    state = ExternalPlaybackState.PLAYING,
                    positionMs = 0L,
                    observedAtElapsedMs = at(1),
                    lastPositionUpdateElapsedMs = 0L,
                )
            )
        )

        val first = engine.onSnapshot(
            snapshot(
                state = ExternalPlaybackState.PLAYING,
                positionMs = 0L,
                observedAtElapsedMs = at(6),
                lastPositionUpdateElapsedMs = 0L,
            )
        )
        assertNotNull(first)
        assertEquals(5_000L, first!!.positionMs)
        assertEquals(5_000L, first.watchedContentMs)

        val second = engine.onSnapshot(
            snapshot(
                state = ExternalPlaybackState.PLAYING,
                positionMs = 0L,
                observedAtElapsedMs = at(11),
                lastPositionUpdateElapsedMs = 0L,
            )
        )
        assertNotNull(second)
        assertEquals(10_000L, second!!.positionMs)
        assertEquals(10_000L, second.watchedContentMs)
    }

    @Test
    fun `a static timestamp-less position still makes progress without double counting`() {
        val engine = ExternalPlaybackProgressEngine(pending())
        assertNull(
            engine.onSnapshot(
                snapshot(
                    state = ExternalPlaybackState.PLAYING,
                    positionMs = 0L,
                    observedAtElapsedMs = at(1),
                    lastPositionUpdateElapsedMs = 0L,
                )
            )
        )

        val first = engine.onSnapshot(
            snapshot(
                state = ExternalPlaybackState.PLAYING,
                positionMs = 5_000L,
                observedAtElapsedMs = at(6),
                lastPositionUpdateElapsedMs = 0L,
            )
        )
        assertNotNull(first)
        assertEquals(5_000L, first!!.positionMs)

        val second = engine.onSnapshot(
            snapshot(
                state = ExternalPlaybackState.PLAYING,
                positionMs = 10_000L,
                observedAtElapsedMs = at(11),
                lastPositionUpdateElapsedMs = 0L,
            )
        )
        assertNotNull(second)
        assertEquals(10_000L, second!!.positionMs)
        assertEquals(10_000L, second.watchedContentMs)
    }

    @Test
    fun `fallback estimation respects playback speed changes`() {
        val engine = ExternalPlaybackProgressEngine(pending())
        assertNull(
            engine.onSnapshot(
                snapshot(
                    state = ExternalPlaybackState.PLAYING,
                    positionMs = 0L,
                    speed = 1f,
                    observedAtElapsedMs = at(1),
                    lastPositionUpdateElapsedMs = 0L,
                )
            )
        )

        val atDoubleSpeed = engine.onSnapshot(
            snapshot(
                state = ExternalPlaybackState.PLAYING,
                positionMs = 0L,
                speed = 2f,
                observedAtElapsedMs = at(6),
                lastPositionUpdateElapsedMs = 0L,
            )
        )
        assertNotNull(atDoubleSpeed)
        assertEquals(5_000L, atDoubleSpeed!!.positionMs)

        val later = engine.onSnapshot(
            snapshot(
                state = ExternalPlaybackState.PLAYING,
                positionMs = 0L,
                speed = 2f,
                observedAtElapsedMs = at(11),
                lastPositionUpdateElapsedMs = 0L,
            )
        )
        assertNotNull(later)
        assertEquals(15_000L, later!!.positionMs)
        assertEquals(15_000L, later.watchedContentMs)
    }

    @Test
    fun `fallback estimation ignores paused time`() {
        val engine = ExternalPlaybackProgressEngine(pending())
        assertNull(
            engine.onSnapshot(
                snapshot(
                    state = ExternalPlaybackState.PLAYING,
                    positionMs = 0L,
                    observedAtElapsedMs = at(1),
                    lastPositionUpdateElapsedMs = 0L,
                )
            )
        )
        assertNotNull(
            engine.onSnapshot(
                snapshot(
                    state = ExternalPlaybackState.PAUSED,
                    positionMs = 0L,
                    observedAtElapsedMs = at(6),
                    lastPositionUpdateElapsedMs = 0L,
                )
            )
        )
        assertNull(
            engine.onSnapshot(
                snapshot(
                    state = ExternalPlaybackState.PLAYING,
                    positionMs = 0L,
                    observedAtElapsedMs = at(11),
                    lastPositionUpdateElapsedMs = 0L,
                )
            )
        )

        val resumed = engine.onSnapshot(
            snapshot(
                state = ExternalPlaybackState.PLAYING,
                positionMs = 0L,
                observedAtElapsedMs = at(16),
                lastPositionUpdateElapsedMs = 0L,
            )
        )
        assertNotNull(resumed)
        assertEquals(5_000L, resumed!!.positionMs)
        assertEquals(5_000L, resumed.watchedContentMs)
    }

    @Test
    fun `fallback estimation ignores buffering time`() {
        val engine = ExternalPlaybackProgressEngine(pending())
        assertNull(
            engine.onSnapshot(
                snapshot(
                    state = ExternalPlaybackState.PLAYING,
                    positionMs = 0L,
                    observedAtElapsedMs = at(1),
                    lastPositionUpdateElapsedMs = 0L,
                )
            )
        )
        assertNotNull(
            engine.onSnapshot(
                snapshot(
                    state = ExternalPlaybackState.BUFFERING,
                    positionMs = 0L,
                    observedAtElapsedMs = at(6),
                    lastPositionUpdateElapsedMs = 0L,
                )
            )
        )
        assertNull(
            engine.onSnapshot(
                snapshot(
                    state = ExternalPlaybackState.PLAYING,
                    positionMs = 0L,
                    observedAtElapsedMs = at(16),
                    lastPositionUpdateElapsedMs = 0L,
                )
            )
        )

        val resumed = engine.onSnapshot(
            snapshot(
                state = ExternalPlaybackState.PLAYING,
                positionMs = 0L,
                observedAtElapsedMs = at(21),
                lastPositionUpdateElapsedMs = 0L,
            )
        )
        assertNotNull(resumed)
        assertEquals(5_000L, resumed!!.positionMs)
        assertEquals(5_000L, resumed.watchedContentMs)
    }

    @Test
    fun `switching between timestamp and fallback estimation does not double count`() {
        val engine = ExternalPlaybackProgressEngine(pending())
        assertNull(
            engine.onSnapshot(
                snapshot(
                    state = ExternalPlaybackState.PLAYING,
                    positionMs = 0L,
                    observedAtElapsedMs = at(1),
                    lastPositionUpdateElapsedMs = at(1),
                )
            )
        )

        val fallback = engine.onSnapshot(
            snapshot(
                state = ExternalPlaybackState.PLAYING,
                positionMs = 0L,
                observedAtElapsedMs = at(6),
                lastPositionUpdateElapsedMs = 0L,
            )
        )
        assertNotNull(fallback)
        assertEquals(5_000L, fallback!!.positionMs)

        val timestamped = engine.onSnapshot(
            snapshot(
                state = ExternalPlaybackState.PLAYING,
                positionMs = 6_000L,
                observedAtElapsedMs = at(11),
                lastPositionUpdateElapsedMs = at(11),
            )
        )
        assertNotNull(timestamped)
        assertEquals(6_000L, timestamped!!.positionMs)
        assertEquals(6_000L, timestamped.watchedContentMs)

        val nextTimestamped = engine.onSnapshot(
            snapshot(
                state = ExternalPlaybackState.PLAYING,
                positionMs = 11_000L,
                observedAtElapsedMs = at(16),
                lastPositionUpdateElapsedMs = at(16),
            )
        )
        assertNotNull(nextTimestamped)
        assertEquals(11_000L, nextTimestamped!!.positionMs)
        assertEquals(11_000L, nextTimestamped.watchedContentMs)
    }

    @Test
    fun `fallback estimation caps long observation gaps`() {
        val engine = ExternalPlaybackProgressEngine(pending())
        assertNull(
            engine.onSnapshot(
                snapshot(
                    state = ExternalPlaybackState.PLAYING,
                    positionMs = 0L,
                    observedAtElapsedMs = at(1),
                    lastPositionUpdateElapsedMs = 0L,
                )
            )
        )

        val update = engine.onSnapshot(
            snapshot(
                state = ExternalPlaybackState.PLAYING,
                positionMs = 0L,
                observedAtElapsedMs = at(100),
                lastPositionUpdateElapsedMs = 0L,
            )
        )
        assertNotNull(update)
        assertEquals(ExternalPlaybackProgressEngine.DEFAULT_MAX_SAMPLE_GAP_MS, update!!.positionMs)
        assertEquals(ExternalPlaybackProgressEngine.DEFAULT_MAX_SAMPLE_GAP_MS, update.watchedContentMs)
    }

    @Test
    fun `fallback estimation detects forward and backward seeks without watched content`() {
        val engine = ExternalPlaybackProgressEngine(pending())
        assertNull(
            engine.onSnapshot(
                snapshot(
                    state = ExternalPlaybackState.PLAYING,
                    positionMs = 0L,
                    observedAtElapsedMs = at(1),
                    lastPositionUpdateElapsedMs = 0L,
                )
            )
        )

        val forward = engine.onSnapshot(
            snapshot(
                state = ExternalPlaybackState.PLAYING,
                positionMs = 50_000L,
                observedAtElapsedMs = at(6),
                lastPositionUpdateElapsedMs = 0L,
            )
        )
        assertNotNull(forward)
        assertEquals(50_000L, forward!!.positionMs)
        assertEquals(0L, forward.watchedContentMs)

        val backward = engine.onSnapshot(
            snapshot(
                state = ExternalPlaybackState.PLAYING,
                positionMs = 10_000L,
                observedAtElapsedMs = at(11),
                lastPositionUpdateElapsedMs = 0L,
            )
        )
        assertNotNull(backward)
        assertEquals(10_000L, backward!!.positionMs)
        assertEquals(0L, backward.watchedContentMs)
    }

    @Test
    fun `fallback estimation clamps positions to the known duration`() {
        val engine = ExternalPlaybackProgressEngine(pending())
        assertNull(
            engine.onSnapshot(
                snapshot(
                    state = ExternalPlaybackState.PLAYING,
                    positionMs = 95_000L,
                    observedAtElapsedMs = at(1),
                    lastPositionUpdateElapsedMs = 0L,
                )
            )
        )

        val update = engine.onSnapshot(
            snapshot(
                state = ExternalPlaybackState.PLAYING,
                positionMs = 95_000L,
                observedAtElapsedMs = at(6),
                lastPositionUpdateElapsedMs = 0L,
            )
        )
        assertNotNull(update)
        assertEquals(DURATION, update!!.positionMs)
        assertTrue(update.completed)
    }

    @Test
    fun `zero snapshot duration falls back to the episode duration`() {
        val engine = ExternalPlaybackProgressEngine(pending())
        assertNull(
            engine.onSnapshot(
                snapshot(
                    state = ExternalPlaybackState.PLAYING,
                    positionMs = 0L,
                    durationMs = 0L,
                    observedAtElapsedMs = at(1),
                )
            )
        )

        val update = engine.onSnapshot(
            snapshot(
                state = ExternalPlaybackState.PLAYING,
                positionMs = 5_000L,
                durationMs = 0L,
                observedAtElapsedMs = at(6),
            )
        )
        assertNotNull(update)
        assertEquals(DURATION, update!!.durationMs)
        assertEquals(5_000L, update.positionMs)
    }

    @Test
    fun `a later snapshot never replaces a valid duration with zero`() {
        val engine = ExternalPlaybackProgressEngine(pending())
        bind(engine)

        val update = engine.onSnapshot(
            snapshot(
                state = ExternalPlaybackState.PLAYING,
                positionMs = 5_000L,
                durationMs = 0L,
                observedAtElapsedMs = at(6),
            )
        )
        assertNotNull(update)
        assertEquals(DURATION, update!!.durationMs)
    }

    @Test
    fun `without any duration the session keeps its position until duration arrives`() {
        val engine = ExternalPlaybackProgressEngine(pending(expectedDurationMs = 0L))
        assertNull(
            engine.onSnapshot(
                snapshot(
                    state = ExternalPlaybackState.PLAYING,
                    positionMs = 0L,
                    durationMs = 0L,
                    observedAtElapsedMs = at(1),
                )
            )
        )

        assertNull(
            engine.onSnapshot(
                snapshot(
                    state = ExternalPlaybackState.PLAYING,
                    positionMs = 5_000L,
                    durationMs = 0L,
                    observedAtElapsedMs = at(6),
                )
            )
        )
        assertEquals(5_000L, engine.lastKnownPositionMs)
        assertEquals(0L, engine.lastKnownDurationMs)
        assertFalse(engine.completed)

        val update = engine.onSnapshot(
            snapshot(
                state = ExternalPlaybackState.PLAYING,
                positionMs = 10_000L,
                durationMs = DURATION,
                observedAtElapsedMs = at(11),
            )
        )
        assertNotNull(update)
        assertEquals(DURATION, update!!.durationMs)
        assertEquals(10_000L, update.positionMs)
    }

    @Test
    fun `resume policy accepts an incomplete VOD position`() {
        assertEquals(
            60_000L,
            ExternalPlaybackResumePolicy.resolveResumePositionMs(
                savedPositionMs = 60_000L,
                savedDurationMs = DURATION,
                isLive = false,
                watchState = VideoWatchState.None,
            )
        )
    }

    @Test
    fun `resume policy rejects completed watched live and invalid positions`() {
        assertEquals(
            0L,
            ExternalPlaybackResumePolicy.resolveResumePositionMs(
                savedPositionMs = 0L,
                savedDurationMs = DURATION,
                isLive = false,
                watchState = VideoWatchState.None,
            )
        )
        assertEquals(
            0L,
            ExternalPlaybackResumePolicy.resolveResumePositionMs(
                savedPositionMs = 60_000L,
                savedDurationMs = 0L,
                isLive = false,
                watchState = VideoWatchState.None,
            )
        )
        assertEquals(
            0L,
            ExternalPlaybackResumePolicy.resolveResumePositionMs(
                savedPositionMs = 60_000L,
                savedDurationMs = DURATION,
                isLive = true,
                watchState = VideoWatchState.None,
            )
        )
        assertEquals(
            0L,
            ExternalPlaybackResumePolicy.resolveResumePositionMs(
                savedPositionMs = 60_000L,
                savedDurationMs = DURATION,
                isLive = false,
                watchState = VideoWatchState.Watched,
            )
        )
        assertEquals(
            0L,
            ExternalPlaybackResumePolicy.resolveResumePositionMs(
                savedPositionMs = 95_000L,
                savedDurationMs = DURATION,
                isLive = false,
                watchState = VideoWatchState.None,
            )
        )
        assertEquals(
            0L,
            ExternalPlaybackResumePolicy.resolveResumePositionMs(
                savedPositionMs = DURATION,
                savedDurationMs = DURATION,
                isLive = false,
                watchState = VideoWatchState.None,
            )
        )
    }

    @Test
    fun `resume seek waits for a valid duration before issuing the command`() {
        val controller = ExternalPlaybackResumeController(pending(initialPositionMs = 60_000L))

        assertNull(
            controller.onSnapshot(
                snapshot(
                    state = ExternalPlaybackState.PLAYING,
                    positionMs = 0L,
                    durationMs = 0L,
                    observedAtElapsedMs = at(1),
                ),
                TOKEN,
                at(1),
            )
        )
        assertEquals(ExternalPlaybackResumeState.WAITING_FOR_MEDIA, controller.state)

        val action = controller.onSnapshot(
            snapshot(
                state = ExternalPlaybackState.PLAYING,
                positionMs = 1_000L,
                observedAtElapsedMs = at(2),
            ),
            TOKEN,
            at(2),
        )

        assertTrue(action is ExternalPlaybackResumeAction.SeekTo)
        assertEquals(60_000L, (action as ExternalPlaybackResumeAction.SeekTo).positionMs)
        assertEquals(60_000L, controller.lastRequestedPositionMs)
        assertEquals(ExternalPlaybackResumeState.SEEK_REQUESTED, controller.state)
    }

    @Test
    fun `resume seek ignores other packages and session tokens`() {
        val controller = ExternalPlaybackResumeController(pending(initialPositionMs = 60_000L))

        assertNull(
            controller.onSnapshot(
                snapshot(
                    state = ExternalPlaybackState.PLAYING,
                    positionMs = 0L,
                    observedAtElapsedMs = at(1),
                    packageName = "com.example.other",
                ),
                TOKEN,
                at(1),
            )
        )
        assertNull(
            controller.onSnapshot(
                snapshot(
                    state = ExternalPlaybackState.PLAYING,
                    positionMs = 0L,
                    observedAtElapsedMs = at(2),
                    sessionToken = "another-token",
                ),
                TOKEN,
                at(2),
            )
        )
        assertEquals(ExternalPlaybackResumeState.WAITING_FOR_MEDIA, controller.state)

        val action = controller.onSnapshot(
            snapshot(
                state = ExternalPlaybackState.PLAYING,
                positionMs = 0L,
                observedAtElapsedMs = at(3),
            ),
            TOKEN,
            at(3),
        )
        assertTrue(action is ExternalPlaybackResumeAction.SeekTo)
    }

    @Test
    fun `resume seek is not issued when ACTION_SEEK_TO is missing`() {
        val controller = ExternalPlaybackResumeController(pending(initialPositionMs = 60_000L))

        assertNull(
            controller.onSnapshot(
                snapshot(
                    state = ExternalPlaybackState.PLAYING,
                    positionMs = 0L,
                    actions = 0L,
                    observedAtElapsedMs = at(1),
                ),
                TOKEN,
                at(1),
            )
        )
        assertNull(
            controller.onSnapshot(
                snapshot(
                    state = ExternalPlaybackState.PLAYING,
                    positionMs = 5_000L,
                    actions = 0L,
                    observedAtElapsedMs = at(15),
                ),
                TOKEN,
                at(15),
            )
        )

        val failed = controller.onSnapshot(
            snapshot(
                state = ExternalPlaybackState.PLAYING,
                positionMs = 10_000L,
                actions = 0L,
                observedAtElapsedMs = at(16),
            ),
            TOKEN,
            at(16),
        )

        assertEquals(ExternalPlaybackResumeAction.Failed, failed)
        assertEquals(ExternalPlaybackResumeState.FAILED, controller.state)
        assertNull(controller.lastRequestedPositionMs)
    }

    @Test
    fun `resume seek is issued once and confirmed by the reported position`() {
        val controller = ExternalPlaybackResumeController(pending(initialPositionMs = 60_000L))

        val seek = controller.onSnapshot(
            snapshot(
                state = ExternalPlaybackState.PLAYING,
                positionMs = 0L,
                observedAtElapsedMs = at(1),
            ),
            TOKEN,
            at(1),
        )
        assertTrue(seek is ExternalPlaybackResumeAction.SeekTo)

        val confirmed = controller.onSnapshot(
            snapshot(
                state = ExternalPlaybackState.PLAYING,
                positionMs = 60_500L,
                observedAtElapsedMs = at(6),
            ),
            TOKEN,
            at(6),
        )

        assertEquals(ExternalPlaybackResumeAction.Confirmed, confirmed)
        assertEquals(ExternalPlaybackResumeState.CONFIRMED, controller.state)
        assertEquals(60_500L, controller.confirmedPositionMs)
        assertNull(
            controller.onSnapshot(
                snapshot(
                    state = ExternalPlaybackState.PLAYING,
                    positionMs = 65_000L,
                    observedAtElapsedMs = at(11),
                ),
                TOKEN,
                at(11),
            )
        )
    }

    @Test
    fun `ignored resume seek fails without retrying`() {
        val controller = ExternalPlaybackResumeController(pending(initialPositionMs = 60_000L))

        assertTrue(
            controller.onSnapshot(
                snapshot(
                    state = ExternalPlaybackState.PLAYING,
                    positionMs = 0L,
                    observedAtElapsedMs = at(1),
                ),
                TOKEN,
                at(1),
            ) is ExternalPlaybackResumeAction.SeekTo
        )
        assertNull(
            controller.onSnapshot(
                snapshot(
                    state = ExternalPlaybackState.PLAYING,
                    positionMs = 5_000L,
                    observedAtElapsedMs = at(6),
                ),
                TOKEN,
                at(6),
            )
        )
        assertNull(
            controller.onSnapshot(
                snapshot(
                    state = ExternalPlaybackState.PLAYING,
                    positionMs = 10_000L,
                    observedAtElapsedMs = at(11),
                ),
                TOKEN,
                at(11),
            )
        )

        assertEquals(
            ExternalPlaybackResumeAction.Failed,
            controller.onSnapshot(
                snapshot(
                    state = ExternalPlaybackState.PLAYING,
                    positionMs = 15_000L,
                    observedAtElapsedMs = at(16),
                ),
                TOKEN,
                at(16),
            )
        )
        assertEquals(ExternalPlaybackResumeState.FAILED, controller.state)
        assertNull(
            controller.onSnapshot(
                snapshot(
                    state = ExternalPlaybackState.PLAYING,
                    positionMs = 20_000L,
                    observedAtElapsedMs = at(21),
                ),
                TOKEN,
                at(21),
            )
        )
    }

    @Test
    fun `resume seek command failure never retries`() {
        val controller = ExternalPlaybackResumeController(pending(initialPositionMs = 60_000L))

        assertTrue(
            controller.onSnapshot(
                snapshot(
                    state = ExternalPlaybackState.PLAYING,
                    positionMs = 0L,
                    observedAtElapsedMs = at(1),
                ),
                TOKEN,
                at(1),
            ) is ExternalPlaybackResumeAction.SeekTo
        )
        controller.onSeekCommandFailed()

        assertEquals(ExternalPlaybackResumeState.FAILED, controller.state)
        assertNull(
            controller.onSnapshot(
                snapshot(
                    state = ExternalPlaybackState.PLAYING,
                    positionMs = 0L,
                    observedAtElapsedMs = at(6),
                ),
                TOKEN,
                at(6),
            )
        )
    }

    @Test
    fun `resume seek never goes beyond the media duration`() {
        val controller = ExternalPlaybackResumeController(pending(initialPositionMs = 120_000L))

        assertEquals(
            ExternalPlaybackResumeAction.Failed,
            controller.onSnapshot(
                snapshot(
                    state = ExternalPlaybackState.PLAYING,
                    positionMs = 0L,
                    durationMs = DURATION,
                    observedAtElapsedMs = at(1),
                ),
                TOKEN,
                at(1),
            )
        )
        assertEquals(ExternalPlaybackResumeState.FAILED, controller.state)
    }

    @Test
    fun `resume seek does not move backwards when playback is already past the target`() {
        val controller = ExternalPlaybackResumeController(pending(initialPositionMs = 60_000L))

        assertEquals(
            ExternalPlaybackResumeAction.Confirmed,
            controller.onSnapshot(
                snapshot(
                    state = ExternalPlaybackState.PLAYING,
                    positionMs = 80_000L,
                    observedAtElapsedMs = at(1),
                ),
                TOKEN,
                at(1),
            )
        )
        assertEquals(ExternalPlaybackResumeState.CONFIRMED, controller.state)
        assertEquals(80_000L, controller.confirmedPositionMs)
        assertNull(controller.lastRequestedPositionMs)
    }

    @Test
    fun `resume seek is skipped for live episodes`() {
        val controller = ExternalPlaybackResumeController(
            pending(initialPositionMs = 60_000L, tvType = TvType.Live)
        )

        assertEquals(
            ExternalPlaybackResumeAction.Failed,
            controller.onSnapshot(
                snapshot(
                    state = ExternalPlaybackState.PLAYING,
                    positionMs = 0L,
                    observedAtElapsedMs = at(1),
                ),
                TOKEN,
                at(1),
            )
        )
        assertEquals(ExternalPlaybackResumeState.FAILED, controller.state)
    }

    @Test
    fun `persisted resume attempt prevents a second seek after process restart`() {
        val controller = ExternalPlaybackResumeController(
            pending(initialPositionMs = 60_000L, resumeSeekAttempted = true)
        )

        assertEquals(ExternalPlaybackResumeState.ALREADY_ATTEMPTED, controller.state)
        assertNull(
            controller.onSnapshot(
                snapshot(
                    state = ExternalPlaybackState.PLAYING,
                    positionMs = 0L,
                    observedAtElapsedMs = at(1),
                ),
                TOKEN,
                at(1),
            )
        )
    }

    @Test
    fun `engine saves normally after the resume seek is confirmed`() {
        val engine = ExternalPlaybackProgressEngine(pending(initialPositionMs = 60_000L))
        bind(engine, positionMs = 0L)
        engine.onResumeSeekConfirmed()

        val update = engine.onSnapshot(
            snapshot(
                state = ExternalPlaybackState.PLAYING,
                positionMs = 60_000L,
                observedAtElapsedMs = at(6),
            )
        )

        assertNotNull(update)
        assertEquals(60_000L, update!!.positionMs)
    }

    @Test
    fun `engine preserves a completed episode position when playback starts at zero`() {
        val engine = ExternalPlaybackProgressEngine(
            pending(initialPositionMs = 0L, existingPositionMs = 96_000L)
        )
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
        assertEquals(-1L, engine.lastPersistedPositionMs)

        var lastUpdate: com.lagradost.cloudstream3.actions.ExternalPlaybackUpdate? = null
        for (seconds in 11L..61L step 5L) {
            lastUpdate = engine.onSnapshot(
                snapshot(
                    state = ExternalPlaybackState.PLAYING,
                    positionMs = (seconds - 1L) * 1_000L,
                    observedAtElapsedMs = at(seconds),
                )
            )
            if (seconds < 61L) assertNull(lastUpdate)
        }

        assertNotNull(lastUpdate)
        assertEquals(60_000L, lastUpdate!!.positionMs)
    }
}
