package com.pynapli.server.psync

import com.pynapli.server.safety.RateLimitService
import com.pynapli.server.safety.RateLimitedAction
import com.pynapli.server.swipe.MatchSideEffectDelivery
import com.pynapli.server.swipe.SwipeDeckTokenCodec
import com.pynapli.shared.network.dto.PsyncActionResponseDto
import com.pynapli.shared.network.dto.PsyncDeckResponseDto
import com.pynapli.shared.network.dto.PsyncNudgeResponseDto
import com.pynapli.shared.network.dto.PsyncRecapDto
import com.pynapli.shared.network.dto.PsyncSessionDto
import com.pynapli.shared.network.dto.SubmitPsyncActionRequestDto
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.time.Clock
import java.util.UUID

private val logger = LoggerFactory.getLogger(PsyncActiveSessionActions::class.java)

internal class PsyncActiveSessionActions(
    private val repository: PsyncSessionRepository,
    private val deckCoordinator: PsyncDeckCoordinator,
    private val recapBuilder: PsyncRecapBuilder,
    private val events: PsyncSessionEvents,
    private val pineappleInventory: PsyncPineappleInventory?,
    private val pineappleRefunds: PsyncPineappleRefunds,
    private val rateLimitService: RateLimitService?,
    private val travelLocationRepository: com.pynapli.server.location.TravelLocationRepository?,
    private val subscriptionService: com.pynapli.server.subscriptions.SubscriptionService?,
    private val deckTokenCodec: SwipeDeckTokenCodec,
    private val sideEffectDelivery: MatchSideEffectDelivery,
    private val clock: Clock,
) {
    /**
     * PS-2: both-tap-Start gate. Marks the caller started; once EVERY participant has
     * started, generates the shared bounded deck and flips the session to `active`.
     */
    suspend fun start(
        sessionId: UUID,
        userId: UUID,
    ): PsyncSessionDto {
        val started = repository.start(sessionId, userId, clock.instant()).updatedOrThrow()
        if (started.alreadyActive) {
            return started.session.toDto()
        }
        if (!started.allStarted) {
            events.emitDelta(sessionId, PsyncBroadcaster.PARTNER_STARTED, started.session.otherParticipantIds(userId))
            return started.session.toDto()
        }

        // Both agreed to a deck at a particular place. If the initiator has since turned Travel mode
        // on, off, or moved it, dealing now would show them somewhere neither agreed to: ask again.
        val location = travelLocationRepository.psyncLocationFor(started.session.initiatorUserId)
        if (location.travelLocationId != started.session.travelLocationId) {
            subscriptionService?.let { requirePsyncTravelAllowed(location, it.getPolicy(started.session.initiatorUserId)) }
            repository.resetLocation(sessionId, location.travelLocationId, location.label, clock.instant()).toDtoOrThrow()
            events.emitDelta(sessionId, PsyncBroadcaster.SESSION_UPDATED, started.session.allParticipantIds())
            throw PsyncException("PSYNC_LOCATION_CHANGED")
        }

        return when (val activated = deckCoordinator.activateWithGeneratedDeck(sessionId, started.session)) {
            is PsyncSessionRepository.MutateOutcome.Updated -> {
                events.emitDelta(sessionId, PsyncBroadcaster.SESSION_ACTIVE, activated.session.allParticipantIds())
                events.recordForBoth(activated.session, "psync_started")
                activated.session.toDto()
            }
            else -> throw PsyncException("PSYNC_INVALID_STATE")
        }
    }

    /** The shared ordered deck for a participant; each card carries the viewer's OWN action only. */
    suspend fun getDeck(
        sessionId: UUID,
        userId: UUID,
    ): PsyncDeckResponseDto {
        val session = requireActiveParticipant(sessionId, userId, allowPaused = true)
        return deckCoordinator.buildDeckResponse(session, userId)
    }

    /** Record the caller's PRIVATE decision on a deck card. Idempotent; never reveals the partner. */
    suspend fun submitAction(
        sessionId: UUID,
        userId: UUID,
        request: SubmitPsyncActionRequestDto,
    ): PsyncActionResponseDto {
        if (request.action !in PSYNC_VALID_ACTIONS) throw PsyncException("PSYNC_INVALID_ACTION")
        val session = requireActiveParticipant(sessionId, userId, allowPaused = false)
        val card =
            repository.findCardByTokenHash(sessionId, deckTokenCodec.tokenHash(request.cardToken))
                ?: throw PsyncException("PSYNC_INVALID_CARD")
        if (deckCoordinator.targetHidesFrom(userId, card.targetUserId)) throw PsyncException("PSYNC_INVALID_CARD")
        val spentInventoryId = spendPineappleLikeIfNew(sessionId, userId, card.targetUserId, request.action)
        // Non-cancellable: a request cancelled mid-write still commits the row, which then owns the
        // spend. Cancellation surfaces after the refund decision, never in place of the outcome.
        val outcome =
            withContext(NonCancellable) {
                val recorded =
                    try {
                        repository.recordAction(
                            sessionId = sessionId,
                            actorUserId = userId,
                            targetUserId = card.targetUserId,
                            action = request.action,
                            now = clock.instant(),
                            consumedInventoryId = spentInventoryId,
                        )
                    } catch (error: Throwable) {
                        // Nothing recorded the spend, so no sweep could ever find it: give it back now.
                        refundSpent(spentInventoryId)
                        throw error
                    }
                // Lost a race with a concurrent submit for the same card (that decision stands), or
                // the session ended before this swipe was written: give back what this call spent.
                if (!recorded.newlyRecorded) refundSpent(spentInventoryId)
                recorded
            }
        if (!outcome.sessionActive) throw PsyncException("PSYNC_INVALID_STATE")

        // Each person's deck leaves out the cards of people hiding from them, so each finishes at
        // their own count.
        val totalCards = repository.countDeckCards(sessionId)
        val deckCount = totalCards - deckCoordinator.hiddenCardCount(sessionId, userId)
        val completed =
            if (outcome.actedCount >= deckCount && bothPartnersFinished(session, userId, totalCards)) {
                val finish = repository.complete(sessionId, userId, clock.instant())
                if (finish is PsyncSessionRepository.CompleteOutcome.Completed &&
                    finish.session.status == PSYNC_STATUS_COMPLETED
                ) {
                    announceResolved(finish)
                    true
                } else {
                    false
                }
            } else {
                false
            }

        if (!completed && outcome.newlyRecorded && deckCount > 0 && outcome.actedCount >= deckCount) {
            events.emitDelta(sessionId, PsyncBroadcaster.PARTNER_FINISHED, session.otherParticipantIds(userId))
        }

        return PsyncActionResponseDto(
            recorded = outcome.newlyRecorded,
            actedCount = outcome.actedCount,
            deckSize = session.deckSize,
            sessionCompleted = completed,
        )
    }

    suspend fun complete(
        sessionId: UUID,
        userId: UUID,
    ): PsyncRecapDto {
        val completed = repository.complete(sessionId, userId, clock.instant()).completedOrThrow()
        announceResolved(completed)
        return recapBuilder.buildRecap(completed.session, userId)
    }

    /**
     * End a started session early on behalf of [userId] (they left). What each partner already
     * decided still counts: likes are delivered and matches resolve, exactly as when the deck is
     * finished. Cards nobody reached are simply dropped, never treated as passes.
     */
    suspend fun endStarted(
        sessionId: UUID,
        userId: UUID,
    ): PsyncSessionDto {
        val completed = repository.complete(sessionId, userId, clock.instant()).completedOrThrow()
        announceResolved(completed)
        return completed.session.toDto()
    }

    /**
     * Resolve every started session whose deadline has passed (including ones whose partner link
     * ended). Returns how many were resolved. One failing session never blocks the rest.
     */
    suspend fun resolveOverdue(): Int =
        repository.listOverdueStartedSessions(clock.instant()).count { overdue ->
            runCatching {
                val outcome = repository.complete(overdue.sessionId, overdue.initiatorUserId, clock.instant())
                if (outcome is PsyncSessionRepository.CompleteOutcome.Completed) announceResolved(outcome)
                outcome is PsyncSessionRepository.CompleteOutcome.Completed
            }.getOrDefault(false)
        }

    /** Post-commit fan-out for a resolved session. Every step is idempotent on a re-complete. */
    private suspend fun announceResolved(completed: PsyncSessionRepository.CompleteOutcome.Completed) {
        // Matches and delivered likes: written to the match side-effect outbox in the completion
        // transaction, delivered here, and by the drainer if this never runs.
        val effects =
            completed.sideEffects
                ?: PsyncSideEffectPlans.forCompletion(completed.session.id, completed.resolvedMatches, completed.deliveredLikes)
        sideEffectDelivery.deliverNow(effects) { ids -> repository.markSideEffectsDelivered(ids, clock.instant()) }
        pineappleRefunds.sweep()
        events.emitDelta(completed.session.id, PsyncBroadcaster.SESSION_COMPLETED, completed.session.allParticipantIds())
        // Idempotent per (recipient, type, session).
        events.recordForBoth(completed.session, "psync_recap")
    }

    suspend fun getRecap(
        sessionId: UUID,
        userId: UUID,
    ): PsyncRecapDto {
        val session = repository.findById(sessionId) ?: throw PsyncException("PSYNC_SESSION_NOT_FOUND")
        if (session.participants.none { it.userId == userId }) throw PsyncException("PSYNC_SESSION_NOT_FOUND")
        if (session.status != PSYNC_STATUS_COMPLETED) throw PsyncException("PSYNC_INVALID_STATE")
        return recapBuilder.buildRecap(session, userId)
    }

    suspend fun pause(
        sessionId: UUID,
        userId: UUID,
    ): PsyncSessionDto {
        val dto = repository.transitionStatus(sessionId, userId, fromStatus = "active", toStatus = "paused").toDtoOrThrow()
        events.emitDelta(sessionId, PsyncBroadcaster.SESSION_PAUSED, dto.participantIds())
        return dto
    }

    suspend fun resume(
        sessionId: UUID,
        userId: UUID,
    ): PsyncSessionDto {
        val dto = repository.transitionStatus(sessionId, userId, fromStatus = "paused", toStatus = "active").toDtoOrThrow()
        events.emitDelta(sessionId, PsyncBroadcaster.SESSION_RESUMED, dto.participantIds())
        return dto
    }

    /**
     * PS-4b: nudge the other partner to keep swiping. Convenience-only realtime ping, rate
     * limited to a short cooldown; never reveals swipes.
     */
    suspend fun nudge(
        sessionId: UUID,
        userId: UUID,
    ): PsyncNudgeResponseDto {
        val session = requireActiveParticipant(sessionId, userId, allowPaused = true)
        if (rateLimitService?.check(userId, RateLimitedAction.PSYNC_NUDGE)?.allowed == false) {
            throw PsyncException("PSYNC_NUDGE_COOLDOWN")
        }
        events.emitDelta(sessionId, PsyncBroadcaster.NUDGE, session.otherParticipantIds(userId))
        return PsyncNudgeResponseDto(sent = true)
    }

    /**
     * A Pineapple Like spends one from the actor's inventory, as in the Swipe deck. A repeat of a
     * decision the actor already made on this card is an idempotent replay and spends nothing.
     */
    private suspend fun spendPineappleLikeIfNew(
        sessionId: UUID,
        userId: UUID,
        targetUserId: UUID,
        action: String,
    ): UUID? {
        val inventory = pineappleInventory ?: return null
        if (action != PSYNC_ACTION_PINEAPPLE_LIKE) return null
        val alreadyDecided = repository.listActionsForActor(sessionId, userId).any { it.targetUserId == targetUserId }
        if (alreadyDecided) return null
        return inventory.consume(userId) ?: throw PsyncException("PINEAPPLE_LIKE_INVENTORY_REQUIRED")
    }

    /**
     * Return a Pineapple Like this call spent but did not record. Runs even if the request was
     * cancelled, and a failed refund never replaces the error or outcome the caller gets.
     */
    private suspend fun refundSpent(inventoryId: UUID?) {
        val inventory = pineappleInventory ?: return
        if (inventoryId == null) return
        withContext(NonCancellable) {
            try {
                inventory.refund(inventoryId)
            } catch (refundError: Exception) {
                logger.warn("Psync Pineapple Like refund failed", refundError)
            }
        }
    }

    private suspend fun bothPartnersFinished(
        session: PsyncSession,
        actorUserId: UUID,
        deckCount: Int,
    ): Boolean {
        if (deckCount == 0) return false
        val partnerId = session.participants.firstOrNull { it.userId != actorUserId }?.userId ?: return false
        val partnerDeckCount = deckCount - deckCoordinator.hiddenCardCount(session.id, partnerId)
        return repository.countActionsForActor(session.id, partnerId) >= partnerDeckCount
    }

    private suspend fun requireActiveParticipant(
        sessionId: UUID,
        userId: UUID,
        allowPaused: Boolean,
    ): PsyncSession {
        val session =
            repository.findById(sessionId, withDisplayNames = false)
                ?: throw PsyncException("PSYNC_SESSION_NOT_FOUND")
        if (session.participants.none { it.userId == userId }) throw PsyncException("PSYNC_SESSION_NOT_FOUND")
        val live = session.status == PSYNC_STATUS_ACTIVE || (allowPaused && session.status == PSYNC_STATUS_PAUSED)
        if (!live) throw PsyncException("PSYNC_INVALID_STATE")
        // Past its deadline (or its partner link ended): no more swiping. Resolve it now so the
        // caller's refetch finds the recap.
        if (!session.expiresAt.isAfter(clock.instant())) {
            resolveOverdue()
            throw PsyncException("PSYNC_INVALID_STATE")
        }
        return session
    }
}
