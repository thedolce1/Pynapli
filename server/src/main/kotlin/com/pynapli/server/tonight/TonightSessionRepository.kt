package com.pynapli.server.tonight

import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Storage for Playing Tonight sessions. Multi-row writes (propose, approve, decline,
 * end) run in a single transaction; approve locks the session row so the
 * "all participants approved?" check is atomic. The service enforces all product rules;
 * this layer only persists/reads.
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
     * Insert a session + its participant rows in one transaction. Solo sessions pass a
     * single owner (approved); together sessions pass owner (approved) + partner (pending).
     */
    suspend fun insertSession(
        session: TonightSession,
    ): TonightSession

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
