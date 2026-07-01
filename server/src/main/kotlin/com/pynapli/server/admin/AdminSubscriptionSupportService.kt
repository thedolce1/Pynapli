package com.pynapli.server.admin

import com.pynapli.server.moderation.ModerationException
import com.pynapli.shared.network.dto.AdminLinkedBenefitDto
import com.pynapli.shared.network.dto.AdminSubscriptionRecordDto
import com.pynapli.shared.network.dto.AdminSubscriptionSupportDto
import com.pynapli.shared.network.dto.SubscriptionEntitlementDto
import java.util.UUID

/**
 * Builds the read-only admin subscription support view by composing the resolved entitlement (from
 * the existing subscription entitlement path that already owns that logic) with the user's stored
 * store-subscription rows and linked-partner benefit relationships. Raw purchase tokens / order ids
 * are never exposed — only the product id, status, and verification timestamps a support agent needs.
 */
class AdminSubscriptionSupportService(
    private val repository: AdminSubscriptionSupportRepository,
    private val entitlementLookup: suspend (UUID) -> SubscriptionEntitlementDto,
) {
    suspend fun view(
        actorId: String,
        userId: UUID,
        reason: String,
    ): AdminSubscriptionSupportDto {
        // Resolve facts first: a missing user short-circuits before we compose the entitlement or audit.
        val facts = repository.facts(userId) ?: throw ModerationException("USER_NOT_FOUND")
        val entitlement = entitlementLookup(userId)

        // Audit only after the user is confirmed to exist — this records a real data reveal.
        repository.recordReviewAudit(actorId = actorId, userId = userId, reason = reason)

        return AdminSubscriptionSupportDto(
            userId = userId.toString(),
            entitlement = entitlement,
            subscriptions = facts.subscriptions.map { it.toDto() },
            benefitsGranted = facts.benefitsGranted.map { it.toDto() },
            benefitsReceived = facts.benefitsReceived.map { it.toDto() },
        )
    }
}

private fun AdminSubscriptionRecordFact.toDto(): AdminSubscriptionRecordDto =
    AdminSubscriptionRecordDto(
        platform = platform,
        tier = tier,
        status = status,
        externalProductId = externalProductId,
        externalStatus = externalStatus,
        acknowledgementState = acknowledgementState,
        currentPeriodStart = currentPeriodStart.toString(),
        currentPeriodEnd = currentPeriodEnd.toString(),
        lastVerifiedAt = lastVerifiedAt?.toString(),
    )

private fun AdminLinkedBenefitFact.toDto(): AdminLinkedBenefitDto =
    AdminLinkedBenefitDto(
        otherUserId = otherUserId.toString(),
        tier = tier,
        status = status,
        startsAt = startsAt.toString(),
        endsAt = endsAt?.toString(),
    )
