package com.pynapli.server.subscriptions

import com.pynapli.shared.network.dto.EntitlementSource
import java.time.Duration
import java.time.Instant
import java.util.UUID

interface SubscriptionRepository {
    suspend fun findBestActiveEntitlement(
        userId: UUID,
        now: Instant,
    ): ActiveEntitlementRecord? =
        listOfNotNull(
            findBestActiveOwnSubscription(userId, now)?.toActiveEntitlementRecord(),
            findBestActiveStoredEntitlement(userId, now)?.toActiveEntitlementRecord(),
            findBestActiveLinkedPartnerSubscription(userId, now)?.toActiveEntitlementRecord(),
        ).maxWithOrNull(activeEntitlementComparator())

    suspend fun findBestActiveOwnSubscription(
        userId: UUID,
        now: Instant,
    ): SubscriptionRecord?

    suspend fun findBestActiveLinkedPartnerSubscription(
        userId: UUID,
        now: Instant,
    ): LinkedPartnerSubscriptionRecord?

    suspend fun findBestActiveStoredEntitlement(
        userId: UUID,
        now: Instant,
    ): StoredEntitlementRecord?

    suspend fun listStoredEntitlementsForUser(
        userId: UUID,
        limit: Int,
    ): List<StoredEntitlementRecord> = emptyList()

    suspend fun grantStoredEntitlement(
        userId: UUID,
        tier: String,
        source: String,
        startsAt: Instant,
        endsAt: Instant?,
        actorId: String,
        reason: String,
    ): StoredEntitlementRecord? = null

    suspend fun revokeStoredEntitlement(
        entitlementId: UUID,
        actorId: String,
        reason: String,
    ): StoredEntitlementRecord? = null

    suspend fun consumeIntroNoteInventory(
        userId: UUID,
        now: Instant,
    ): Boolean

    suspend fun consumePineappleLikeInventory(
        userId: UUID,
        now: Instant,
    ): Boolean = false

    /**
     * Variant of `consumePineappleLikeInventory` that returns the `premium_inventory.id`
     * of the row decremented (or null if none was available). Lets the Pineapple Like
     * rewind path refund the exact row atomically (`refundInventoryById`). Default
     * impl falls back to the Boolean method so unrelated test stubs keep compiling.
     */
    suspend fun consumePineappleLikeInventoryReturningId(
        userId: UUID,
        now: Instant,
    ): UUID? = if (consumePineappleLikeInventory(userId, now)) UUID.randomUUID() else null

    /**
     * Increment a specific `premium_inventory` row's quantity by 1. Used by the
     * Pineapple Like rewind path to refund the exact row that was decremented when
     * the action was originally recorded. Returns true if the row was updated.
     */
    suspend fun refundInventoryById(
        inventoryId: UUID,
        now: Instant,
    ): Boolean = false

    suspend fun listPremiumInventoryBalances(
        userId: UUID,
        now: Instant,
    ): List<PremiumInventoryBalanceRecord> = emptyList()

    suspend fun grantPremiumInventory(
        userId: UUID,
        inventoryType: String,
        quantity: Int,
        source: String,
        expiresAt: Instant?,
        now: Instant,
    ): PremiumInventoryBalanceRecord? = null

    suspend fun activateBoost(
        userId: UUID,
        now: Instant,
        duration: Duration,
    ): BoostActivationResult? = null

    /** Credits a one-time Play purchase once per purchase token (see V88). */
    suspend fun creditPlayStoreProductPurchase(
        purchaseToken: String,
        userId: UUID,
        productId: String,
        orderId: String?,
        inventoryType: String,
        quantity: Int,
        now: Instant,
    ): ProductPurchaseCredit = ProductPurchaseCredit.AlreadyCredited

    /** The Boost running right now, if any (so the app can show its countdown after a restart). */
    suspend fun findActiveBoost(
        userId: UUID,
        now: Instant,
    ): BoostActivationRecord? = null

    suspend fun upsertDevSubscriptionPurchase(
        userId: UUID,
        productId: String,
        tier: String,
        startsAt: Instant,
        endsAt: Instant,
    ): SubscriptionRecord = throw SubscriptionException("DEV_STORE_PURCHASE_UNAVAILABLE")

    suspend fun listPlayStoreSubscriptionsDueForVerification(
        staleBefore: Instant,
        renewalWindowEnd: Instant,
        limit: Int,
    ): List<PlayStoreSubscriptionVerificationCandidate> = emptyList()

    suspend fun findPlayStoreSubscriptionByPurchaseToken(
        purchaseToken: String,
    ): PlayStoreSubscriptionVerificationCandidate? = null

