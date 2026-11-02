package com.lagradost.cloudstream3.actions

/**
 * Testable orchestration layer for external playback tracking.
 *
 * The Android tracker supplies real MediaSession, SharedPreferences and
 * DataStoreHelper adapters. Tests supply fakes with the same contracts.
 */
interface ExternalPlaybackClock {
    fun nowElapsedMs(): Long
}

data class ExternalPlaybackSession(
    val packageName: String,
    val sessionToken: String,
    val snapshot: ExternalPlaybackSnapshot,
    val seekTo: ((Long) -> Unit)? = null,
)

interface ExternalPlaybackSessionSource {
    /** May throw SecurityException when notification access is missing. */
    fun activeSessions(): List<ExternalPlaybackSession>
}

interface ExternalPlaybackPendingStore {
    fun get(): PendingExternalPlayback?
    fun clear(reason: String)
    fun markResumeSeekAttempted(pending: PendingExternalPlayback): PendingExternalPlayback?
    fun updateObserved(
        pending: PendingExternalPlayback,
        positionMs: Long,
        durationMs: Long,
    ): PendingExternalPlayback?
}

interface ExternalPlaybackProgressSink {
    fun save(pending: PendingExternalPlayback, update: ExternalPlaybackUpdate): Boolean
}

enum class ExternalPlaybackScanOutcome {
    NO_PENDING,
    DISABLED,
    EXPIRED,
    PERMISSION_MISSING,
    SOURCE_ERROR,
    NO_SESSION,
    AMBIGUOUS,
    BOUND,
    TRACKING,
    SAVED,
    MEDIA_CHANGED,
}

sealed interface ExternalPlaybackSessionSelection {
    data class Bound(val session: ExternalPlaybackSession) : ExternalPlaybackSessionSelection
    data class Select(
        val session: ExternalPlaybackSession,
        val replacingBoundSession: Boolean,
    ) : ExternalPlaybackSessionSelection

    data object Ambiguous : ExternalPlaybackSessionSelection
    data object None : ExternalPlaybackSessionSelection
}

/**
 * Selects a session using multi-dimensional evidence ranking.
 *
 * The bound token is preferred only when it is genuinely usable: not terminal,
 * not stale, and still producing updates. A replacement candidate must not be
 * clearly older than the launch. Ambiguous candidates are left alone until one
 * becomes distinguishable.
 */
object ExternalPlaybackSessionSelector {
    private const val FRESHNESS_TOLERANCE_MS = 1_000L

    /** Maximum time since last position update before a session is considered stale. */
    private const val STALE_UPDATE_THRESHOLD_MS = 60_000L

    fun select(
        sessions: List<ExternalPlaybackSession>,
        pending: PendingExternalPlayback,
        boundToken: String?,
        nowElapsedMs: Long,
    ): ExternalPlaybackSessionSelection {
        if (boundToken != null) {
            val boundSession = sessions.firstOrNull { it.sessionToken == boundToken }
            if (boundSession != null) {
                // Check whether the bound session is still genuinely usable.
                if (isBoundSessionUsable(boundSession.snapshot, nowElapsedMs)) {
                    return ExternalPlaybackSessionSelection.Bound(boundSession)
                }
                // Bound session exists but is terminal or stale. Consider replacements.
                val replacements = sessions.filter {
                    it.sessionToken != boundToken && !isClearlyStale(it.snapshot, pending)
                }
                if (replacements.isEmpty()) {
                    // No replacement available — keep the bound session (it still exists).
                    return ExternalPlaybackSessionSelection.Bound(boundSession)
                }
                // Rank replacements; pick one only if it has stronger evidence.
                val ranked = rankCandidates(replacements, pending, nowElapsedMs)
                if (ranked == null) {
                    // Replacements are ambiguous or absent — keep the stale bound.
                    return ExternalPlaybackSessionSelection.Bound(boundSession)
                }
                val replacement = ranked.first
                // A replacement needs active playback evidence to unseat a bound session.
                if (isActivelyPlaying(replacement.snapshot)) {
                    return ExternalPlaybackSessionSelection.Select(
                        session = replacement,
                        replacingBoundSession = true,
                    )
                }
                // Replacement is not clearly better; keep the existing bound session.
                return ExternalPlaybackSessionSelection.Bound(boundSession)
            }
            // Bound token is gone from the active list; fall through to candidate selection.
        }

        val candidates = sessions.filterNot { isClearlyStale(it.snapshot, pending) }
        if (candidates.isEmpty()) return ExternalPlaybackSessionSelection.None

        val ranked = rankCandidates(candidates, pending, nowElapsedMs)
            ?: return ExternalPlaybackSessionSelection.Ambiguous

        return ExternalPlaybackSessionSelection.Select(
            session = ranked.first,
            replacingBoundSession = boundToken != null,
        )
    }

