package com.pynapli.server.swipe

import com.pynapli.server.profiling.profile
import com.pynapli.server.safety.RateLimitService
import com.pynapli.server.safety.RateLimitedAction
import com.pynapli.server.safety.RiskPolicyService
import com.pynapli.server.subscriptions.SubscriptionService
import com.pynapli.shared.network.dto.SwipeAction
import com.pynapli.shared.network.dto.SwipeActionRequestDto
import com.pynapli.shared.network.dto.SwipeActionResponseDto
import java.time.Clock
import java.util.UUID

internal class SwipeSubmitAction(
    private val repository: SwipeRepository,
    private val sideEffects: SwipeActionSideEffects,
    private val deckTokenCodec: SwipeDeckTokenCodec,
    private val rateLimitService: RateLimitService?,
    private val riskPolicyService: RiskPolicyService?,
    private val subscriptionService: SubscriptionService?,
    private val clock: Clock,
    private val contactBlockVisibility: com.pynapli.server.contactblocks.ContactBlockVisibility? = null,
) {
    suspend fun submitAction(
        viewerUserId: UUID,
        request: SwipeActionRequestDto,
    ): SwipeActionResponseDto {
        val parsedRequest = SwipeActionRequestParser.parse(viewerUserId, request)
        val targetUserId = parsedRequest.targetUserId
        val action = parsedRequest.action
        val noteText = parsedRequest.noteText
        val actionId = parsedRequest.actionId
        val existingAction =
            profile("swipe.action.idempotency") {
                repository.findSwipeActionById(actionId, viewerUserId)
            }
        if (existingAction != null) {
            if (
                existingAction.targetUserId != targetUserId ||
                existingAction.action != action ||
                existingAction.noteText != noteText
            ) {
                throw SwipeException("INVALID_SWIPE_ACTION_ID")
            }
            if (existingAction.rewoundAt != null) {
                return SwipeActionResponseDto(recorded = false)
            }
            return existingAction.toResponseDto(viewerUserId)
        }
        val now = clock.instant()
        val actionTokens =
            try {
                deckTokenCodec.validateAction(
                    deckSessionId = parsedRequest.deckSessionId,
                    cardToken = parsedRequest.cardToken,
                    viewerUserId = viewerUserId,
                    targetUserId = targetUserId,
                    now = now,
                )
            } catch (error: SwipeDeckTokenException) {
                throw SwipeException(error.code)
            }
        val preconditions =
            profile("swipe.action.preconditions") {
                repository.checkActionPreconditions(
                    sessionId = actionTokens.sessionId,
                    cardTokenHash = actionTokens.cardTokenHash,
                    viewerUserId = viewerUserId,
                    targetUserId = targetUserId,
                    radiusMeters = SWIPE_MAX_RADIUS_METERS,
                    now = now,
                )
            }
        if (!preconditions.deckCardActionable) {
            throw SwipeException(SwipeDeckTokenCodec.INVALID_CARD_TOKEN)
        }
        if (profile("swipe.action.risk") { riskPolicyService?.isSocialActionRestricted(viewerUserId) == true }) {
            throw SwipeException("RISK_RESTRICTED")
        }
        if (profile("swipe.action.rateLimit") {
                rateLimitService?.check(viewerUserId, RateLimitedAction.SWIPE_ACTION)?.allowed == false
            }
        ) {
            throw SwipeException("RATE_LIMITED")
        }

        if (!preconditions.targetEligible) {
            throw SwipeException("INVALID_SWIPE_TARGET")
        }
        // "Hide from people you know": a card dealt before the block was bound can't be used to like
        // (and so match) someone who now hides from the viewer. Same answer as any ineligible target.
        if (contactBlockVisibility?.isHiddenFrom(viewerUserId, targetUserId) == true) {
            throw SwipeException("INVALID_SWIPE_TARGET")
        }

        // "Allow notes with Pineapple Likes" off: refuse the note, not the like. Nothing is spent, so
        // the client can offer to send the same Pineapple Like without it.
        if (noteText != null && !preconditions.targetAcceptsNotes) {
            throw SwipeException("PINEAPPLE_LIKE_NOTE_NOT_ACCEPTED")
        }

        // A Pineapple Like (with or without a note) spends its unit inside the record transaction, so
        // a retry, a second request for the same person, or a failed insert can never cost the user a
        // unit for nothing.
        // Without a subscription service nothing is metered, as before.
        val spendInventory =
            when {
                subscriptionService == null -> null
                action == SwipeAction.PINEAPPLE_LIKE -> SwipeInventorySpend.PINEAPPLE_LIKE
                else -> null
            }
        // What the plan includes this period (Social 1, Select 5 Pineapple Likes a week), from the
        // cached entitlement. Resolved only for a Pineapple Like, so likes and passes pay nothing.
        val includedAllowance =
            spendInventory?.let { spend -> subscriptionService?.includedAllowance(viewerUserId, spend.inventoryType) }

        val result =
            profile("swipe.action.record") {
                repository.recordSwipeActionAndMarkCard(
                    actionId = actionId,
                    actorUserId = viewerUserId,
                    targetUserId = targetUserId,
                    action = action,
                    sessionId = actionTokens.sessionId,
                    cardTokenHash = actionTokens.cardTokenHash,
                    now = clock.instant(),
                    noteText = noteText,
                    travelLocationId = preconditions.viewerTravelLocationId,
                    spendInventory = spendInventory,
                    includedAllowance = includedAllowance,
                )
            }
        if (result.inventoryUnavailable) {
            throw SwipeException("PINEAPPLE_LIKE_INVENTORY_REQUIRED")
        }
        // A like the target can see: if the target hides from the viewer (a bind that raced this like), the pair is now exempt.
        if (action != SwipeAction.PASS) contactBlockVisibility?.noteActToward(viewerUserId, targetUserId)
        sideEffects.deliver(result, actorUserId = viewerUserId, targetUserId = targetUserId, action = action)
        val match = result.match

        return SwipeActionResponseDto(
            recorded = result.recorded,
            match = match?.toDto(viewerUserId),
        )
    }
}
