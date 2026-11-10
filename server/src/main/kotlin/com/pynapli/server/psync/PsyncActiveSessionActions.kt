package com.pynapli.server.psync

import com.pynapli.server.safety.RateLimitService
import com.pynapli.server.safety.RateLimitedAction
import com.pynapli.server.swipe.SwipeDeckTokenCodec
import com.pynapli.shared.network.dto.PsyncActionResponseDto
import com.pynapli.shared.network.dto.PsyncDeckResponseDto
import com.pynapli.shared.network.dto.PsyncNudgeResponseDto
import com.pynapli.shared.network.dto.PsyncRecapDto
import com.pynapli.shared.network.dto.PsyncSessionDto
import com.pynapli.shared.network.dto.SubmitPsyncActionRequestDto
import java.time.Clock
import java.util.UUID

internal class PsyncActiveSessionActions(
    private val repository: PsyncSessionRepository,
    private val deckCoordinator: PsyncDeckCoordinator,
    private val recapBuilder: PsyncRecapBuilder,
    private val events: PsyncSessionEvents,
    private val pineappleInventory: PsyncPineappleInventory?,
    private val pineappleRefunds: PsyncPineappleRefunds,
    private val rateLimitService: RateLimitService?,
    private val deckTokenCodec: SwipeDeckTokenCodec,
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
        val spentInventoryId = spendPineappleLikeIfNew(sessionId, userId, card.targetUserId, request.action)
        val outcome =
            repository.recordAction(
                sessionId = sessionId,
                actorUserId = userId,
                targetUserId = card.targetUserId,
                action = request.action,
                now = clock.instant(),
                consumedInventoryId = spentInventoryId,
            )
        // Lost a race with a concurrent submit for the same card: that decision stands, so give
        // back what this call spent.
        if (!outcome.newlyRecorded && spentInventoryId != null) pineappleInventory?.refund(spentInventoryId)

        val deckCount = repository.countDeckCards(sessionId)
        val completed =
            if (outcome.actedCount >= deckCount && bothPartnersFinished(session, userId, deckCount)) {
                val finish = repository.complete(sessionId, userId, clock.instant())
                if (finish is PsyncSessionRepository.CompleteOutcome.Completed &&
                    finish.session.status == PSYNC_STATUS_COMPLETED
                ) {
                    events.emitMatchActivity(finish.resolvedMatches)
                    events.emitDeliveredLikes(finish.deliveredLikes)
                    pineappleRefunds.sweep()
                    events.emitDelta(sessionId, PsyncBroadcaster.SESSION_COMPLETED, session.allParticipantIds())
                    events.recordForBoth(finish.session, "psync_recap")
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
        events.emitMatchActivity(completed.resolvedMatches)
        events.emitDeliveredLikes(completed.deliveredLikes)
        pineappleRefunds.sweep()
        // Idempotent per (recipient, type, session) — safe on an idempotent re-complete.
        events.recordForBoth(completed.session, "psync_recap")
        return recapBuilder.buildRecap(completed.session, userId)
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

    private suspend fun bothPartnersFinished(
        session: PsyncSession,
        actorUserId: UUID,
        deckCount: Int,
    ): Boolean {
        if (deckCount == 0) return false
        val partnerId = session.participants.firstOrNull { it.userId != actorUserId }?.userId ?: return false
        return repository.countActionsForActor(session.id, partnerId) >= deckCount
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
        return session
    }
}
