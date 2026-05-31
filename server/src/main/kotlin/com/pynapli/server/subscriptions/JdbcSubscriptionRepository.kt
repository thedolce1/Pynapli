package com.pynapli.server.subscriptions

import com.pynapli.server.moderation.insertAdminAuditLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.sql.Connection
import java.time.Duration
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

class JdbcSubscriptionRepository(
    private val dataSource: DataSource,
) : SubscriptionRepository {
    override suspend fun findBestActiveEntitlement(
        userId: UUID,
        now: Instant,
    ): ActiveEntitlementRecord? =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection.loadBestActiveEntitlement(userId, now)
            }
        }

    override suspend fun findBestActiveOwnSubscription(
        userId: UUID,
        now: Instant,
    ): SubscriptionRecord? =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection.loadBestActiveOwnSubscription(userId, now)
            }
        }

    override suspend fun findBestActiveLinkedPartnerSubscription(
        userId: UUID,
        now: Instant,
    ): LinkedPartnerSubscriptionRecord? =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection.loadBestActiveLinkedPartnerSubscription(userId, now)
            }
        }

    override suspend fun findBestActiveStoredEntitlement(
        userId: UUID,
        now: Instant,
    ): StoredEntitlementRecord? =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection.loadBestActiveStoredEntitlement(userId, now)
            }
        }

    override suspend fun listStoredEntitlementsForUser(
        userId: UUID,
        limit: Int,
    ): List<StoredEntitlementRecord> =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection.listStoredEntitlementRecordsForUser(userId, limit)
            }
        }

    override suspend fun grantStoredEntitlement(
        userId: UUID,
        tier: String,
        source: String,
        startsAt: Instant,
        endsAt: Instant?,
        actorId: String,
        reason: String,
    ): StoredEntitlementRecord? =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection.runInTransaction {
                    val record =
                        grantStoredEntitlementRecord(
                            userId = userId,
                            tier = tier,
                            source = source,
                            startsAt = startsAt,
                            endsAt = endsAt,
                        ) ?: return@runInTransaction null
                    insertAdminAuditLog(
                        actorId = actorId,
                        action = "subscription_entitlement.grant",
                        targetType = "entitlement",
                        targetId = record.entitlementId,
                        reason = reason,
                        metadataJson =
                            buildJsonObject {
                                put("userId", userId.toString())
                                put("tier", tier)
                                put("source", source)
                                put("startsAt", startsAt.toString())
                                put("endsAt", endsAt?.toString())
                            }.toString(),
                    )
                    record
                }
            }
        }

    override suspend fun revokeStoredEntitlement(
        entitlementId: UUID,
        actorId: String,
        reason: String,
    ): StoredEntitlementRecord? =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection.runInTransaction {
                    val record = revokeStoredEntitlementRecord(entitlementId) ?: return@runInTransaction null
                    insertAdminAuditLog(
                        actorId = actorId,
                        action = "subscription_entitlement.revoke",
                        targetType = "entitlement",
                        targetId = record.entitlementId,
                        reason = reason,
                        metadataJson =
                            buildJsonObject {
                                put("userId", record.userId.toString())
                                put("tier", record.tier)
                                put("source", record.source)
                            }.toString(),
                    )
                    record
                }
            }
        }

    override suspend fun consumeIntroNoteInventory(
        userId: UUID,
        now: Instant,
    ): Boolean = consumePremiumInventory(userId = userId, inventoryType = "intro_note", now = now)

    override suspend fun consumePineappleLikeInventory(
        userId: UUID,
        now: Instant,
    ): Boolean = consumePineappleLikeInventoryReturningId(userId, now) != null

    override suspend fun consumePineappleLikeInventoryReturningId(
        userId: UUID,
        now: Instant,
    ): UUID? =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection.consumePremiumInventoryRecordReturningId(userId, "pineapple_like", now)
            }
        }

    override suspend fun refundInventoryById(
        inventoryId: UUID,
        now: Instant,
    ): Boolean =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection.refundPremiumInventoryRecord(inventoryId, now)
            }
        }

    override suspend fun listPremiumInventoryBalances(
        userId: UUID,
        now: Instant,
    ): List<PremiumInventoryBalanceRecord> =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection.listPremiumInventoryBalances(userId, now)
            }
        }

    override suspend fun grantPremiumInventory(
        userId: UUID,
        inventoryType: String,
        quantity: Int,
        source: String,
        expiresAt: Instant?,
        now: Instant,
    ): PremiumInventoryBalanceRecord? =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection.runInTransaction {
                    grantPremiumInventoryRecord(
                        userId = userId,
                        inventoryType = inventoryType,
                        quantity = quantity,
                        source = source,
                        expiresAt = expiresAt,
                        now = now,
                    )
                    listPremiumInventoryBalanceRecord(userId, inventoryType, now)
                }
            }
        }

    private suspend fun consumePremiumInventory(
        userId: UUID,
        inventoryType: String,
        now: Instant,
    ): Boolean =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection.consumePremiumInventoryRecord(userId, inventoryType, now)
            }
        }

    override suspend fun activateBoost(
        userId: UUID,
        now: Instant,
        duration: Duration,
    ): BoostActivationResult? =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection.runInTransaction {
                    findActiveBoostActivation(userId, now)?.let { active ->
                        return@runInTransaction BoostActivationResult(
                            activation = active,
                            consumedInventory = false,
                        )
                    }
                    if (!consumePremiumInventoryRecord(userId, "boost", now)) {
                        return@runInTransaction null
                    }
                    BoostActivationResult(
                        activation = insertBoostActivation(userId, now, duration),
                        consumedInventory = true,
                    )
                }
            }
        }

    override suspend fun upsertDevSubscriptionPurchase(
        userId: UUID,
        productId: String,
        tier: String,
        startsAt: Instant,
        endsAt: Instant,
    ): SubscriptionRecord =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection.upsertDevSubscriptionPurchaseRecord(userId, productId, tier, startsAt, endsAt)
            }
        }

    override suspend fun listPlayStoreSubscriptionsDueForVerification(
        staleBefore: Instant,
        renewalWindowEnd: Instant,
        limit: Int,
    ): List<PlayStoreSubscriptionVerificationCandidate> =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection.listPlayStoreSubscriptionsDueForVerificationRecords(staleBefore, renewalWindowEnd, limit)
            }
        }

    override suspend fun findPlayStoreSubscriptionByPurchaseToken(
        purchaseToken: String,
    ): PlayStoreSubscriptionVerificationCandidate? =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection.findPlayStoreSubscriptionByPurchaseTokenRecord(purchaseToken)
            }
        }

    override suspend fun insertPlayStoreRtdnEvent(event: PlayStoreRtdnEventInsert): Boolean =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection.insertPlayStoreRtdnEventRecord(event)
            }
        }

    override suspend fun updatePlayStoreRtdnEventStatus(
        messageId: String,
        processingStatus: String,
        errorCode: String?,
    ): Unit =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection.updatePlayStoreRtdnEventStatusRecord(messageId, processingStatus, errorCode)
            }
        }

    override suspend fun upsertPendingPlayStoreSubscriptionPurchase(
        userId: UUID,
        productId: String,
        purchaseToken: String,
        tier: String,
        now: Instant,
    ): SubscriptionRecord =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection.upsertPendingPlayStoreSubscriptionPurchaseRecord(userId, productId, purchaseToken, tier, now)
            }
        }

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
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection.upsertVerifiedPlayStoreSubscriptionPurchaseRecord(
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
        }

    private fun <T> Connection.runInTransaction(block: Connection.() -> T): T {
        val previousAutoCommit = autoCommit
        autoCommit = false
        return try {
            val result = block()
            commit()
            result
        } catch (error: Throwable) {
            rollback()
            throw error
        } finally {
            autoCommit = previousAutoCommit
        }
    }
}
