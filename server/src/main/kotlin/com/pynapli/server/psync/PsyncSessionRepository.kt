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

    // ---- PS-2: shared deck + private swipes --------------------------------------------

    /**
     * Mark [userId]'s participant row `started=true` on a `ready` session (locks the row).
     * Reports back whether ALL participants are now started (the both-tap gate) and the
     * reloaded session. Does NOT generate the deck or flip status — the service computes
     * the deck (calling the swipe ranker + safety repos) then calls [activateWithDeck].
     * If the session is already `active`, returns it untouched with allStarted=true.
     */
    suspend fun start(
        sessionId: UUID,
        userId: UUID,
        now: Instant,
    ): StartOutcome

    /**
     * Atomically flip a `ready` session to `active`, set started_at/deck_session_id, and
     * persist the shared ordered [cards] (locks the row). Idempotent against races: if the
     * session is already `active` (deck already persisted) this is a no-op that returns the
     * current session. Used by the service after it has generated the deck.
     */
    suspend fun activateWithDeck(
        sessionId: UUID,
        deckSessionId: UUID,
        cards: List<PsyncSessionCard>,
        now: Instant,
    ): MutateOutcome

    /** The shared ordered deck cards for a session (position ascending). */
    suspend fun listDeckCards(sessionId: UUID): List<PsyncSessionCard>

    /** Only [actorUserId]'s own recorded actions for [sessionId], keyed by target user id. */
    suspend fun listActionsForActor(
        sessionId: UUID,
        actorUserId: UUID,
    ): List<PsyncSessionAction>

    /** A single deck card matched by its token hash within [sessionId], or null. */
    suspend fun findCardByTokenHash(
        sessionId: UUID,
        cardTokenHash: String,
    ): PsyncSessionCard?

    /**
     * Insert [actorUserId]'s private decision on [targetUserId] in [sessionId] idempotently
     * (ON CONFLICT DO NOTHING — first decision wins). Returns [newlyRecorded]=false if a
     * decision already existed, plus the actor's current acted count.
     */
    suspend fun recordAction(
        sessionId: UUID,
        actorUserId: UUID,
        targetUserId: UUID,
        action: String,
        now: Instant,
        actionIdFactory: () -> UUID = UUID::randomUUID,
    ): RecordActionOutcome

    /** Count of [actorUserId]'s own recorded decisions in [sessionId]. */
    suspend fun countActionsForActor(
        sessionId: UUID,
        actorUserId: UUID,
    ): Int

    /** Transition a session [userId] participates in between statuses (locks the row). */
    suspend fun transitionStatus(
        sessionId: UUID,
        userId: UUID,
        fromStatus: String,
        toStatus: String,
    ): MutateOutcome

    // ---- PS-3: match resolution + recap -------------------------------------------------

    /**
     * Finish a session [userId] participates in: resolve every deck target into a together /
     * solo / no-match outcome, persist the canonical `matches` rows + `psync_session_matches`
     * provenance, set status='completed' and ended_at, all under a `FOR UPDATE` lock. Requires
     * the session to be `active` or `paused`; if it is already `completed` this is an idempotent
     * no-op (no re-resolution) that returns the current session.
     */
    suspend fun complete(
        sessionId: UUID,
        userId: UUID,
        now: Instant,
        matchIdFactory: () -> UUID = UUID::randomUUID,
    ): MutateOutcome

    /** Resolved match outcomes for a session (display names joined). */
    suspend fun listSessionMatches(sessionId: UUID): List<PsyncSessionMatch>

    /** Number of cards in the shared deck (profiles reviewed). */
    suspend fun countDeckCards(sessionId: UUID): Int

    /** Number of deck targets liked (like/pineapple_like) by BOTH partners. */
    suspend fun countBothLiked(sessionId: UUID): Int

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

    /** Outcome of [start]: the reloaded session + whether the deck should now be generated. */
    sealed interface StartOutcome {
        /**
         * The both-tap gate state after marking the caller started. [allStarted] true means
         * every participant has started and the service should generate + persist the deck.
         * [alreadyActive] true means the session was already `active` (idempotent start).
         */
        data class Updated(
            val session: PsyncSession,
            val allStarted: Boolean,
            val alreadyActive: Boolean,
        ) : StartOutcome

        object SessionNotFound : StartOutcome

        object NotParticipant : StartOutcome

        object InvalidState : StartOutcome
    }

    /** Outcome of [recordAction]: idempotent insert result + the actor's acted count. */
    data class RecordActionOutcome(
        val newlyRecorded: Boolean,
        val actedCount: Int,
    )
}