    suspend fun insertPlayStoreRtdnEvent(event: PlayStoreRtdnEventInsert): Boolean = true

    suspend fun updatePlayStoreRtdnEventStatus(
        messageId: String,
        processingStatus: String,
        errorCode: String? = null,
    ) = Unit

    suspend fun upsertPendingPlayStoreSubscriptionPurchase(
        userId: UUID,
        productId: String,
        purchaseToken: String,
        tier: String,
        now: Instant,
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
        verifiedAt: Instant,
    ): SubscriptionRecord = throw SubscriptionException("SUBSCRIPTION_PURCHASE_SYNC_UNAVAILABLE")
}

data class SubscriptionRecord(
    val subscriptionId: UUID,
    val userId: UUID,
    val tier: String,
    val status: String,
    val currentPeriodStart: Instant,
    val currentPeriodEnd: Instant,
)

data class PlayStoreSubscriptionVerificationCandidate(
    val userId: UUID,
    val productId: String,
    val purchaseToken: String,
    val tier: String,
    val status: String,
    val currentPeriodStart: Instant,
    val currentPeriodEnd: Instant,
)

data class PlayStoreRtdnEventInsert(
    val messageId: String,
    val packageName: String?,
    val eventType: String,
    val notificationType: Int?,
    val purchaseToken: String?,
    val productId: String?,
    val eventTime: Instant?,
    val rawPayload: String,
)

data class LinkedPartnerSubscriptionRecord(
    val subscription: SubscriptionRecord,
    val linkedPartnerUserId: UUID,
    val partnerLinkId: UUID,
)

data class ActiveEntitlementRecord(
    val tier: String,
    val source: String,
    val sourceSubscriptionId: UUID?,
    val sourceUserId: UUID,
    val linkedPartnerUserId: UUID?,
    val startsAt: Instant,
    val endsAt: Instant?,
)

data class StoredEntitlementRecord(
    val entitlementId: UUID,
    val userId: UUID,
    val tier: String,
    val source: String,
    val sourceId: UUID?,
    val startsAt: Instant,
    val endsAt: Instant?,
    val status: String = "active",
)

data class BoostActivationRecord(
    val activationId: UUID,
    val userId: UUID,
    val boostType: String,
    val source: String,
    val startsAt: Instant,
    val endsAt: Instant,
    val status: String,
)

data class BoostActivationResult(
    val activation: BoostActivationRecord,
    val consumedInventory: Boolean,
)

data class PremiumInventoryBalanceRecord(
    val inventoryType: String,
    val quantity: Int,
    val nextExpiresAt: Instant?,
)

private fun SubscriptionRecord.toActiveEntitlementRecord(): ActiveEntitlementRecord =
    ActiveEntitlementRecord(
        tier = tier,
        source = EntitlementSource.OWN_SUBSCRIPTION,
        sourceSubscriptionId = subscriptionId,
        sourceUserId = userId,
        linkedPartnerUserId = null,
        startsAt = currentPeriodStart,
        endsAt = currentPeriodEnd,
    )

private fun LinkedPartnerSubscriptionRecord.toActiveEntitlementRecord(): ActiveEntitlementRecord =
    ActiveEntitlementRecord(
        tier = subscription.tier,
        source = EntitlementSource.LINKED_PARTNER_BENEFIT,
        sourceSubscriptionId = subscription.subscriptionId,
        sourceUserId = linkedPartnerUserId,
        linkedPartnerUserId = linkedPartnerUserId,
        startsAt = subscription.currentPeriodStart,
        endsAt = subscription.currentPeriodEnd,
    )

private fun StoredEntitlementRecord.toActiveEntitlementRecord(): ActiveEntitlementRecord =
    ActiveEntitlementRecord(
        tier = tier,
        source = source,
        sourceSubscriptionId = sourceId,
        sourceUserId = userId,
        linkedPartnerUserId = null,
        startsAt = startsAt,
        endsAt = endsAt,
    )

private fun activeEntitlementComparator(): Comparator<ActiveEntitlementRecord> =
    compareBy<ActiveEntitlementRecord> { entitlementTierRank(it.tier) }
        .thenBy { entitlementSourcePriority(it.source) }
        .thenBy { it.endsAt ?: Instant.EPOCH }

private fun entitlementTierRank(tier: String): Int =
    when (tier) {
        "select" -> 2
        "social" -> 1
        else -> 0
    }

private fun entitlementSourcePriority(source: String): Int =
    when (source) {
        EntitlementSource.OWN_SUBSCRIPTION -> 3
        EntitlementSource.ADMIN -> 2
        EntitlementSource.PROMO -> 1
        EntitlementSource.LINKED_PARTNER_BENEFIT -> 0
        else -> -1
    }
