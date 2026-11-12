package com.pynapli.server.subscriptions

import java.time.Duration
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

class JdbcSubscriptionRepository(
    dataSource: DataSource,
) : SubscriptionRepository {
    private val entitlements = JdbcSubscriptionEntitlementActions(dataSource)
    private val inventory = JdbcSubscriptionInventoryRepositoryActions(dataSource)
    private val purchases = JdbcSubscriptionPurchaseRepositoryActions(dataSource)

    override suspend fun findBestActiveEntitlement(
        userId: UUID,
        now: Instant,
    ): ActiveEntitlementRecord? = entitlements.findBestActiveEntitlement(userId, now)

    override suspend fun findBestActiveOwnSubscription(
        userId: UUID,
        now: Instant,
    ): SubscriptionRecord? = entitlements.findBestActiveOwnSubscription(userId, now)

    override suspend fun findBestActiveLinkedPartnerSubscription(
        userId: UUID,
        now: Instant,
    ): LinkedPartnerSubscriptionRecord? = entitlements.findBestActiveLinkedPartnerSubscription(userId, now)

    override suspend fun findBestActiveStoredEntitlement(
        userId: UUID,
        now: Instant,
    ): StoredEntitlementRecord? = entitlements.findBestActiveStoredEntitlement(userId, now)

    override suspend fun listStoredEntitlementsForUser(
        userId: UUID,
        limit: Int,
    ): List<StoredEntitlementRecord> = entitlements.listStoredEntitlementsForUser(userId, limit)

    override suspend fun grantStoredEntitlement(
        userId: UUID,
        tier: String,
        source: String,
        startsAt: Instant,
        endsAt: Instant?,
        actorId: String,
        reason: String,
    ): StoredEntitlementRecord? = entitlements.grantStoredEntitlement(userId, tier, source, startsAt, endsAt, actorId, reason)

    override suspend fun revokeStoredEntitlement(
        entitlementId: UUID,
        actorId: String,
        reason: String,
    ): StoredEntitlementRecord? = entitlements.revokeStoredEntitlement(entitlementId, actorId, reason)

    override suspend fun consumeIntroNoteInventory(
        userId: UUID,
        now: Instant,
    ): Boolean = inventory.consumeIntroNoteInventory(userId, now)

    override suspend fun consumePineappleLikeInventory(
        userId: UUID,
        now: Instant,
    ): Boolean = inventory.consumePineappleLikeInventory(userId, now)

    override suspend fun consumePineappleLikeInventoryReturningId(
        userId: UUID,
        now: Instant,
    ): UUID? = inventory.consumePineappleLikeInventoryReturningId(userId, now)

    override suspend fun refundInventoryById(
        inventoryId: UUID,
        now: Instant,
    ): Boolean = inventory.refundInventoryById(inventoryId, now)

    override suspend fun listPremiumInventoryBalances(
        userId: UUID,
        now: Instant,
    ): List<PremiumInventoryBalanceRecord> = inventory.listPremiumInventoryBalances(userId, now)

    override suspend fun grantPremiumInventory(
        userId: UUID,
        inventoryType: String,
        quantity: Int,
        source: String,
        expiresAt: Instant?,
        now: Instant,
    ): PremiumInventoryBalanceRecord? = inventory.grantPremiumInventory(userId, inventoryType, quantity, source, expiresAt, now)

    override suspend fun activateBoost(
        userId: UUID,
        now: Instant,
        duration: Duration,
    ): BoostActivationResult? = inventory.activateBoost(userId, now, duration)

    override suspend fun findActiveBoost(
        userId: UUID,
        now: Instant,
    ): BoostActivationRecord? = inventory.findActiveBoost(userId, now)

    override suspend fun upsertDevSubscriptionPurchase(
        userId: UUID,
        productId: String,
        tier: String,
        startsAt: Instant,
        endsAt: Instant,
    ): SubscriptionRecord = purchases.upsertDevSubscriptionPurchase(userId, productId, tier, startsAt, endsAt)

    override suspend fun listPlayStoreSubscriptionsDueForVerification(
        staleBefore: Instant,
        renewalWindowEnd: Instant,
        limit: Int,
    ): List<PlayStoreSubscriptionVerificationCandidate> = purchases.listPlayStoreSubscriptionsDueForVerification(staleBefore, renewalWindowEnd, limit)

    override suspend fun findPlayStoreSubscriptionByPurchaseToken(
        purchaseToken: String,
    ): PlayStoreSubscriptionVerificationCandidate? = purchases.findPlayStoreSubscriptionByPurchaseToken(purchaseToken)

    override suspend fun insertPlayStoreRtdnEvent(event: PlayStoreRtdnEventInsert): Boolean = purchases.insertPlayStoreRtdnEvent(event)

    override suspend fun updatePlayStoreRtdnEventStatus(
        messageId: String,
        processingStatus: String,
        errorCode: String?,
    ): Unit = purchases.updatePlayStoreRtdnEventStatus(messageId, processingStatus, errorCode)

    override suspend fun upsertPendingPlayStoreSubscriptionPurchase(
        userId: UUID,
        productId: String,
        purchaseToken: String,
        tier: String,
        now: Instant,
    ): SubscriptionRecord = purchases.upsertPendingPlayStoreSubscriptionPurchase(userId, productId, purchaseToken, tier, now)

    override suspend fun upsertVerifiedPlayStoreSubscriptionPurchase(
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
    ): SubscriptionRecord =
        purchases.upsertVerifiedPlayStoreSubscriptionPurchase(
            userId = userId,
            productId = productId,
            purchaseToken = purchaseToken,
            orderId = orderId,
            tier = tier,
            status = status,
            externalStatus = externalStatus,
            acknowledgementState = acknowledgementState,
            acknowledgedAt = acknowledgedAt,
            currentPeriodStart = currentPeriodStart,
            currentPeriodEnd = currentPeriodEnd,
            verifiedAt = verifiedAt,
        )
}
