package com.pynapli.server.subscriptions

import java.time.Instant
import java.util.UUID

interface SubscriptionRepository {
    suspend fun findBestActiveOwnSubscription(userId: UUID, now: Instant): SubscriptionRecord?

    suspend fun findBestActiveLinkedPartnerSubscription(userId: UUID, now: Instant): LinkedPartnerSubscriptionRecord?

    suspend fun findBestActiveStoredEntitlement(userId: UUID, now: Instant): StoredEntitlementRecord?

    suspend fun consumeIntroNoteInventory(userId: UUID, now: Instant): Boolean

    suspend fun listPlayStoreSubscriptionsDueForVerification(
        staleBefore: Instant,
        renewalWindowEnd: Instant,
        limit: Int
    ): List<PlayStoreSubscriptionVerificationCandidate> = emptyList()

    suspend fun upsertPendingPlayStoreSubscriptionPurchase(
        userId: UUID,
        productId: String,
        purchaseToken: String,
        tier: String,
        now: Instant
    ): SubscriptionRecord = throw SubscriptionException("SUBSCRIPTION_PURCHASE_SYNC_UNAVAILABLE")

    suspend fun upsertVerifiedPlayStoreSubscriptionPurchase(
        userId: UUID,
        productId: String,
        purchaseToken: String,
        orderId: String?,
        tier: String,
        status: String,
        externalStatus: String,
        acknowledgementState: String?,
        acknowledgedAt: Instant?,
        currentPeriodStart: Instant,
        currentPeriodEnd: Instant,
        verifiedAt: Instant
    ): SubscriptionRecord = throw SubscriptionException("SUBSCRIPTION_PURCHASE_SYNC_UNAVAILABLE")
}

data class SubscriptionRecord(
    val subscriptionId: UUID,
    val userId: UUID,
    val tier: String,
    val status: String,
    val currentPeriodStart: Instant,
    val currentPeriodEnd: Instant
)

data class PlayStoreSubscriptionVerificationCandidate(
    val userId: UUID,
    val productId: String,
    val purchaseToken: String,
    val tier: String,
    val status: String,
    val currentPeriodStart: Instant,
    val currentPeriodEnd: Instant
)

data class LinkedPartnerSubscriptionRecord(
    val subscription: SubscriptionRecord,
    val linkedPartnerUserId: UUID,
    val partnerLinkId: UUID
)

data class StoredEntitlementRecord(
    val entitlementId: UUID,
    val userId: UUID,
    val tier: String,
    val source: String,
    val sourceId: UUID?,
    val startsAt: Instant,
    val endsAt: Instant?
)
