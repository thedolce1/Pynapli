package com.pynapli.server.swipe

import com.pynapli.server.profiling.profile
import com.pynapli.server.safety.RateLimitService
import com.pynapli.server.safety.RateLimitedAction
import com.pynapli.server.safety.RiskPolicyService
import com.pynapli.server.subscriptions.EntitlementPolicy
import com.pynapli.server.subscriptions.SubscriptionService
import com.pynapli.shared.network.dto.SubscriptionTier
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
                    requireIntroNotesAllowed = action == SwipeAction.INTRO_NOTE,
                    now = now,
                )
            }
        if (!preconditions.deckCardActionable) {
            throw SwipeException(SwipeDeckTokenCodec.INVALID_CARD_TOKEN)
        }
        if (profile("swipe.action.risk") { riskPolicyService?.isSocialActionRestricted(viewerUserId) == true }) {
            throw SwipeException("RISK_RESTRICTED")
        }
        val policy =
            profile("swipe.action.policy") {
                subscriptionService?.getPolicy(viewerUserId) ?: EntitlementPolicy.unrestricted()
            }
        if (action == SwipeAction.INTRO_NOTE && policy.tier == SubscriptionTier.FREE) {
            throw SwipeException("ENTITLEMENT_REQUIRED")
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

        // Paid swipes spend their unit inside the record transaction, so a retry, a second request
        // for the same person, or a failed insert can never cost the user a unit for nothing.
        // Without a subscription service nothing is metered, as before.
        val spendInventory =
            when {
                subscriptionService == null -> null
                action == SwipeAction.INTRO_NOTE -> SwipeInventorySpend.INTRO_NOTE
                action == SwipeAction.PINEAPPLE_LIKE -> SwipeInventorySpend.PINEAPPLE_LIKE
                else -> null
            }

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
                )
            }
        if (result.inventoryUnavailable) {
            throw SwipeException(
                if (spendInventory == SwipeInventorySpend.INTRO_NOTE) {
                    "INTRO_NOTE_INVENTORY_REQUIRED"
                } else {
                    "PINEAPPLE_LIKE_INVENTORY_REQUIRED"
                },
            )
        }
        if (result.newlyRecorded) {
            sideEffects.recordActivityForAction(
                viewerUserId = viewerUserId,
                targetUserId = targetUserId,
                action = action,
                match = result.match,
            )
        }
        val match = result.match
        if (match != null) {
            sideEffects.notifyMatch(
                viewerUserId = viewerUserId,
                targetUserId = targetUserId,
                match = match,
            )
        }

        return SwipeActionResponseDto(
            recorded = result.recorded,
            match = match?.toDto(viewerUserId),
        )
    }
}
