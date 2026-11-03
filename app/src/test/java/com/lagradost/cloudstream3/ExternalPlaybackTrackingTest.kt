package com.lagradost.cloudstream3

import com.lagradost.cloudstream3.actions.ExternalPlaybackExpired
import com.lagradost.cloudstream3.actions.ExternalPlaybackHistoryDelegate
import com.lagradost.cloudstream3.actions.ExternalPlaybackProgressEngine
import com.lagradost.cloudstream3.actions.ExternalPlaybackResumeAction
import com.lagradost.cloudstream3.actions.ExternalPlaybackResumeController
import com.lagradost.cloudstream3.actions.ExternalPlaybackResumePolicy
import com.lagradost.cloudstream3.actions.ExternalPlaybackResumeState
import com.lagradost.cloudstream3.actions.ExternalPlaybackSaveReason
import com.lagradost.cloudstream3.actions.ExternalPlaybackSessionStore
import com.lagradost.cloudstream3.actions.ExternalPlaybackSnapshot
import com.lagradost.cloudstream3.actions.ExternalPlaybackState
import com.lagradost.cloudstream3.actions.ExternalPlaybackUpdate
import com.lagradost.cloudstream3.actions.NextEpisodeResolution
import com.lagradost.cloudstream3.actions.NextEpisodeResolver
import com.lagradost.cloudstream3.actions.PendingExternalPlayback
import com.lagradost.cloudstream3.ui.result.ResultEpisode
import com.lagradost.cloudstream3.ui.result.ResultViewModel2
import com.lagradost.cloudstream3.ui.result.VideoWatchState
import com.lagradost.cloudstream3.utils.AppUtils
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
        isRewatchOfCompletedEpisode: Boolean = false,
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
            isRewatchOfCompletedEpisode = isRewatchOfCompletedEpisode,
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

    // -------------------------------------------------------------------------
    // NextEpisodeResolver regression tests
    // -------------------------------------------------------------------------

    private fun seriesEpisode(
        id: Int,
        season: Int,
        episode: Int,
        parentId: Int = 500,
        name: String = "Episode $episode",
        tvType: TvType = TvType.TvSeries,
        totalEpisodeIndex: Int? = null,
    ): ResultEpisode {
        return ResultEpisode(
            headerName = "Season $season",
            name = name,
            poster = "https://example.com/poster.jpg",
            episode = episode,
            seasonIndex = season,
            season = season,
            data = "episode_data_$id",
            apiName = "TestApi",
            id = id,
            index = episode - 1,
            position = 0L,
            duration = DURATION,
            score = null,
            description = "Description for $name",
            isFiller = false,
            tvType = tvType,
            parentId = parentId,
            videoWatchState = VideoWatchState.None,
            totalEpisodeIndex = totalEpisodeIndex,
        )
    }

    private class RecordingHistoryDelegate : ExternalPlaybackHistoryDelegate {
        val setViewPosAndResumeCalls = mutableListOf<ViewPosAndResumeCall>()
        val setViewPosCalls = mutableListOf<ViewPosCall>()
        val setLastWatchedCalls = mutableListOf<LastWatchedCall>()
        val removeLastWatchedCalls = mutableListOf<Int?>()
        val clearWatchStateCalls = mutableListOf<Int?>()
        var bookmarksUpdatedCount = 0
        var resultUiUpdatedCount = 0

        val viewPosStore = mutableMapOf<Int, Pair<Long, Long>>()
        val lastWatchedStore = mutableMapOf<Int, LastWatchedEntry>()
        val watchStateStore = mutableMapOf<Int, VideoWatchState>()

        data class ViewPosAndResumeCall(
            val id: Int?,
            val position: Long,
            val duration: Long,
            val currentEpisode: Any?,
            val nextEpisode: Any?,
        )

        data class ViewPosCall(
            val id: Int?,
            val position: Long,
            val duration: Long,
        )

        data class LastWatchedCall(
            val parentId: Int?,
            val episodeId: Int?,
            val episode: Int?,
            val season: Int?,
            val isFromDownload: Boolean,
        )

        data class LastWatchedEntry(
            val parentId: Int?,
            val episodeId: Int?,
            val episode: Int?,
            val season: Int?,
        )

        override fun setViewPosAndResume(
            id: Int?,
            position: Long,
            duration: Long,
            currentEpisode: Any?,
            nextEpisode: Any?,
        ) {
            setViewPosAndResumeCalls.add(
                ViewPosAndResumeCall(id, position, duration, currentEpisode, nextEpisode)
            )
            if (id != null) {
                viewPosStore[id] = position to duration
            }
            val percentage = if (duration > 0) position * 100L / duration else 0L
            val isCompleted = percentage >= 90L
            val resumeMeta = if (isCompleted) nextEpisode else currentEpisode
            if (resumeMeta == null && isCompleted) {
                val current = currentEpisode as? ResultEpisode
                if (current != null) {
                    removeLastWatched(current.parentId)
                }
            } else if (resumeMeta is ResultEpisode) {
                setLastWatched(
                    resumeMeta.parentId,
                    resumeMeta.id,
                    resumeMeta.episode,
                    resumeMeta.season,
                    isFromDownload = false,
                )
            }
        }

        override fun setViewPos(id: Int?, position: Long, duration: Long) {
            setViewPosCalls.add(ViewPosCall(id, position, duration))
            if (id != null) {
                viewPosStore[id] = position to duration
            }
        }

        override fun setLastWatched(
            parentId: Int?,
            episodeId: Int?,
            episode: Int?,
            season: Int?,
            isFromDownload: Boolean,
        ) {
            setLastWatchedCalls.add(
                LastWatchedCall(parentId, episodeId, episode, season, isFromDownload)
            )
            if (parentId != null) {
                lastWatchedStore[parentId] = LastWatchedEntry(parentId, episodeId, episode, season)
            }
        }

        override fun removeLastWatched(parentId: Int?) {
            removeLastWatchedCalls.add(parentId)
            if (parentId != null) {
                lastWatchedStore.remove(parentId)
            }
        }

        override fun clearEpisodeWatchState(episodeId: Int?) {
            clearWatchStateCalls.add(episodeId)
            if (episodeId != null) {
                watchStateStore[episodeId] = VideoWatchState.None
            }
        }

        override fun notifyBookmarksUpdated() {
            bookmarksUpdatedCount++
        }

        override fun notifyResultUiUpdated() {
            resultUiUpdatedCount++
        }
    }

    @Test
    fun `resolveNextEpisode advances episode 1 to episode 2 within same season`() {
        val ep1 = seriesEpisode(id = 1, season = 1, episode = 1)
        val ep2 = seriesEpisode(id = 2, season = 1, episode = 2)
        val catalogue = mapOf(
            ResultViewModel2.EpisodeIndexer(DubStatus.None, 1) to listOf(ep1, ep2)
        )

        val resolution = NextEpisodeResolver.resolveNextEpisode(catalogue, ep1)
        assertTrue(resolution is NextEpisodeResolution.Found)
        assertEquals(ep2.id, (resolution as NextEpisodeResolution.Found).nextEpisode.id)
        assertTrue(resolution.isComplete)
    }

    @Test
    fun `resolveNextEpisode advances final episode of season 1 to first episode of season 2`() {
        val s1e1 = seriesEpisode(id = 1, season = 1, episode = 1)
        val s1e2 = seriesEpisode(id = 2, season = 1, episode = 2)
        val s2e1 = seriesEpisode(id = 3, season = 2, episode = 1)
        val s2e2 = seriesEpisode(id = 4, season = 2, episode = 2)
        val catalogue = mapOf(
            ResultViewModel2.EpisodeIndexer(DubStatus.None, 1) to listOf(s1e1, s1e2),
            ResultViewModel2.EpisodeIndexer(DubStatus.None, 2) to listOf(s2e1, s2e2),
        )

        val resolution = NextEpisodeResolver.resolveNextEpisode(catalogue, s1e2)
        assertTrue(resolution is NextEpisodeResolution.Found)
        val next = (resolution as NextEpisodeResolution.Found).nextEpisode
        assertEquals(s2e1.id, next.id)
        assertEquals(2, next.season)
        assertEquals(1, next.episode)
    }

    @Test
    fun `resolveNextEpisode returns LastEpisode for final episode of show`() {
        val s1e1 = seriesEpisode(id = 1, season = 1, episode = 1)
        val s2e1 = seriesEpisode(id = 2, season = 2, episode = 1)
        val catalogue = mapOf(
            ResultViewModel2.EpisodeIndexer(DubStatus.None, 1) to listOf(s1e1),
            ResultViewModel2.EpisodeIndexer(DubStatus.None, 2) to listOf(s2e1),
        )

        val resolution = NextEpisodeResolver.resolveNextEpisode(catalogue, s2e1)
        assertEquals(NextEpisodeResolution.LastEpisode, resolution)
        assertTrue(resolution.isComplete)
        assertNull(resolution.resolvedEpisode)
    }

    @Test
    fun `resolveNextEpisode stays strictly within selected dub status for anime`() {
        val dubEp1 = seriesEpisode(id = 10, season = 1, episode = 1, name = "Dub 1", tvType = TvType.Anime)
        val dubEp2 = seriesEpisode(id = 20, season = 1, episode = 2, name = "Dub 2", tvType = TvType.Anime)
        val subEp1 = seriesEpisode(id = 11, season = 1, episode = 1, name = "Sub 1", tvType = TvType.Anime)
        val subEp2 = seriesEpisode(id = 21, season = 1, episode = 2, name = "Sub 2", tvType = TvType.Anime)
        val subEp3 = seriesEpisode(id = 31, season = 1, episode = 3, name = "Sub 3", tvType = TvType.Anime)

        val catalogue = mapOf(
            ResultViewModel2.EpisodeIndexer(DubStatus.Dubbed, 1) to listOf(dubEp1, dubEp2),
            ResultViewModel2.EpisodeIndexer(DubStatus.Subbed, 1) to listOf(subEp1, subEp2, subEp3),
        )

        // Resolving dubEp1 should yield dubEp2, not subEp2
        val dubRes1 = NextEpisodeResolver.resolveNextEpisode(catalogue, dubEp1, preferredDubStatus = DubStatus.Dubbed)
        assertTrue(dubRes1 is NextEpisodeResolution.Found)
        assertEquals(dubEp2.id, (dubRes1 as NextEpisodeResolution.Found).nextEpisode.id)

        // Resolving dubEp2 (the last dubbed episode) must return LastEpisode, NOT advance to subEp3
        val dubRes2 = NextEpisodeResolver.resolveNextEpisode(catalogue, dubEp2, preferredDubStatus = DubStatus.Dubbed)
        assertEquals(NextEpisodeResolution.LastEpisode, dubRes2)
    }

    @Test
    fun `resolveNextEpisode is immune to UI descending sort order`() {
        val ep1 = seriesEpisode(id = 1, season = 1, episode = 1)
        val ep2 = seriesEpisode(id = 2, season = 1, episode = 2)
        // Reverse order as if the UI sorted descending
        val catalogue = mapOf(
            ResultViewModel2.EpisodeIndexer(DubStatus.None, 1) to listOf(ep2, ep1)
        )

        val resolution = NextEpisodeResolver.resolveNextEpisode(catalogue, ep1)
        assertTrue(resolution is NextEpisodeResolution.Found)
        assertEquals(ep2.id, (resolution as NextEpisodeResolution.Found).nextEpisode.id)
    }

    @Test
    fun `resolveNextEpisode returns Unknown when episode is not in catalogue`() {
        val ep1 = seriesEpisode(id = 1, season = 1, episode = 1)
        val uncatalogued = seriesEpisode(id = 9999, season = 1, episode = 99)
        val catalogue = mapOf(
            ResultViewModel2.EpisodeIndexer(DubStatus.None, 1) to listOf(ep1)
        )

        val resolution = NextEpisodeResolver.resolveNextEpisode(catalogue, uncatalogued)
        assertEquals(NextEpisodeResolution.Unknown, resolution)
        assertFalse(resolution.isComplete)
        assertNull(resolution.resolvedEpisode)
    }

    @Test
    fun `resolveNextEpisode handles duplicate IDs and empty catalogue safely`() {
        val emptyCatalogue = emptyMap<ResultViewModel2.EpisodeIndexer, List<ResultEpisode>>()
        val ep1 = seriesEpisode(id = 1, season = 1, episode = 1)
        assertEquals(NextEpisodeResolver.resolveNextEpisode(emptyCatalogue, ep1), NextEpisodeResolution.Unknown)

        // Duplicate episode entries in the list
        val duplicateCatalogue = mapOf(
            ResultViewModel2.EpisodeIndexer(DubStatus.None, 1) to listOf(ep1, ep1)
        )
        // With duplicate single episode, it should identify it as LastEpisode rather than returning itself
        val resolution = NextEpisodeResolver.resolveNextEpisode(duplicateCatalogue, ep1)
        assertEquals(NextEpisodeResolution.LastEpisode, resolution)
    }

    // -------------------------------------------------------------------------
    // History persistence regression tests
    // -------------------------------------------------------------------------

    @Test
    fun `completing episode with resolved next episode saves next episode as Continue Watching`() {
        val ep1 = seriesEpisode(id = 1, season = 1, episode = 1, parentId = 500)
        val ep2 = seriesEpisode(id = 2, season = 1, episode = 2, parentId = 500)
        val delegate = RecordingHistoryDelegate()
        val writer = ExternalPlaybackSessionStore.createHistoryWriter(delegate)

        val pendingPlayback = PendingExternalPlayback(
            sessionId = "session-1",
            packageName = PACKAGE,
            episode = ep1,
            streamIdentifier = "hash1",
            initialPositionMs = 0L,
            expectedDurationMs = DURATION,
            startedAtWallClockMs = 1_000L,
            startedAtElapsedRealtimeMs = START,
            nextEpisode = ep2,
            nextEpisodeResolutionComplete = true,
        )

        val update = ExternalPlaybackUpdate(
            positionMs = 95_000L,
            durationMs = 100_000L,
            watchedContentMs = 95_000L,
            state = ExternalPlaybackState.STOPPED,
            reason = ExternalPlaybackSaveReason.STOP,
            immediate = true,
            completed = true,
            sessionEnded = true,
            clearPending = true,
        )

        writer.write(pendingPlayback, update)

        assertEquals(1, delegate.setViewPosAndResumeCalls.size)
        val call = delegate.setViewPosAndResumeCalls.first()
        assertEquals(ep1.id, call.id)
        assertEquals(95_000L, call.position)
        assertEquals(100_000L, call.duration)
        assertEquals(ep1, call.currentEpisode)
        assertEquals(ep2, call.nextEpisode)

        // Continue Watching in simulated storage is now pointing to ep2
        val lastWatched = delegate.lastWatchedStore[ep1.parentId]
        assertNotNull(lastWatched)
        assertEquals(ep2.id, lastWatched!!.episodeId)
        assertEquals(ep2.episode, lastWatched.episode)
        assertEquals(ep2.season, lastWatched.season)
        assertEquals(ep2.parentId, lastWatched.parentId)

        assertEquals(1, delegate.bookmarksUpdatedCount)
        assertEquals(1, delegate.resultUiUpdatedCount)
    }

    @Test
    fun `next episode saved in Continue Watching has correct parent ID, episode ID, season, and episode number`() {
        val ep1 = seriesEpisode(id = 101, season = 1, episode = 10, parentId = 777)
        val ep2 = seriesEpisode(id = 102, season = 2, episode = 1, parentId = 777)
        val delegate = RecordingHistoryDelegate()
        val writer = ExternalPlaybackSessionStore.createHistoryWriter(delegate)

        val pendingPlayback = PendingExternalPlayback(
            sessionId = "session-2",
            packageName = PACKAGE,
            episode = ep1,
            streamIdentifier = "hash2",
            initialPositionMs = 0L,
            expectedDurationMs = DURATION,
            startedAtWallClockMs = 1_000L,
            startedAtElapsedRealtimeMs = START,
            nextEpisode = ep2,
            nextEpisodeResolutionComplete = true,
        )

        val update = ExternalPlaybackUpdate(
            positionMs = 92_000L,
            durationMs = 100_000L,
            watchedContentMs = 92_000L,
            state = ExternalPlaybackState.STOPPED,
            reason = ExternalPlaybackSaveReason.STOP,
            immediate = true,
            completed = true,
            sessionEnded = true,
            clearPending = true,
        )

        writer.write(pendingPlayback, update)

        val lastWatched = delegate.lastWatchedStore[777]
        assertNotNull(lastWatched)
        assertEquals(777, lastWatched!!.parentId)
        assertEquals(102, lastWatched.episodeId)
        assertEquals(2, lastWatched.season)
        assertEquals(1, lastWatched.episode)
    }

    @Test
    fun `completing confirmed final episode removes Continue Watching entry`() {
        val finalEp = seriesEpisode(id = 99, season = 3, episode = 12, parentId = 888)
        val delegate = RecordingHistoryDelegate()
        delegate.setLastWatched(888, 99, 12, 3)
        val writer = ExternalPlaybackSessionStore.createHistoryWriter(delegate)

        val pendingPlayback = PendingExternalPlayback(
            sessionId = "session-3",
            packageName = PACKAGE,
            episode = finalEp,
            streamIdentifier = "hash3",
            initialPositionMs = 0L,
            expectedDurationMs = DURATION,
            startedAtWallClockMs = 1_000L,
            startedAtElapsedRealtimeMs = START,
            nextEpisode = null,
            nextEpisodeResolutionComplete = true,
        )

        val update = ExternalPlaybackUpdate(
            positionMs = 96_000L,
            durationMs = 100_000L,
            watchedContentMs = 96_000L,
            state = ExternalPlaybackState.STOPPED,
            reason = ExternalPlaybackSaveReason.STOP,
            immediate = true,
            completed = true,
            sessionEnded = true,
            clearPending = true,
        )

        writer.write(pendingPlayback, update)

        assertEquals(1, delegate.setViewPosAndResumeCalls.size)
        assertNull(delegate.setViewPosAndResumeCalls.first().nextEpisode)
        // Show is completely removed from Continue Watching because it reached the final episode
        assertNull(delegate.lastWatchedStore[888])
        assertTrue(delegate.removeLastWatchedCalls.contains(888))
    }

    @Test
    fun `completing episode with unknown next episode preserves Continue Watching entry`() {
        val ep = seriesEpisode(id = 55, season = 1, episode = 5, parentId = 999)
        val delegate = RecordingHistoryDelegate()
        delegate.setLastWatched(999, 55, 5, 1)
        val writer = ExternalPlaybackSessionStore.createHistoryWriter(delegate)

        val pendingPlayback = PendingExternalPlayback(
            sessionId = "session-4",
            packageName = PACKAGE,
            episode = ep,
            streamIdentifier = "hash4",
            initialPositionMs = 0L,
            expectedDurationMs = DURATION,
            startedAtWallClockMs = 1_000L,
            startedAtElapsedRealtimeMs = START,
            nextEpisode = null,
            nextEpisodeResolutionComplete = false, // Unknown next episode
        )

        val update = ExternalPlaybackUpdate(
            positionMs = 95_000L,
            durationMs = 100_000L,
            watchedContentMs = 95_000L,
            state = ExternalPlaybackState.STOPPED,
            reason = ExternalPlaybackSaveReason.STOP,
            immediate = true,
            completed = true,
            sessionEnded = true,
            clearPending = true,
        )

        writer.write(pendingPlayback, update)

        // Must NOT call removeLastWatched
        assertTrue("removeLastWatched must not be called when next episode is unknown", delegate.removeLastWatchedCalls.isEmpty())
        // View position of current episode is updated
        assertEquals(95_000L to 100_000L, delegate.viewPosStore[55])
        // Show remains in Continue Watching with current episode
        val lastWatched = delegate.lastWatchedStore[999]
        assertNotNull(lastWatched)
        assertEquals(55, lastWatched!!.episodeId)
        assertEquals(5, lastWatched.episode)
    }

    @Test
    fun `progress below completion threshold resumes current episode`() {
        val ep1 = seriesEpisode(id = 1, season = 1, episode = 1, parentId = 500)
        val ep2 = seriesEpisode(id = 2, season = 1, episode = 2, parentId = 500)
        val delegate = RecordingHistoryDelegate()
        val writer = ExternalPlaybackSessionStore.createHistoryWriter(delegate)

        val pendingPlayback = PendingExternalPlayback(
            sessionId = "session-5",
            packageName = PACKAGE,
            episode = ep1,
            streamIdentifier = "hash5",
            initialPositionMs = 0L,
            expectedDurationMs = DURATION,
            startedAtWallClockMs = 1_000L,
            startedAtElapsedRealtimeMs = START,
            nextEpisode = ep2,
            nextEpisodeResolutionComplete = true,
        )

        // Watched 35% (< 90% completion threshold)
        val update = ExternalPlaybackUpdate(
            positionMs = 35_000L,
            durationMs = 100_000L,
            watchedContentMs = 35_000L,
            state = ExternalPlaybackState.PAUSED,
            reason = ExternalPlaybackSaveReason.PAUSE,
            immediate = true,
            completed = false,
            sessionEnded = false,
            clearPending = false,
        )

        writer.write(pendingPlayback, update)

        val lastWatched = delegate.lastWatchedStore[500]
        assertNotNull(lastWatched)
        // Resume points to current episode (ep1), not next episode (ep2)
        assertEquals(ep1.id, lastWatched!!.episodeId)
        assertEquals(35_000L to 100_000L, delegate.viewPosStore[ep1.id])
    }

    @Test
    fun `existing next-episode resume position is not overwritten with zero`() {
        val ep1 = seriesEpisode(id = 1, season = 1, episode = 1, parentId = 500)
        val ep2 = seriesEpisode(id = 2, season = 1, episode = 2, parentId = 500)
        val delegate = RecordingHistoryDelegate()
        // ep2 already had 20 seconds watched in the past
        delegate.viewPosStore[ep2.id] = 20_000L to 100_000L

        val writer = ExternalPlaybackSessionStore.createHistoryWriter(delegate)
        val pendingPlayback = PendingExternalPlayback(
            sessionId = "session-6",
            packageName = PACKAGE,
            episode = ep1,
            streamIdentifier = "hash6",
            initialPositionMs = 0L,
            expectedDurationMs = DURATION,
            startedAtWallClockMs = 1_000L,
            startedAtElapsedRealtimeMs = START,
            nextEpisode = ep2,
            nextEpisodeResolutionComplete = true,
        )

        // Complete ep1
        val update = ExternalPlaybackUpdate(
            positionMs = 95_000L,
            durationMs = 100_000L,
            watchedContentMs = 95_000L,
            state = ExternalPlaybackState.STOPPED,
            reason = ExternalPlaybackSaveReason.STOP,
            immediate = true,
            completed = true,
            sessionEnded = true,
            clearPending = true,
        )

        writer.write(pendingPlayback, update)

        // ep1 position is 95s
        assertEquals(95_000L to 100_000L, delegate.viewPosStore[ep1.id])
        // ep2 position was NOT overwritten with 0
        assertEquals(20_000L to 100_000L, delegate.viewPosStore[ep2.id])
    }

    @Test
    fun `movies retain existing completion behavior`() {
        val movie = episode(id = 42, name = "My Movie", duration = 120_000L, tvType = TvType.Movie)
        val delegate = RecordingHistoryDelegate()
        delegate.setLastWatched(movie.parentId, movie.id, movie.episode, movie.season)
        val writer = ExternalPlaybackSessionStore.createHistoryWriter(delegate)

        val pendingPlayback = PendingExternalPlayback(
            sessionId = "session-movie",
            packageName = PACKAGE,
            episode = movie,
            streamIdentifier = "hash-movie",
            initialPositionMs = 0L,
            expectedDurationMs = 120_000L,
            startedAtWallClockMs = 1_000L,
            startedAtElapsedRealtimeMs = START,
            nextEpisode = null,
            nextEpisodeResolutionComplete = false, // Even if false, movie completes cleanly
        )

        // Movie completed
        val update = ExternalPlaybackUpdate(
            positionMs = 115_000L,
            durationMs = 120_000L,
            watchedContentMs = 115_000L,
            state = ExternalPlaybackState.STOPPED,
            reason = ExternalPlaybackSaveReason.STOP,
            immediate = true,
            completed = true,
            sessionEnded = true,
            clearPending = true,
        )

        writer.write(pendingPlayback, update)

        assertEquals(1, delegate.setViewPosAndResumeCalls.size)
        assertNull(delegate.setViewPosAndResumeCalls.first().nextEpisode)
        // Movie is removed from Continue Watching on completion
        assertNull(delegate.lastWatchedStore[movie.parentId])
        assertTrue(delegate.removeLastWatchedCalls.contains(movie.parentId))
    }

    @Test
    fun `old pending playback JSON without new fields deserializes properly`() {
        val oldJson = """
            {
              "sessionId": "legacy-session-123",
              "packageName": "com.genuine.leone",
              "episode": {
                "headerName": "Season 1",
                "name": "Legacy Episode",
                "poster": "https://example.com/poster.jpg",
                "episode": 1,
                "seasonIndex": 1,
                "season": 1,
                "data": "episode_data",
                "apiName": "TestApi",
                "id": 1234,
                "index": 0,
                "position": 0,
                "duration": 100000,
                "score": null,
                "description": "Legacy desc",
                "isFiller": false,
                "tvType": "TvSeries",
                "parentId": 100,
                "videoWatchState": "None"
              },
              "streamIdentifier": "stream_id_123",
              "initialPositionMs": 0,
              "existingPositionMs": 0,
              "expectedDurationMs": 100000,
              "startedAtWallClockMs": 1000,
              "startedAtElapsedRealtimeMs": 2000,
              "resumeSeekAttempted": false,
              "lastObservedPositionMs": 0,
              "lastObservedDurationMs": 100000,
              "hasObservedPlayback": false
            }
        """.trimIndent()

        val parsed = AppUtils.parseJson<PendingExternalPlayback>(oldJson)
        assertEquals("legacy-session-123", parsed.sessionId)
        assertEquals(1234, parsed.episode.id)
        assertNull(parsed.nextEpisode)
        assertFalse(parsed.nextEpisodeResolutionComplete)
        assertFalse(parsed.isRewatchOfCompletedEpisode)
    }

    // -------------------------------------------------------------------------
    // Rewatch Detection regression tests
    // -------------------------------------------------------------------------

    @Test
    fun `episode with VideoWatchState Watched is detected as a rewatch`() {
        val ep = seriesEpisode(id = 1, season = 1, episode = 1)
            .copy(videoWatchState = VideoWatchState.Watched, position = 0L)
        assertTrue(ExternalPlaybackSessionStore.isCompletedEpisode(ep))
    }

    @Test
    fun `episode with saved position at or above completion threshold is detected as a rewatch`() {
        // 95% of 100_000 is 95_000
        val epAt95 = seriesEpisode(id = 2, season = 1, episode = 2)
            .copy(position = 95_000L, duration = 100_000L, videoWatchState = VideoWatchState.None)
        assertTrue(ExternalPlaybackSessionStore.isCompletedEpisode(epAt95))

        val epAt98 = seriesEpisode(id = 3, season = 1, episode = 3)
            .copy(position = 98_000L, duration = 100_000L, videoWatchState = VideoWatchState.None)
        assertTrue(ExternalPlaybackSessionStore.isCompletedEpisode(epAt98))
    }

    @Test
    fun `movie with previously completed progress is detected as a rewatch`() {
        val movie = episode(id = 10, duration = 120_000L, tvType = TvType.Movie)
            .copy(position = 119_000L, videoWatchState = VideoWatchState.None)
        assertTrue(ExternalPlaybackSessionStore.isCompletedEpisode(movie))
    }

    @Test
    fun `unfinished episode is not detected as a rewatch`() {
        val unfinished = seriesEpisode(id = 4, season = 1, episode = 4)
            .copy(position = 50_000L, duration = 100_000L, videoWatchState = VideoWatchState.None)
        assertFalse(ExternalPlaybackSessionStore.isCompletedEpisode(unfinished))
    }

    @Test
    fun `new episode with zero saved position is not detected as a rewatch`() {
        val newEp = seriesEpisode(id = 5, season = 1, episode = 5)
            .copy(position = 0L, duration = 100_000L, videoWatchState = VideoWatchState.None)
        assertFalse(ExternalPlaybackSessionStore.isCompletedEpisode(newEp))
    }

    // -------------------------------------------------------------------------
    // Progress Engine Rewatch regression tests
    // -------------------------------------------------------------------------

    @Test
    fun `normal unfinished episode preserves saved resume position before seek confirmation`() {
        val engine = ExternalPlaybackProgressEngine(
            pending(initialPositionMs = 60_000L, isRewatchOfCompletedEpisode = false)
        )
        bind(engine, positionMs = 0L)

        // Player starts at zero before seeking to resume position: must not overwrite saved 60s
        val update = engine.onSnapshot(
            snapshot(
                state = ExternalPlaybackState.PLAYING,
                positionMs = 5_000L,
                observedAtElapsedMs = at(6),
            )
        )
        assertNull("Normal unfinished episode must guard against 0 position overwrite", update)
        assertEquals(-1L, engine.lastPersistedPositionMs)
    }

    @Test
    fun `intentional rewatch bypasses old completed-position guard`() {
        val engine = ExternalPlaybackProgressEngine(
            pending(
                initialPositionMs = 0L,
                existingPositionMs = 98_000L,
                isRewatchOfCompletedEpisode = true,
            )
        )
        bind(engine, positionMs = 0L)

        // Player starts at zero for a rewatch and advances to 5s.
        // It must NOT be blocked by the previous 98s completed position.
        val update = engine.onSnapshot(
            snapshot(
                state = ExternalPlaybackState.PLAYING,
                positionMs = 5_000L,
                observedAtElapsedMs = at(6),
            )
        )
        assertNotNull("Rewatch must bypass resume guard and emit update", update)
        assertEquals(5_000L, update!!.positionMs)
    }

    @Test
    fun `rewatch starting at zero can save its new progressing position without waiting 60 seconds`() {
        val engine = ExternalPlaybackProgressEngine(
            pending(
                initialPositionMs = 0L,
                existingPositionMs = 100_000L,
                isRewatchOfCompletedEpisode = true,
            )
        )
        bind(engine, positionMs = 0L)

        // Advance 10 seconds: saves without needing 60 seconds of watchedContentMs
        val update = engine.onSnapshot(
            snapshot(
                state = ExternalPlaybackState.PLAYING,
                positionMs = 10_000L,
                observedAtElapsedMs = at(11),
            )
        )
        assertNotNull(update)
        assertEquals(10_000L, update!!.positionMs)
        assertTrue(update.watchedContentMs < 60_000L)
    }

    @Test
    fun `rewatch that seeks to a different timestamp can subsequently save the new position`() {
        val engine = ExternalPlaybackProgressEngine(
            pending(
                initialPositionMs = 0L,
                existingPositionMs = 100_000L,
                isRewatchOfCompletedEpisode = true,
            )
        )
        bind(engine, positionMs = 0L)

        // User seeks to 35s
        val update = engine.onSnapshot(
            snapshot(
                state = ExternalPlaybackState.PLAYING,
                positionMs = 35_000L,
                observedAtElapsedMs = at(6),
            )
        )
        assertNotNull(update)
        assertEquals(35_000L, update!!.positionMs)
    }

    @Test
    fun `stale sessions and out-of-order MediaSession observations remain rejected even for rewatches`() {
        val engine = ExternalPlaybackProgressEngine(
            pending(
                initialPositionMs = 0L,
                existingPositionMs = 100_000L,
                isRewatchOfCompletedEpisode = true,
            )
        )
        bind(engine, positionMs = 0L)

        // Observation from wrong session token must be ignored
        val updateStale = engine.onSnapshot(
            snapshot(
                state = ExternalPlaybackState.PLAYING,
                positionMs = 20_000L,
                observedAtElapsedMs = at(6),
                sessionToken = "token-other",
            )
        )
        assertNull(updateStale)

        // Out-of-order observation from the past must be rejected
        val updatePast = engine.onSnapshot(
            snapshot(
                state = ExternalPlaybackState.PLAYING,
                positionMs = 15_000L,
                observedAtElapsedMs = at(0),
                sessionToken = "token-1",
            )
        )
        assertNull(updatePast)
    }

    @Test
    fun `non-rewatch does not accidentally inherit rewatch exception`() {
        val engine = ExternalPlaybackProgressEngine(
            pending(
                initialPositionMs = 0L,
                existingPositionMs = 96_000L,
                isRewatchOfCompletedEpisode = false,
            )
        )
        bind(engine, positionMs = 0L)

        // Progress below threshold is guarded
        val update = engine.onSnapshot(
            snapshot(
                state = ExternalPlaybackState.PLAYING,
                positionMs = 10_000L,
                observedAtElapsedMs = at(11),
            )
        )
        assertNull("Non-rewatch must not bypass the resume guard", update)
    }

    // -------------------------------------------------------------------------
    // History writer and Home pointer regression tests
    // -------------------------------------------------------------------------

    @Test
    fun `intentional rewatch of series episode updates position and clears watch state without touching last watched`() {
        val ep1 = seriesEpisode(id = 10, season = 1, episode = 1, parentId = 500)
        val delegate = RecordingHistoryDelegate()
        // Pre-populate last watched pointing to episode 5 (user was on episode 5)
        delegate.setLastWatched(500, 50, 5, 1)
        val initialLastWatched = delegate.lastWatchedStore[500]

        val writer = ExternalPlaybackSessionStore.createHistoryWriter(delegate)
        val pendingPlayback = PendingExternalPlayback(
            sessionId = "rewatch-1",
            packageName = PACKAGE,
            episode = ep1,
            streamIdentifier = "hash-rewatch-1",
            initialPositionMs = 0L,
            existingPositionMs = 100_000L,
            expectedDurationMs = 100_000L,
            startedAtWallClockMs = 1_000L,
            startedAtElapsedRealtimeMs = START,
            nextEpisode = seriesEpisode(id = 20, season = 1, episode = 2, parentId = 500),
            nextEpisodeResolutionComplete = true,
            isRewatchOfCompletedEpisode = true,
        )

        // Rewatching at 40%
        val update = ExternalPlaybackUpdate(
            positionMs = 40_000L,
            durationMs = 100_000L,
            watchedContentMs = 40_000L,
            state = ExternalPlaybackState.PAUSED,
            reason = ExternalPlaybackSaveReason.PAUSE,
            immediate = true,
            completed = false,
            sessionEnded = false,
            clearPending = false,
        )

        writer.write(pendingPlayback, update)

        // 1. Position is updated
        assertEquals(40_000L to 100_000L, delegate.viewPosStore[ep1.id])
        // 2. Old Watched marker is cleared
        assertTrue(delegate.clearWatchStateCalls.contains(ep1.id))
        assertEquals(VideoWatchState.None, delegate.watchStateStore[ep1.id])
        // 3. Last watched is NEVER modified
        assertTrue(delegate.setViewPosAndResumeCalls.isEmpty())
        assertEquals(1, delegate.setLastWatchedCalls.size) // only initial setup
        assertTrue(delegate.removeLastWatchedCalls.isEmpty())
        assertEquals(initialLastWatched, delegate.lastWatchedStore[500])
        assertEquals(50, delegate.lastWatchedStore[500]?.episodeId)
    }

    @Test
    fun `intentional rewatch that reaches completion threshold still does not modify Home Continue Watching`() {
        val ep1 = seriesEpisode(id = 10, season = 1, episode = 1, parentId = 500)
        val delegate = RecordingHistoryDelegate()
        delegate.setLastWatched(500, 50, 5, 1)

        val writer = ExternalPlaybackSessionStore.createHistoryWriter(delegate)
        val pendingPlayback = PendingExternalPlayback(
            sessionId = "rewatch-2",
            packageName = PACKAGE,
            episode = ep1,
            streamIdentifier = "hash-rewatch-2",
            initialPositionMs = 0L,
            existingPositionMs = 100_000L,
            expectedDurationMs = 100_000L,
            startedAtWallClockMs = 1_000L,
            startedAtElapsedRealtimeMs = START,
            nextEpisode = seriesEpisode(id = 20, season = 1, episode = 2, parentId = 500),
            nextEpisodeResolutionComplete = true,
            isRewatchOfCompletedEpisode = true,
        )

        // Rewatch fully reaches 98%
        val update = ExternalPlaybackUpdate(
            positionMs = 98_000L,
            durationMs = 100_000L,
            watchedContentMs = 98_000L,
            state = ExternalPlaybackState.STOPPED,
            reason = ExternalPlaybackSaveReason.STOP,
            immediate = true,
            completed = true,
            sessionEnded = true,
            clearPending = true,
        )

        writer.write(pendingPlayback, update)

        // Position is updated to 98%
        assertEquals(98_000L to 100_000L, delegate.viewPosStore[ep1.id])
        // Home Continue Watching MUST NOT advance to ep 2 or remove ep 5
        assertTrue(delegate.setViewPosAndResumeCalls.isEmpty())
        assertTrue(delegate.removeLastWatchedCalls.isEmpty())
        assertEquals(50, delegate.lastWatchedStore[500]?.episodeId)
    }

    @Test
    fun `intentional rewatch of movie updates position without removing last watched`() {
        val movie = episode(id = 77, name = "My Favorite Movie", duration = 120_000L, tvType = TvType.Movie)
        val delegate = RecordingHistoryDelegate()
        delegate.setLastWatched(movie.parentId, movie.id, movie.episode, movie.season)

        val writer = ExternalPlaybackSessionStore.createHistoryWriter(delegate)
        val pendingPlayback = PendingExternalPlayback(
            sessionId = "rewatch-movie",
            packageName = PACKAGE,
            episode = movie,
            streamIdentifier = "hash-rewatch-movie",
            initialPositionMs = 0L,
            existingPositionMs = 120_000L,
            expectedDurationMs = 120_000L,
            startedAtWallClockMs = 1_000L,
            startedAtElapsedRealtimeMs = START,
            nextEpisode = null,
            nextEpisodeResolutionComplete = true,
            isRewatchOfCompletedEpisode = true,
        )

        // Movie rewatched to 60s
        val update = ExternalPlaybackUpdate(
            positionMs = 60_000L,
            durationMs = 120_000L,
            watchedContentMs = 60_000L,
            state = ExternalPlaybackState.PAUSED,
            reason = ExternalPlaybackSaveReason.PAUSE,
            immediate = true,
            completed = false,
            sessionEnded = false,
            clearPending = false,
        )

        writer.write(pendingPlayback, update)

        assertEquals(60_000L to 120_000L, delegate.viewPosStore[movie.id])
        assertTrue(delegate.clearWatchStateCalls.contains(movie.id))
        assertTrue(delegate.removeLastWatchedCalls.isEmpty())
        assertNotNull(delegate.lastWatchedStore[movie.parentId])
    }

    @Test
    fun `rewatch with resolved next episode, final episode, or unknown next episode all use rewatch branch`() {
        val delegate = RecordingHistoryDelegate()
        val writer = ExternalPlaybackSessionStore.createHistoryWriter(delegate)

        // 1. With resolved next episode
        val pendingWithNext = PendingExternalPlayback(
            sessionId = "r-1",
            packageName = PACKAGE,
            episode = seriesEpisode(id = 1, season = 1, episode = 1, parentId = 100),
            streamIdentifier = "h-1",
            initialPositionMs = 0L,
            existingPositionMs = 100_000L,
            expectedDurationMs = 100_000L,
            startedAtWallClockMs = 1_000L,
            startedAtElapsedRealtimeMs = START,
            nextEpisode = seriesEpisode(id = 2, season = 1, episode = 2, parentId = 100),
            nextEpisodeResolutionComplete = true,
            isRewatchOfCompletedEpisode = true,
        )

        // 2. Confirmed final episode
        val pendingFinal = PendingExternalPlayback(
            sessionId = "r-2",
            packageName = PACKAGE,
            episode = seriesEpisode(id = 9, season = 1, episode = 9, parentId = 100),
            streamIdentifier = "h-2",
            initialPositionMs = 0L,
            existingPositionMs = 100_000L,
            expectedDurationMs = 100_000L,
            startedAtWallClockMs = 1_000L,
            startedAtElapsedRealtimeMs = START,
            nextEpisode = null,
            nextEpisodeResolutionComplete = true,
            isRewatchOfCompletedEpisode = true,
        )

        // 3. Unknown next episode
        val pendingUnknown = PendingExternalPlayback(
            sessionId = "r-3",
            packageName = PACKAGE,
            episode = seriesEpisode(id = 5, season = 1, episode = 5, parentId = 100),
            streamIdentifier = "h-3",
            initialPositionMs = 0L,
            existingPositionMs = 100_000L,
            expectedDurationMs = 100_000L,
            startedAtWallClockMs = 1_000L,
            startedAtElapsedRealtimeMs = START,
            nextEpisode = null,
            nextEpisodeResolutionComplete = false,
            isRewatchOfCompletedEpisode = true,
        )

        val completedUpdate = ExternalPlaybackUpdate(
            positionMs = 95_000L,
            durationMs = 100_000L,
            watchedContentMs = 95_000L,
            state = ExternalPlaybackState.STOPPED,
            reason = ExternalPlaybackSaveReason.STOP,
            immediate = true,
            completed = true,
            sessionEnded = true,
            clearPending = true,
        )

        writer.write(pendingWithNext, completedUpdate)
        writer.write(pendingFinal, completedUpdate)
        writer.write(pendingUnknown, completedUpdate)

        // All 3 used the rewatch branch: 3 setViewPos calls, 3 clearEpisodeWatchState calls
        assertEquals(3, delegate.setViewPosCalls.size)
        assertEquals(3, delegate.clearWatchStateCalls.size)
        // Never called setViewPosAndResume, setLastWatched, or removeLastWatched
        assertTrue(delegate.setViewPosAndResumeCalls.isEmpty())
        assertTrue(delegate.setLastWatchedCalls.isEmpty())
        assertTrue(delegate.removeLastWatchedCalls.isEmpty())
    }
}
