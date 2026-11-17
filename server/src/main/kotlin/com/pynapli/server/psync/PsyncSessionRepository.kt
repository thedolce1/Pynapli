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

    /**
     * Sessions [initiatorUserId] initiated that actually started (a deck was dealt) at or after
     * [since]. Invites that were declined, cancelled or expired before starting don't count.
     */
    suspend fun countStartedSessionsSince(
        initiatorUserId: UUID,
        since: Instant,
    ): Int = 0

    /** True if a non-terminal Psync session already exists for [linkId]. */
    suspend fun hasNonTerminalSessionForLink(linkId: UUID): Boolean

    /**
     * Insert a session + its participant rows in one transaction. Returns it loaded, or null (and
     * inserts nothing) if the session's link already has a non-terminal session: creates for one
     * link are serialised, so two partners creating at once yield exactly one session.
     */
    suspend fun insertSession(
        session: PsyncSession,
        participants: List<PsyncSessionParticipant>,
    ): PsyncSession?

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

    /** Sweep stale non-terminal sessions to 'expired'. Returns the swept sessions + participants. */
    suspend fun expireStale(now: Instant): List<PsyncExpiredSession>

    /**
     * Started (active or paused) sessions whose deadline has passed, each with a participant to
     * resolve it as. A partner link ending moves a started session's deadline to now, so those
     * show up here too.
     */
    suspend fun listOverdueStartedSessions(now: Instant): List<PsyncOverdueSession> = emptyList()

    /** The initiator's location moved before the deck started: record it and ask both again. */
    suspend fun resetLocation(
        sessionId: UUID,
        travelLocationId: UUID?,
        locationLabel: String?,
        now: Instant,
    ): MutateOutcome = MutateOutcome.InvalidState

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

    /**
     * Of [targetUserIds], those either partner has already settled with: a live swipe decision
     * (like, pass or Pineapple Like), an existing match, or a safety report. They never belong in
     * a shared deck. The ranker only knows the initiator's history; this covers both partners.
     */
    suspend fun alreadySettledTargets(
        partnerUserIds: List<UUID>,
        targetUserIds: List<UUID>,
    ): Set<UUID> = emptySet()

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
     * decision already existed, plus the actor's current acted count. Locks the session row and
     * writes nothing unless it is still `active` and before its deadline.
     */
    suspend fun recordAction(
        sessionId: UUID,
        actorUserId: UUID,
        targetUserId: UUID,
        action: String,
        now: Instant,
        actionIdFactory: () -> UUID = UUID::randomUUID,
        // The premium_inventory row a Pineapple Like spent, or null for a like/pass.
        consumedInventoryId: UUID? = null,
    ): RecordActionOutcome

    /**
     * Claim the Pineapple Likes that were spent in sessions that have ended but were never
     * delivered to the target as a swipe like, and return the inventory rows to refund (one entry
     * per like). Each is claimed exactly once.
     */
    suspend fun claimUndeliveredPineappleRefunds(): List<UUID> = emptyList()

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
    ): CompleteOutcome

    /** Resolved match outcomes for a session (display names joined). */
    suspend fun listSessionMatches(sessionId: UUID): List<PsyncSessionMatch>

    /** Number of cards in the shared deck (profiles reviewed). */
    suspend fun countDeckCards(sessionId: UUID): Int

    /** Number of deck targets liked (like/pineapple_like) by BOTH partners. */
    suspend fun countBothLiked(sessionId: UUID): Int

    /** Whether [userId] opted to share their Psync solo matches with their linked partner. */
    suspend fun isSoloShareEnabled(userId: UUID): Boolean

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

    /**
     * Outcome of [recordAction]: idempotent insert result + the actor's acted count.
     * [sessionActive] false means the session was no longer active (or past its deadline) once its
     * row was locked, so nothing was written.
     */
    data class RecordActionOutcome(
        val newlyRecorded: Boolean,
        val actedCount: Int,
        val sessionActive: Boolean = true,
    )

    /**
     * Outcome of [complete]. [Completed.resolvedMatches] is the set of canonical matches created
     * by THIS completion (empty on an idempotent re-complete), which the service replays into the
     * Activity/notification/realtime pipeline post-commit.
     */
    sealed interface CompleteOutcome {
        data class Completed(
            val session: PsyncSession,
            val resolvedMatches: List<PsyncResolvedMatch>,
            // Likes this completion delivered to targets who have not liked back yet (empty on an
            // idempotent re-complete), replayed post-commit into the targets' Likes You.
            val deliveredLikes: List<PsyncDeliveredLike> = emptyList(),
        ) : CompleteOutcome

        object SessionNotFound : CompleteOutcome

        object NotParticipant : CompleteOutcome

        object InvalidState : CompleteOutcome
    }
}
