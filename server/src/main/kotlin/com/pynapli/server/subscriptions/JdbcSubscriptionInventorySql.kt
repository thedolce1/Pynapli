package com.pynapli.server.subscriptions

import java.sql.Connection
import java.sql.Timestamp
import java.sql.Types
import java.time.Duration
import java.time.Instant
import java.util.UUID

internal fun Connection.consumePremiumInventoryRecord(
    userId: UUID,
    inventoryType: String,
    now: Instant,
): Boolean = consumePremiumInventoryRecordReturningId(userId, inventoryType, now) != null

internal fun Connection.consumePremiumInventoryRecordReturningId(
    userId: UUID,
    inventoryType: String,
    now: Instant,
): UUID? =
    prepareStatement(CONSUME_PREMIUM_INVENTORY_QUERY).use { statement ->
        statement.setObject(1, userId)
        statement.setString(2, inventoryType)
        statement.setTimestamp(3, Timestamp.from(now))
        statement.executeQuery().use { rs ->
            if (rs.next()) rs.getObject("id", UUID::class.java) else null
        }
    }

internal fun Connection.refundPremiumInventoryRecord(
    inventoryId: UUID,
    now: Instant,
): Boolean =
    prepareStatement(REFUND_PREMIUM_INVENTORY_QUERY).use { statement ->
        statement.setTimestamp(1, Timestamp.from(now))
        statement.setObject(2, inventoryId)
        statement.executeUpdate() > 0
    }

internal fun Connection.listPremiumInventoryBalances(
    userId: UUID,
    now: Instant,
): List<PremiumInventoryBalanceRecord> =
    prepareStatement(LIST_PREMIUM_INVENTORY_BALANCES_QUERY).use { statement ->
        statement.setObject(1, userId)
        statement.setTimestamp(2, Timestamp.from(now))
        statement.executeQuery().use { rs ->
            buildList {
                while (rs.next()) {
                    add(rs.toPremiumInventoryBalanceRecord())
                }
            }
        }
    }

internal fun Connection.grantPremiumInventoryRecord(
    userId: UUID,
    inventoryType: String,
    quantity: Int,
    source: String,
    expiresAt: Instant?,
    now: Instant,
) {
    prepareStatement(GRANT_PREMIUM_INVENTORY_QUERY).use { statement ->
        statement.setObject(1, UUID.randomUUID())
        statement.setObject(2, userId)
        statement.setString(3, inventoryType)
        statement.setInt(4, quantity)
        statement.setString(5, source)
        if (expiresAt == null) {
            statement.setNull(6, Types.TIMESTAMP_WITH_TIMEZONE)
        } else {
            statement.setTimestamp(6, Timestamp.from(expiresAt))
        }
        statement.setTimestamp(7, Timestamp.from(now))
        statement.executeUpdate()
    }
}

internal fun Connection.listPremiumInventoryBalanceRecord(
    userId: UUID,
    inventoryType: String,
    now: Instant,
): PremiumInventoryBalanceRecord? =
    prepareStatement(LIST_ONE_PREMIUM_INVENTORY_BALANCE_QUERY).use { statement ->
        statement.setObject(1, userId)
        statement.setString(2, inventoryType)
        statement.setTimestamp(3, Timestamp.from(now))
        statement.executeQuery().use { rs ->
            if (rs.next()) rs.toPremiumInventoryBalanceRecord() else null
        }
    }

internal fun Connection.findActiveBoostActivation(
    userId: UUID,
    now: Instant,
): BoostActivationRecord? =
    prepareStatement(FIND_ACTIVE_BOOST_ACTIVATION_QUERY).use { statement ->
        statement.setObject(1, userId)
        statement.setTimestamp(2, Timestamp.from(now))
        statement.executeQuery().use { rs ->
            if (rs.next()) rs.toBoostActivationRecord() else null
        }
    }

/**
 * Marks this user's Boosts that have run out as 'expired'. Readers already ignore them (they filter
 * on `ends_at`), but the one-active-Boost index (V92) counts rows by status, so a lapsed row must
 * leave 'active' before the next activation is inserted. Call it under the activation lock.
 */
