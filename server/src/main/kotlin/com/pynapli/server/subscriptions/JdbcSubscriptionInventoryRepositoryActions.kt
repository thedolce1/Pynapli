package com.pynapli.server.subscriptions

import com.pynapli.server.db.inTransaction
import com.pynapli.server.db.lockForTransaction
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
                // With no Boost running, the FOR UPDATE below has no row to lock, so a double tap
                // would let both requests spend and start one. Serialise activations per user.
                lockForTransaction(BOOST_ACTIVATION_LOCK_NAMESPACE, userId.toString())
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

    suspend fun creditPlayStoreProductPurchase(
        purchaseToken: String,
        userId: UUID,
        productId: String,
        orderId: String?,
        inventoryType: String,
        quantity: Int,
        now: Instant,
    ): ProductPurchaseCredit =
        dataSource.withConnection { connection ->
            connection.inTransaction {
                val inserted =
                    prepareStatement(
                        """
                        INSERT INTO play_store_product_purchases (purchase_token, user_id, product_id, order_id, inventory_type, quantity)
                        VALUES (?, ?, ?, ?, ?, ?)
                        ON CONFLICT (purchase_token) DO NOTHING
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setString(1, purchaseToken)
                        statement.setObject(2, userId)
                        statement.setString(3, productId)
                        statement.setString(4, orderId)
                        statement.setString(5, inventoryType)
                        statement.setInt(6, quantity)
                        statement.executeUpdate() > 0
                    }
                if (!inserted) {
                    val owner =
                        prepareStatement("SELECT user_id FROM play_store_product_purchases WHERE purchase_token = ?").use { statement ->
                            statement.setString(1, purchaseToken)
                            statement.executeQuery().use { rs -> if (rs.next()) rs.getObject(1, UUID::class.java) else null }
                        }
                    return@inTransaction if (owner == userId) ProductPurchaseCredit.AlreadyCredited else ProductPurchaseCredit.OwnedByAnotherAccount
                }
                grantPremiumInventoryRecord(
                    userId = userId,
                    inventoryType = inventoryType,
                    quantity = quantity,
                    source = "purchase",
                    expiresAt = null,
                    now = now,
                )
                ProductPurchaseCredit.Credited
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

private const val BOOST_ACTIVATION_LOCK_NAMESPACE = "boost-activation"
