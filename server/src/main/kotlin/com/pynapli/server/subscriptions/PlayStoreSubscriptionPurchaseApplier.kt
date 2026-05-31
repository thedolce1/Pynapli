package com.pynapli.server.subscriptions

import java.time.Instant
import java.util.UUID

internal class PlayStoreSubscriptionPurchaseApplier(
    private val repository: SubscriptionRepository,
    private val invalidateEntitlement: (UUID) -> Unit,
) {
    suspend fun applyVerifiedPurchase(
        userId: UUID,
        tier: String,
        purchase: VerifiedPlayStoreSubscriptionPurchase,
        verifiedAt: Instant,
    ): SubscriptionRecord {
        val subscriptionStatus = purchase.toSubscriptionStatus(verifiedAt)
        val subscription =
            repository.upsertVerifiedPlayStoreSubscriptionPurchase(
                userId = userId,
                productId = purchase.productId,
                purchaseToken = purchase.purchaseToken,
                orderId = purchase.latestOrderId,
                tier = tier,
                status = subscriptionStatus,
                externalStatus = purchase.subscriptionState,
                acknowledgementState = purchase.acknowledgementState,
                acknowledgedAt = if (purchase.isAcknowledged()) verifiedAt else null,
                currentPeriodStart = purchase.currentPeriodStart,
                currentPeriodEnd = purchase.currentPeriodEnd,
                verifiedAt = verifiedAt,
            )
        invalidateEntitlement(userId)
        return subscription
    }

    suspend fun revokeStoredPurchase(
        stored: PlayStoreSubscriptionVerificationCandidate,
        purchaseToken: String = stored.purchaseToken,
        orderId: String? = null,
        externalStatus: String,
        verifiedAt: Instant,
    ): SubscriptionRecord {
        val subscription =
            repository.upsertVerifiedPlayStoreSubscriptionPurchase(
                userId = stored.userId,
                productId = stored.productId,
                purchaseToken = purchaseToken,
                orderId = orderId,
                tier = stored.tier,
                status = "revoked",
                externalStatus = externalStatus,
                acknowledgementState = null,
                acknowledgedAt = null,
                currentPeriodStart = stored.currentPeriodStart,
                currentPeriodEnd = stored.currentPeriodEnd,
                verifiedAt = verifiedAt,
            )
        invalidateEntitlement(stored.userId)
        return subscription
    }
}

internal val ACTIVE_PLAY_STORE_SUBSCRIPTION_STATUSES = setOf("active", "grace")

private fun VerifiedPlayStoreSubscriptionPurchase.toSubscriptionStatus(now: Instant): String {
    if (currentPeriodEnd <= now) return "expired"
    return when (subscriptionState) {
        "SUBSCRIPTION_STATE_ACTIVE",
        "SUBSCRIPTION_STATE_CANCELED",
        -> "active"
        "SUBSCRIPTION_STATE_IN_GRACE_PERIOD" -> "grace"
        "SUBSCRIPTION_STATE_EXPIRED" -> "expired"
        "SUBSCRIPTION_STATE_REVOKED" -> "revoked"
        else -> "pending"
    }
}

private fun VerifiedPlayStoreSubscriptionPurchase.isAcknowledged(): Boolean = acknowledgementState == "ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED"
