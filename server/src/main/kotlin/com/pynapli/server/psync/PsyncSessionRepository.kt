package com.pynapli.server.psync

import java.time.Instant
import java.util.UUID

/**
 * Storage for Psync sessions. Multi-row writes (create, accept, deny, suggest, leave)
 * run in one transaction; the mutations lock the session row (FOR UPDATE) so the
 * "all participants approved?" check is atomic. The service enforces all product rules;
 * this layer only persists/reads.
 */
interface PsyncSessionRepository {
    /**
     * The user's single ACTIVE linked partner (status='active', unlinked_at IS NULL,
     * either direction), returned with the link row id, or null if none.
     */
    suspend fun findActiveLink(userId: UUID): ActiveLink?

    /** True if a non-terminal Psync session already exists for [linkId]. */
    suspend fun hasNonTerminalSessionForLink(linkId: UUID): Boolean

    /** Insert a session + its participant rows in one transaction. Returns it loaded. */
    suspend fun insertSession(
        session: PsyncSession,
        participants: List<PsyncSessionParticipant>,
    ): PsyncSession

    /**
     * Atomically set [userId]'s consent=accepted/approved=true on [sessionId] and, if every
     * participant is then approved, flip the session to 'ready'. Locks the session row.
     */
    suspend fun accept(
        sessionId: UUID,
        userId: UUID,
        now: Instant,
    ): MutateOutcome

    /**
     * Atomically set [userId]'s consent=denied and cancel the session. Locks the session row.
     */
    suspend fun deny(
        sessionId: UUID,
        userId: UUID,
        now: Instant,
    ): MutateOutcome

    /**
     * Atomically replace the session filters, set status='configuring', and reset all
     * participants approved=false EXCEPT the suggester (approved=true, consent=accepted).
     * Locks the session row.
     */
    suspend fun suggestFilters(
        sessionId: UUID,
        userId: UUID,
        filtersJson: String,
        now: Instant,
    ): MutateOutcome

    /** Cancel a session [userId] participates in that is currently non-terminal. Locks the row. */
    suspend fun leave(
        sessionId: UUID,
        userId: UUID,
        now: Instant,
    ): MutateOutcome

    /** Load a session (with participants) by id. */
    suspend fun findById(
        sessionId: UUID,
        withDisplayNames: Boolean = false,
    ): PsyncSession?

    /**
     * The user's current non-terminal session (status NOT terminal AND expires_at > [now])
     * with participant display names joined, or null.
     */
    suspend fun findCurrentForUser(
        userId: UUID,
        now: Instant,
    ): PsyncSession?

    /** Sweep stale non-terminal sessions to 'expired'. Returns rows updated. */
    suspend fun expireStale(now: Instant): Int

    /** The active linked partner of a user, with the link row id. */
    data class ActiveLink(
        val linkId: UUID,
        val partnerUserId: UUID,
    )

    /** Outcome of accept/deny/suggest/leave mutations. */
    sealed interface MutateOutcome {
        data class Updated(
            val session: PsyncSession,
        ) : MutateOutcome

        object SessionNotFound : MutateOutcome

        object NotParticipant : MutateOutcome

        object InvalidState : MutateOutcome
    }
}