    /**
     * A bound session is usable when it is not in a terminal state and its
     * position updates are reasonably fresh.
     */
    fun isBoundSessionUsable(
        snapshot: ExternalPlaybackSnapshot,
        nowElapsedMs: Long,
    ): Boolean {
        // Terminal states: the session has ended.
        if (snapshot.state == ExternalPlaybackState.STOPPED ||
            snapshot.state == ExternalPlaybackState.ERROR ||
            snapshot.state == ExternalPlaybackState.NONE
        ) {
            return false
        }
        // If the session has a position update timestamp, check recency.
        if (snapshot.lastPositionUpdateElapsedMs > 0L) {
            val staleness = nowElapsedMs - snapshot.lastPositionUpdateElapsedMs
            if (staleness > STALE_UPDATE_THRESHOLD_MS) {
                return false
            }
        }
        // PLAYING, PAUSED, BUFFERING, IDLE with fresh updates are usable.
        return true
    }

    private fun isActivelyPlaying(snapshot: ExternalPlaybackSnapshot): Boolean {
        return snapshot.state == ExternalPlaybackState.PLAYING ||
                snapshot.state == ExternalPlaybackState.BUFFERING
    }

    /**
     * Returns the best candidate if it is unambiguously the strongest, or null
     * when the top two candidates are genuinely indistinguishable.
     */
    private fun rankCandidates(
        candidates: List<ExternalPlaybackSession>,
        pending: PendingExternalPlayback,
        nowElapsedMs: Long,
    ): Pair<ExternalPlaybackSession, SessionEvidence>? {
        if (candidates.isEmpty()) return null
        val evidenceList = candidates.map { session ->
            session to evaluateEvidence(session, pending, nowElapsedMs)
        }.sortedWith(compareByDescending<Pair<ExternalPlaybackSession, SessionEvidence>> {
            it.second
        })

        val best = evidenceList.first()
        val second = evidenceList.getOrNull(1)
        if (second != null && second.second.compareTo(best.second) == 0) {
            return null // genuinely ambiguous
        }
        return best
    }

    private fun isClearlyStale(
        snapshot: ExternalPlaybackSnapshot,
        pending: PendingExternalPlayback,
    ): Boolean {
        return snapshot.lastPositionUpdateElapsedMs > 0L &&
                snapshot.lastPositionUpdateElapsedMs <
                pending.startedAtElapsedRealtimeMs - FRESHNESS_TOLERANCE_MS
    }

