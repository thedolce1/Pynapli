package com.pynapli.server.tonight

import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Storage for Playing Tonight sessions. Multi-row writes (propose, approve, decline,
 * end) run in a single transaction; approve locks the session row so the
 * "all participants approved?" check is atomic. The service decides the product rules;
 * the one exception is [insertSessionIfAllowed], which re-runs the service's
 * "already live" / weekly-cap checks under a lock, because only storage can make a
 * check-then-insert atomic.
 */
interface TonightSessionRepository {
    /** True if [photoAssetId] is a non-deleted asset owned by [userId]. */
    suspend fun isPhotoOwnedBy(
        userId: UUID,
        photoAssetId: UUID,
    ): Boolean

    /**
     * True if [userId] participates in a session that is still live/pending
     * (status IN ('proposed','active') AND expires_at > [now]).
     */
    suspend fun hasActiveOrProposedSession(
        userId: UUID,
        now: Instant,
    ): Boolean

    /**
     * Count of sessions [userId] created since [since] that are not declined — used for
     * the free-tier weekly cap.
     */
    suspend fun countSessionsCreatedSince(
        userId: UUID,
        since: Instant,
    ): Int

    /**
     * The single ACTIVE linked partner of [userId] (status='active', unlinked_at IS NULL,
     * either direction), or null if none.
     */
    suspend fun findActiveLinkedPartner(userId: UUID): UUID?

    /**
     * Insert a session + its participant rows in one transaction, unless a concurrent
     * proposal got there first. Solo sessions pass a single owner (approved); together
     * sessions pass owner (approved) + partner (pending).
     *
     * The transaction first takes a per-user advisory lock for every participant (sorted, so
     * overlapping proposals queue instead of deadlocking), then re-checks, in this order:
     * - the creator has no live session ([hasActiveOrProposedSession] at [now]) →
     *   otherwise [InsertOutcome.AlreadyActive];
     * - when [weeklyCap] is given, the creator has created fewer than [WeeklyCap.limit]
     *   sessions since [WeeklyCap.since] ([countSessionsCreatedSince]) →
     *   otherwise [InsertOutcome.WeeklyLimitReached].
     * Nothing is written unless the outcome is [InsertOutcome.Inserted].
     */
    suspend fun insertSessionIfAllowed(
        session: TonightSession,
        now: Instant,
        weeklyCap: WeeklyCap?,
    ): InsertOutcome

    /** A weekly creation cap for [insertSessionIfAllowed]; null there means uncapped. */
    data class WeeklyCap(
        val since: Instant,
        val limit: Int,
    )

    /** Outcome of [insertSessionIfAllowed]. */
    sealed interface InsertOutcome {
        data class Inserted(
            val session: TonightSession,
        ) : InsertOutcome

        object AlreadyActive : InsertOutcome

        object WeeklyLimitReached : InsertOutcome
    }

    /**
     * Atomically approve [userId]'s pending participant row on [sessionId] and, if every
     * participant is then approved, flip the session to active (started_at = [now],
     * expires_at = [now] + originalWindow). Locks the session row (FOR UPDATE).
     */
    suspend fun approveParticipant(
        sessionId: UUID,
        userId: UUID,
        now: Instant,
    ): ApproveOutcome

    /**
     * Atomically decline [userId]'s pending participant row and mark the session declined.
     * Locks the session row.
     */
    suspend fun declineParticipant(
        sessionId: UUID,
        userId: UUID,
        now: Instant,
    ): MutateOutcome

    /**
     * End a session that [userId] participates in and that is currently proposed/active.
     * Locks the session row.
     */
    suspend fun endSession(
        sessionId: UUID,
        userId: UUID,
        now: Instant,
    ): MutateOutcome

    /** Load a session (with participants, no display names) by id. */
    suspend fun findById(sessionId: UUID): TonightSession?

    /**
     * The user's current non-terminal session (status IN ('proposed','active') AND
     * expires_at > [now]) with participant display names joined, or null.
     */
    suspend fun findCurrentForUser(
        userId: UUID,
        now: Instant,
    ): TonightSession?

    /** Sweep stale sessions to 'expired'. Returns rows updated. */
    suspend fun expireStale(now: Instant): Int

    /**
     * Atomically claim active sessions whose expiry falls within [leadWindow] of [now] and that
     * have not yet been reminded (status='active' AND reminder_sent_at IS NULL AND
     * expires_at > [now] AND expires_at <= [now] + [leadWindow]). In ONE transaction this
     * selects the due rows FOR UPDATE SKIP LOCKED, stamps reminder_sent_at = [now], and returns
     * each claimed session with its participant user ids. The stamp is the idempotency latch:
     * a session is claimed (and therefore reminded) at most once across ticks/instances.
     */
    suspend fun claimSessionsDueForReminder(
        now: Instant,
        leadWindow: Duration,
    ): List<TonightReminderTarget>

    /** A session claimed for a 15-minute-before-expiry reminder, with its participants. */
    data class TonightReminderTarget(
        val sessionId: UUID,
        val participantUserIds: List<UUID>,
    )

    /**
     * Together-only, currently-live (status='active' AND expires_at > [now]) sessions with their
     * participant user ids and current [pausedAt]. Used by the presence reconciler to decide
     * pause/resume/end. Solo sessions are intentionally excluded — presence pause/end applies only
     * to linked-together sessions (PLAYING_TONIGHT_SPEC Edge Cases).
     */
    suspend fun listActivePresenceSessions(now: Instant): List<TonightPresenceSession>

    /**
     * Set paused_at = [now] on a session (begin a presence pause), only while it is still
     * status='active'. Returns false when it no longer was (ended/declined/expired since the
     * reconciler listed it) and nothing changed.
     */
    suspend fun pauseSession(
        sessionId: UUID,
        now: Instant,
    ): Boolean

    /**
     * Clear paused_at on a session (resume after the participant returned), only while it is
     * still status='active'. Returns false when nothing changed.
     */
    suspend fun resumeSession(sessionId: UUID): Boolean

    /**
     * End a session as the SYSTEM (participant-agnostic): status='ended', ended_at=[now]. Unlike
     * [endSession] this performs no participant gating, so it must NOT be used for user-initiated
     * ends. It is gated on status='active' only: the reconciler lists sessions and acts on them
     * later, and a session a user ended, or that expired, in between must keep that outcome.
     * Returns false when the session was no longer active and nothing changed.
     */
    suspend fun systemEndSession(
        sessionId: UUID,
        now: Instant,
    ): Boolean

    /** A live together session the presence reconciler may pause/resume/end. */
    data class TonightPresenceSession(
        val sessionId: UUID,
        val pausedAt: Instant?,
        val participantUserIds: List<UUID>,
    )

    /** Outcome of approve/decline/end participant mutations. */
    sealed interface MutateOutcome {
        data class Updated(
            val session: TonightSession,
        ) : MutateOutcome

        object SessionNotFound : MutateOutcome

        object NotParticipant : MutateOutcome

        object NotPending : MutateOutcome
    }

    /** Approve adds the "all approved" signal so the service can report activation. */
    sealed interface ApproveOutcome {
        data class Updated(
            val session: TonightSession,
        ) : ApproveOutcome

        object SessionNotFound : ApproveOutcome

        object NotParticipant : ApproveOutcome

        object NotPending : ApproveOutcome
    }
}
