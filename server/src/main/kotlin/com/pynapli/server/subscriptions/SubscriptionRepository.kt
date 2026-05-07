package com.pynapli.server.subscriptions

import java.time.Instant
import java.util.UUID

interface SubscriptionRepository {
    suspend fun findBestActiveOwnSubscription(userId: UUID, now: Instant): SubscriptionRecord?

    suspend fun findBestActiveLinkedPartnerSubscription(userId: UUID, now: Instant): LinkedPartnerSubscriptionRecord?

    suspend fun findBestActiveStoredEntitlement(userId: UUID, now: Instant): StoredEntitlementRecord?

    suspend fun consumeIntroNoteInventory(userId: UUID, now: Instant): Boolean

    suspend fun upsertPendingPlayStoreSubscriptionPurchase(
        userId: UUID,
        productId: String,
        purchaseToken: String,
        tier: String,
        now: Instant
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