    /**
     * Multi-dimensional evidence for session ranking. Implements [Comparable]
     * so sessions can be ranked deterministically without collapsing different
     * evidence patterns to the same integer score.
     *
     * Priority order:
     * 1. Playback state rank (PLAYING > BUFFERING > PAUSED > IDLE > terminal)
     * 2. Whether position updates are fresh (recent timestamp)
     * 3. Actual recency of position update timestamp
     * 4. Whether duration is available
     * 5. Whether position is non-zero
     * 6. Whether mediaId is present
     * 7. Title match score
     */
    data class SessionEvidence(
        /** 4=PLAYING, 3=BUFFERING, 2=PAUSED, 1=IDLE, 0=terminal/other */
        val stateRank: Int,
        /** Whether lastPositionUpdateElapsedMs is post-launch */
        val freshUpdate: Boolean,
        /** Actual elapsed time of last position update (higher is more recent) */
        val lastUpdateMs: Long,
        /** Whether duration > 0 */
        val hasDuration: Boolean,
        /** Whether position > 0 */
        val hasPosition: Boolean,
        /** Whether mediaId is present */
        val hasMediaId: Boolean,
        /** Title match score (-1 to 3) */
        val titleScore: Int,
    ) : Comparable<SessionEvidence> {
        override fun compareTo(other: SessionEvidence): Int {
            // 1. State rank
            stateRank.compareTo(other.stateRank).let { if (it != 0) return it }
            // 2. Fresh update
            freshUpdate.compareTo(other.freshUpdate).let { if (it != 0) return it }
            // 3. Last update recency
            lastUpdateMs.compareTo(other.lastUpdateMs).let { if (it != 0) return it }
            // 4. Has duration
            hasDuration.compareTo(other.hasDuration).let { if (it != 0) return it }
            // 5. Has position
            hasPosition.compareTo(other.hasPosition).let { if (it != 0) return it }
            // 6. Has media ID
            hasMediaId.compareTo(other.hasMediaId).let { if (it != 0) return it }
            // 7. Title score
            return titleScore.compareTo(other.titleScore)
        }
    }

    private fun evaluateEvidence(
        session: ExternalPlaybackSession,
        pending: PendingExternalPlayback,
        nowElapsedMs: Long,
    ): SessionEvidence {
        val snapshot = session.snapshot

        val stateRank = when (snapshot.state) {
            ExternalPlaybackState.PLAYING -> 4
            ExternalPlaybackState.BUFFERING -> 3
            ExternalPlaybackState.PAUSED -> 2
            ExternalPlaybackState.IDLE -> 1
            else -> 0
        }

        val freshUpdate = snapshot.lastPositionUpdateElapsedMs > 0L &&
                snapshot.lastPositionUpdateElapsedMs >=
                pending.startedAtElapsedRealtimeMs - FRESHNESS_TOLERANCE_MS

        // Use actual timestamp for recency ranking; 0 means unknown.
        val lastUpdateMs = if (snapshot.lastPositionUpdateElapsedMs > 0L) {
            snapshot.lastPositionUpdateElapsedMs
        } else {
            0L
        }

        val expectedTitle = pending.episode.name ?: pending.episode.headerName
        val titleScore = ExternalPlaybackTitleMatcher.matchScore(expectedTitle, snapshot.title)

        return SessionEvidence(
            stateRank = stateRank,
            freshUpdate = freshUpdate,
            lastUpdateMs = lastUpdateMs,
            hasDuration = snapshot.durationMs > 0L,
            hasPosition = snapshot.positionMs > 0L,
            hasMediaId = !snapshot.mediaId.isNullOrBlank(),
            titleScore = titleScore,
        )
    }
}