internal fun Connection.expireLapsedBoostActivations(
    userId: UUID,
    now: Instant,
): Int =
    prepareStatement(
        """
        UPDATE boost_activations
        SET status = 'expired',
            updated_at = ?
        WHERE user_id = ?
          AND status = 'active'
          AND ends_at <= ?
        """.trimIndent(),
    ).use { statement ->
        statement.setTimestamp(1, Timestamp.from(now))
        statement.setObject(2, userId)
        statement.setTimestamp(3, Timestamp.from(now))
        statement.executeUpdate()
    }

internal fun Connection.insertBoostActivation(
    userId: UUID,
    startsAt: Instant,
    duration: Duration,
): BoostActivationRecord =
    prepareStatement(INSERT_BOOST_ACTIVATION_QUERY).use { statement ->
        statement.setObject(1, UUID.randomUUID())
        statement.setObject(2, userId)
        statement.setTimestamp(3, Timestamp.from(startsAt))
        statement.setTimestamp(4, Timestamp.from(startsAt.plus(duration)))
        statement.executeQuery().use { rs ->
            rs.next()
            rs.toBoostActivationRecord()
        }
    }

private val CONSUME_PREMIUM_INVENTORY_QUERY =
    """
    WITH candidate AS (
        SELECT id
        FROM premium_inventory
        WHERE user_id = ?
          AND inventory_type = ?
          AND quantity > 0
          AND (expires_at IS NULL OR expires_at > ?)
        ORDER BY expires_at ASC NULLS LAST, created_at ASC, id ASC
        LIMIT 1
        FOR UPDATE
    )
    UPDATE premium_inventory inventory
    SET quantity = inventory.quantity - 1,
        updated_at = now()
    FROM candidate
    WHERE inventory.id = candidate.id
    RETURNING inventory.id
    """.trimIndent()

private val REFUND_PREMIUM_INVENTORY_QUERY =
    """
    UPDATE premium_inventory
    SET quantity = quantity + 1,
        updated_at = ?
    WHERE id = ?
    """.trimIndent()

private val LIST_PREMIUM_INVENTORY_BALANCES_QUERY =
    """
    SELECT inventory_type,
           COALESCE(sum(quantity), 0)::integer AS quantity,
           min(expires_at) FILTER (WHERE expires_at IS NOT NULL) AS next_expires_at
    FROM premium_inventory
    WHERE user_id = ?
      AND quantity > 0
      AND (expires_at IS NULL OR expires_at > ?)
    GROUP BY inventory_type
    ORDER BY inventory_type ASC
    """.trimIndent()

private val LIST_ONE_PREMIUM_INVENTORY_BALANCE_QUERY =
    """
    SELECT inventory_type,
           COALESCE(sum(quantity), 0)::integer AS quantity,
           min(expires_at) FILTER (WHERE expires_at IS NOT NULL) AS next_expires_at
    FROM premium_inventory
    WHERE user_id = ?
      AND inventory_type = ?
      AND quantity > 0
      AND (expires_at IS NULL OR expires_at > ?)
    GROUP BY inventory_type
    """.trimIndent()

private val GRANT_PREMIUM_INVENTORY_QUERY =
    """
    INSERT INTO premium_inventory (
        id,
        user_id,
        inventory_type,
        quantity,
        source,
        expires_at,
        updated_at
    )
    VALUES (?, ?, ?, ?, ?, ?, ?)
    """.trimIndent()

private val FIND_ACTIVE_BOOST_ACTIVATION_QUERY =
    """
    SELECT id AS boost_activation_id,
           user_id AS boost_user_id,
           boost_type,
           source,
           starts_at,
           ends_at,
           status
    FROM boost_activations
    WHERE user_id = ?
      AND status = 'active'
      AND ends_at > ?
    ORDER BY ends_at DESC,
             starts_at DESC,
             id ASC
    LIMIT 1
    FOR UPDATE
    """.trimIndent()

private val INSERT_BOOST_ACTIVATION_QUERY =
    """
    INSERT INTO boost_activations (
        id,
        user_id,
        boost_type,
        source,
        starts_at,
        ends_at,
        status
    )
    VALUES (?, ?, 'boost', 'inventory', ?, ?, 'active')
    RETURNING id AS boost_activation_id,
              user_id AS boost_user_id,
              boost_type,
              source,
              starts_at,
              ends_at,
              status
    """.trimIndent()
