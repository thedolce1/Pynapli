package com.pynapli.server.subscriptions

import com.pynapli.server.db.inTransaction
import com.pynapli.server.db.withConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

internal class JdbcSubscriptionInventoryRepositoryActions(
    private val dataSource: DataSource,
) {
    suspend fun consumeIntroNoteInventory(
        userId: UUID,
        now: Instant,
    ): Boolean = consumePremiumInventory(userId = userId, inventoryType = "intro_note", now = now)

    suspend fun consumePineappleLikeInventory(
        userId: UUID,
        now: Instant,
    ): Boolean = consumePineappleLikeInventoryReturningId(userId, now) != null

    suspend fun consumePineappleLikeInventoryReturningId(
        userId: UUID,
        now: Instant,
    ): UUID? =
        dataSource.withConnection { connection ->
            connection.consumePremiumInventoryRecordReturningId(userId, "pineapple_like", now)
        }

    suspend fun refundInventoryById(
        inventoryId: UUID,
        now: Instant,
    ): Boolean =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection -> connection.refundPremiumInventoryRecord(inventoryId, now) }
        }

    suspend fun listPremiumInventoryBalances(
        userId: UUID,
        now: Instant,
    ): List<PremiumInventoryBalanceRecord> =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection -> connection.listPremiumInventoryBalances(userId, now) }
        }

    suspend fun grantPremiumInventory(
        userId: UUID,
        inventoryType: String,
        quantity: Int,
        source: String,
        expiresAt: Instant?,
        now: Instant,
    ): PremiumInventoryBalanceRecord? =
        dataSource.withConnection { connection ->
            connection.inTransaction {
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

    suspend fun activateBoost(
        userId: UUID,
        now: Instant,
        duration: Duration,
    ): BoostActivationResult? =
        dataSource.withConnection { connection ->
            connection.inTransaction {
                findActiveBoostActivation(userId, now)?.let { active ->
                    return@inTransaction BoostActivationResult(
                        activation = active,
                        consumedInventory = false,
                    )
                }
                if (!consumePremiumInventoryRecord(userId, "boost", now)) {
                    return@inTransaction null
                }
                BoostActivationResult(
                    activation = insertBoostActivation(userId, now, duration),
                    consumedInventory = true,
                )
            }
        }

    suspend fun findActiveBoost(
        userId: UUID,
        now: Instant,
    ): BoostActivationRecord? = dataSource.withConnection { connection -> connection.findActiveBoostActivation(userId, now) }

    private suspend fun consumePremiumInventory(
        userId: UUID,
        inventoryType: String,
        now: Instant,
    ): Boolean =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection -> connection.consumePremiumInventoryRecord(userId, inventoryType, now) }
        }
}