class ExternalPlaybackTrackerCore(
    private val sessionSource: ExternalPlaybackSessionSource,
    private val pendingStore: ExternalPlaybackPendingStore,
    private val progressSink: ExternalPlaybackProgressSink,
    private val clock: ExternalPlaybackClock,
    private val isTrackingEnabled: () -> Boolean,
    private val diagnostics: (String) -> Unit = {},
) {
    private var engine: ExternalPlaybackProgressEngine? = null
    private var resumeController: ExternalPlaybackResumeController? = null
    private var pendingSessionId: String? = null

    var permissionMissing: Boolean = false
        private set

    var lastOutcome: ExternalPlaybackScanOutcome = ExternalPlaybackScanOutcome.NO_PENDING
        private set

    var lastDiagnostic: String = ""
        private set

    val boundSessionToken: String?
        get() = engine?.boundSessionToken

    fun scan(): ExternalPlaybackScanOutcome {
        if (!isTrackingEnabled()) {
            val pending = pendingStore.get()
            if (pending != null) {
                pendingStore.clear("tracking-disabled")
            }
            reset("tracking-disabled")
            return finish(ExternalPlaybackScanOutcome.DISABLED, "tracking disabled")
        }

        val pending = pendingStore.get()
        if (pending == null) {
            reset("no-pending")
            return finish(ExternalPlaybackScanOutcome.NO_PENDING, "no pending playback")
        }

        val tracker = engineFor(pending)
        val now = clock.nowElapsedMs()

        // The candidate-presence flag is set below after scanning sessions.
        // For the tick check, preserve the previous state until we know.
        if (tracker.onTick(now) == ExternalPlaybackExpired) {
            pendingStore.clear("expired")
            reset("expired")
            diagnostics("EXPIRED: pending expired: ${tracker.lastDecision}")
            return finish(
                ExternalPlaybackScanOutcome.EXPIRED,
                "pending expired: ${tracker.lastDecision}"
            )
        }

        val sessions = try {
            sessionSource.activeSessions()
        } catch (t: SecurityException) {
            permissionMissing = true
            return finish(
                ExternalPlaybackScanOutcome.PERMISSION_MISSING,
                "notification access missing: ${t.message ?: "SecurityException"}"
            )
        } catch (t: Throwable) {
            return finish(
                ExternalPlaybackScanOutcome.SOURCE_ERROR,
                "MediaSession discovery failed: ${t.message ?: t.javaClass.simpleName}"
            )
        }
        permissionMissing = false

        val matching = sessions.filter { it.packageName == pending.packageName }
        // Update candidate-presence flag for the engine's tick-based expiration.
        tracker.hasCandidates = matching.isNotEmpty()
        diagnostics(
            "scan pending=${shortId(pending.sessionId)} sessions=${sessions.size} " +
                    "matching=${matching.size} " +
                    "packages=${matching.map { it.packageName }.distinct().joinToString(",")} " +
                    "bound=${shortToken(tracker.boundSessionToken)} now=$now"
        )
        if (matching.isEmpty()) {
            return onNoSession(tracker, pending, now, "no matching package session")
        }

        // Once a media identity is known, do not let a contradictory session
        // from the same package compete for tracking.
        val boundToken = tracker.boundSessionToken
        val boundSession = boundToken?.let { tok -> matching.firstOrNull { it.sessionToken == tok } }
        if (boundSession != null && !ExternalPlaybackSessionSelector.isBoundSessionUsable(boundSession.snapshot, now)) {
            diagnostics(
                "STALE_BOUND_SESSION: token=${shortToken(boundToken)} " +
                        "state=${boundSession.snapshot.state} " +
                        "lastUpdate=${boundSession.snapshot.lastPositionUpdateElapsedMs} " +
                        "pos=${boundSession.snapshot.positionMs}ms"
            )
        }

        val candidates = if (boundToken == null) {
            matching
        } else {
            matching.filter {
                it.sessionToken == boundToken ||
                        replacementMatchesIdentity(tracker, it.snapshot)
            }
        }
        if (candidates.isEmpty()) {
            return onNoSession(
                tracker,
                pending,
                now,
                "no session consistent with the bound media identity",
            )
        }

        return when (val selection = ExternalPlaybackSessionSelector.select(
            sessions = candidates,
            pending = pending,
            boundToken = boundToken,
            nowElapsedMs = now,
        )) {
            is ExternalPlaybackSessionSelection.Bound -> {
                process(selection.session, tracker, pending, now)
            }

            is ExternalPlaybackSessionSelection.Select -> {
                diagnostics(
                    "candidate selected token=${shortToken(selection.session.sessionToken)} " +
                            "state=${selection.session.snapshot.state} " +
                            "replacingBound=${selection.replacingBoundSession}"
                )
                if (selection.replacingBoundSession) {
                    if (!replacementMatchesIdentity(tracker, selection.session.snapshot)) {
                        diagnostics(
                            "reject rebind old=${shortToken(tracker.boundSessionToken)} " +
                                    "new=${shortToken(selection.session.sessionToken)}: " +
                                    "media identity mismatch"
                        )
                        return onNoSession(
                            tracker,
                            pending,
                            now,
                            "replacement media identity mismatch",
                        )
                    }
                    val old = tracker.boundSessionToken
                    tracker.rebindSession(selection.session.snapshot, now)
                    diagnostics(
                        "SESSION_REBOUND: rebind old=${shortToken(old)} " +
                                "new=${shortToken(selection.session.sessionToken)} " +
                                "preservedPos=${tracker.lastKnownPositionMs} " +
                                "watched=${tracker.watchedContentMs}"
                    )
                }
                process(selection.session, tracker, pending, now)
            }

            ExternalPlaybackSessionSelection.Ambiguous -> {
                diagnostics(
                    "AMBIGUOUS: ${candidates.size} candidates with equal evidence, " +
                            "keeping pending episode=${pending.episode.id}"
                )
                onNoSession(
                    tracker,
                    pending,
                    now,
                    "ambiguous replacement candidates",
                    ExternalPlaybackScanOutcome.AMBIGUOUS,
                )
            }

            ExternalPlaybackSessionSelection.None -> {
                onNoSession(tracker, pending, now, "no fresh replacement candidate")
            }
        }
    }

    /**
     * Finalizes only the launch that produced this result. A stale result must
     * never clear a newer pending playback.
     */
    fun onActivityResult(
        expectedSessionId: String?,
        positionMs: Long?,
        durationMs: Long?,
    ): Boolean {
        val pending = pendingStore.get() ?: return false
        if (expectedSessionId == null || expectedSessionId != pending.sessionId) {
            diagnostics(
                "stale activity result expected=${shortId(expectedSessionId)} " +
                        "current=${shortId(pending.sessionId)}; keeping tracking"
            )
            return false
        }

        val tracker = engineFor(pending)
        val now = clock.nowElapsedMs()
        val update = tracker.onActivityResult(positionMs, durationMs, now)
        if (update != null) {
            val saved = progressSink.save(pending, update)
            diagnostics(
                "activity result save episode=${pending.episode.id} saved=$saved " +
                        "pos=${update.positionMs}ms dur=${update.durationMs}ms " +
                        "reason=${update.reason}"
            )
            if (!saved) {
                // Do not delete the only record of a position that failed to
                // reach the existing history store.
                tracker.onPersistFailed()
                pendingStore.updateObserved(
                    pending,
                    tracker.lastKnownPositionMs,
                    tracker.lastKnownDurationMs,
                )
                lastOutcome = ExternalPlaybackScanOutcome.TRACKING
                return false
            }
        } else {
            diagnostics(
                "activity result without progress episode=${pending.episode.id} " +
                        "position=$positionMs duration=$durationMs reason=${tracker.lastDecision}"
            )
        }
        pendingStore.updateObserved(
            pending,
            tracker.lastKnownPositionMs,
            tracker.lastKnownDurationMs,
        )
        pendingStore.clear("activity-result")
        reset("activity-result")
        lastOutcome = ExternalPlaybackScanOutcome.SAVED
        return true
    }

    fun reset(reason: String) {
        releaseEngineState()
        pendingSessionId = null
        diagnostics("tracker reset: $reason")
    }

    private fun process(
        session: ExternalPlaybackSession,
        tracker: ExternalPlaybackProgressEngine,
        pending: PendingExternalPlayback,
        now: Long,
    ): ExternalPlaybackScanOutcome {
        val snapshot = session.snapshot
        diagnostics(
            "TRACKING: session token=${shortToken(session.sessionToken)} state=${snapshot.state} " +
                    "pos=${snapshot.positionMs}ms dur=${snapshot.durationMs}ms " +
                    "lastUpdate=${snapshot.lastPositionUpdateElapsedMs} " +
                    "actions=${snapshot.actions} mediaId=${snapshot.mediaId != null} " +
                    "title=${sanitizedTitle(snapshot.title)}"
        )
        val update = tracker.onSnapshot(snapshot, now)
        if (update != null) {
            val saved = progressSink.save(pending, update)
            // Retain the latest observation even when the durable write fails,
            // so a later Activity Result or process restart can retry it.
            pendingStore.updateObserved(pending, update.positionMs, update.durationMs)
            if (saved) {
                diagnostics(
                    "SAVED: persisted episode=${pending.episode.id} pos=${update.positionMs}ms " +
                            "dur=${update.durationMs}ms reason=${update.reason} " +
                            "watched=${update.watchedContentMs}ms " +
                            "effective=${tracker.lastEffectivePositionMs}ms " +
                            "completed=${update.completed} " +
                            "titleMismatch=${tracker.lastTitleMismatchDescription ?: "none"}"
                )
            } else {
                diagnostics(
                    "persist failed episode=${pending.episode.id} reason=${update.reason} " +
                            "pos=${update.positionMs}ms dur=${update.durationMs}ms"
                )
                tracker.onPersistFailed()
            }
            if (update.clearPending) {
                if (!saved) {
                    // Keep the marker so the last confirmed progress can be
                    // retried. Never attribute the replacement media item's
                    // position to the launched episode.
                    diagnostics(
                        "media change save failed episode=${pending.episode.id}; " +
                                "retaining pending for retry"
                    )
                    return finish(
                        ExternalPlaybackScanOutcome.TRACKING,
                        "media change progress not persisted",
                    )
                }
                pendingStore.clear("media-changed")
                reset("media-changed")
                return finish(ExternalPlaybackScanOutcome.MEDIA_CHANGED, "genuine media change")
            }
        } else {
            if (tracker.lastKnownDurationMs <= 0L) {
                pendingStore.updateObserved(
                    pending,
                    tracker.lastKnownPositionMs,
                    0L,
                )
            }
            diagnostics(
                "no-persist episode=${pending.episode.id} reason=${tracker.lastDecision} " +
                        "effective=${tracker.lastEffectivePositionMs}ms " +
                        "duration=${tracker.lastKnownDurationMs}ms " +
                        "titleMismatch=${tracker.lastTitleMismatchDescription ?: "none"}"
            )
        }

        evaluateResumeSeek(session, tracker, pending, snapshot, now)
        return finish(
            if (update != null) ExternalPlaybackScanOutcome.SAVED
            else ExternalPlaybackScanOutcome.TRACKING,
            "session processed: ${tracker.lastDecision}"
        )
    }

    /**
     * A replacement session may take over only when its strong identifiers do
     * not contradict the media already bound to this launch. Weak evidence
     * (title, position, duration) is deliberately not required to match.
     */
    private fun replacementMatchesIdentity(
        tracker: ExternalPlaybackProgressEngine,
        snapshot: ExternalPlaybackSnapshot,
    ): Boolean {
        val newMediaId = snapshot.mediaId?.takeIf { it.isNotBlank() }
        val boundMediaId = tracker.boundMediaId
        if (boundMediaId != null && newMediaId != null && boundMediaId != newMediaId) {
            return false
        }
        if (tracker.boundQueueItemId >= 0L && snapshot.queueItemId >= 0L &&
            tracker.boundQueueItemId != snapshot.queueItemId
        ) {
            return false
        }
        return true
    }

    private fun onNoSession(
        tracker: ExternalPlaybackProgressEngine,
        pending: PendingExternalPlayback,
        now: Long,
        reason: String,
        outcome: ExternalPlaybackScanOutcome = ExternalPlaybackScanOutcome.NO_SESSION,
    ): ExternalPlaybackScanOutcome {
        if (outcome == ExternalPlaybackScanOutcome.NO_SESSION) {
            diagnostics("NO_SESSION: episode=${pending.episode.id} ($reason)")
        }
        val update = tracker.onSessionLost(now)
        if (update != null) {
            val saved = progressSink.save(pending, update)
            pendingStore.updateObserved(pending, update.positionMs, update.durationMs)
            if (!saved) tracker.onPersistFailed()
            diagnostics(
                "session lost episode=${pending.episode.id} saved=$saved " +
                        "pos=${update.positionMs}ms dur=${update.durationMs}ms"
            )
        } else {
            diagnostics(
                "session lost episode=${pending.episode.id} no-save " +
                        "reason=${tracker.lastDecision} ($reason)"
            )
        }
        return finish(outcome, reason)
    }

    private fun evaluateResumeSeek(
        session: ExternalPlaybackSession,
        tracker: ExternalPlaybackProgressEngine,
        pending: PendingExternalPlayback,
        snapshot: ExternalPlaybackSnapshot,
        now: Long,
    ) {
        val resume = resumeController ?: return
        when (val action = resume.onSnapshot(snapshot, tracker.boundSessionToken, now)) {
            null -> return
            is ExternalPlaybackResumeAction.SeekTo -> {
                val marked = pendingStore.markResumeSeekAttempted(pending)
                if (marked == null) {
                    resume.onSeekAborted()
                    diagnostics("resume seek aborted: pending session changed")
                    return
                }
                val seek = session.seekTo
                if (seek == null) {
                    resume.onSeekCommandFailed()
                    diagnostics("resume seek unavailable: no controller")
                    return
                }
                try {
                    seek(action.positionMs)
                    diagnostics(
                        "resume seek requested episode=${pending.episode.id} " +
                                "target=${action.positionMs}ms pos=${snapshot.positionMs}ms " +
                                "dur=${snapshot.durationMs}ms actions=${snapshot.actions}"
                    )
                } catch (t: Throwable) {
                    resume.onSeekCommandFailed()
                    diagnostics(
                        "resume seek failed episode=${pending.episode.id} " +
                                "target=${action.positionMs}ms: ${t.message}"
                    )
                }
            }

            ExternalPlaybackResumeAction.Confirmed -> {
                tracker.onResumeSeekConfirmed()
                diagnostics(
                    "resume confirmed episode=${pending.episode.id} " +
                            "target=${pending.initialPositionMs}ms " +
                            "reported=${resume.confirmedPositionMs}ms"
                )
            }

            ExternalPlaybackResumeAction.Failed -> {
                diagnostics(
                    "resume unavailable episode=${pending.episode.id} " +
                            "target=${pending.initialPositionMs}ms state=${resume.state}"
                )
            }
        }
    }

    private fun engineFor(pending: PendingExternalPlayback): ExternalPlaybackProgressEngine {
        val current = engine
        if (current != null && pendingSessionId == pending.sessionId) return current
        releaseEngineState()
        pendingSessionId = pending.sessionId
        return ExternalPlaybackProgressEngine(pending).also {
            engine = it
            resumeController = ExternalPlaybackResumeController(pending)
            diagnostics(
                "engine created pending=${shortId(pending.sessionId)} " +
                        "episode=${pending.episode.id} resume=${pending.initialPositionMs}ms " +
                        "observed=${pending.lastObservedPositionMs}/${pending.lastObservedDurationMs}ms"
            )
        }
    }

    private fun releaseEngineState() {
        engine = null
        resumeController = null
    }

    private fun finish(
        outcome: ExternalPlaybackScanOutcome,
        diagnostic: String,
    ): ExternalPlaybackScanOutcome {
        lastOutcome = outcome
        lastDiagnostic = diagnostic
        diagnostics("outcome=$outcome: $diagnostic")
        return outcome
    }

    private fun shortToken(token: String?): String {
        if (token.isNullOrBlank()) return "none"
        return Integer.toHexString(token.hashCode())
    }

    /**
     * Titles may be URLs or signed filenames. Never write those to logcat.
     */
    private fun sanitizedTitle(title: String?): String {
        if (title.isNullOrBlank()) return "none"
        if (ExternalPlaybackTitleMatcher.looksLikeUrl(title)) {
            return "url-like(len=${title.length})"
        }
        return title.take(60)
    }

    private fun shortId(id: String?): String {
        if (id.isNullOrBlank()) return "none"
        return id.take(8)
    }
}
