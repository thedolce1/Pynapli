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
 * on `ends_at`), but the one-active-Boost index (V101) counts rows by status, so a lapsed row must
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

/**
 * Brings the plan's included units of [allowances] up to date for [userId] (MONETIZATION_SPEC
 * "Included allowances"). Must run in the same transaction as, and before, anything that reads or
 * spends the balance, because it is the only thing that grants, tops up or withholds included units.
 *
 * Exactly once per period: the period's row is created by `INSERT ... ON CONFLICT DO UPDATE` on the
 * (user, type, period start) unique index (V117), so concurrent requests at the start of a period
 * create one row between them. On an existing row it only re-applies the cap for the current tier:
 * usable = max(0, entitled now - used this period), where used never changes here, so no sequence of
 * tier changes, links and unlinks can make more than the period's highest entitlement usable.
 */
internal fun Connection.reconcileIncludedAllowances(
    userId: UUID,
    allowances: List<IncludedAllowance>,
    now: Instant,
) {
    var anchor: Instant? = null
    allowances.forEach { allowance ->
        if (allowance.quantity <= 0) {
            withholdIncludedAllowance(userId, allowance.inventoryType, now)
            return@forEach
        }
        val resolvedAnchor =
            anchor
                ?: resolveIncludedAllowanceAnchor(userId, allowance.entitlementStartedAt, now).also { anchor = it }
        val period = IncludedAllowancePlan.periodContaining(resolvedAnchor, allowance.period, now)
        upsertIncludedAllowancePeriod(userId, allowance, period, now)
    }
}

/**
 * The person's allowance anchor, created on first use and never changed afterwards. Two statements
 * on purpose: under READ COMMITTED the SELECT after an `ON CONFLICT DO NOTHING` takes a fresh
 * snapshot, so it sees the row a concurrent first grant committed (a single CTE would not).
 */
internal fun Connection.resolveIncludedAllowanceAnchor(
    userId: UUID,
    entitlementStartedAt: Instant?,
    now: Instant,
): Instant {
    selectIncludedAllowanceAnchor(userId)?.let { return it }
    val candidate =
        (entitlementStartedAt?.takeIf { it <= now } ?: now)
            .truncatedTo(java.time.temporal.ChronoUnit.MICROS)
    prepareStatement(
        """
        INSERT INTO included_allowance_anchors (user_id, anchored_at)
        VALUES (?, ?)
        ON CONFLICT (user_id) DO NOTHING
        """.trimIndent(),
    ).use { statement ->
        statement.setObject(1, userId)
        statement.setTimestamp(2, Timestamp.from(candidate))
        statement.executeUpdate()
    }
    return selectIncludedAllowanceAnchor(userId) ?: candidate
}

private fun Connection.selectIncludedAllowanceAnchor(userId: UUID): Instant? =
    prepareStatement("SELECT anchored_at FROM included_allowance_anchors WHERE user_id = ?").use { statement ->
        statement.setObject(1, userId)
        statement.executeQuery().use { rs -> if (rs.next()) rs.getTimestamp("anchored_at").toInstant() else null }
    }

private fun Connection.upsertIncludedAllowancePeriod(
    userId: UUID,
    allowance: IncludedAllowance,
    period: IncludedAllowancePeriod,
    now: Instant,
) {
    prepareStatement(UPSERT_INCLUDED_ALLOWANCE_PERIOD_QUERY).use { statement ->
        statement.setObject(1, UUID.randomUUID())
        statement.setObject(2, userId)
        statement.setString(3, allowance.inventoryType)
        statement.setInt(4, allowance.quantity)
        statement.setTimestamp(5, Timestamp.from(period.end))
        statement.setTimestamp(6, Timestamp.from(period.start))
        statement.setInt(7, allowance.quantity)
        statement.setString(8, allowance.tier)
        statement.setTimestamp(9, Timestamp.from(now))
        statement.executeUpdate()
    }
}

/**
 * Not entitled right now: the current period's unused included units stop being usable. They are
 * moved to `allowance_withheld`, not deleted, so they come back if the tier does within the period.
 * Purchased rows are never touched (they have no `allowance_period_start`).
 */
private fun Connection.withholdIncludedAllowance(
    userId: UUID,
    inventoryType: String,
    now: Instant,
) {
    prepareStatement(
        """
        UPDATE premium_inventory
        SET allowance_withheld = allowance_withheld + quantity,
            quantity = 0,
            updated_at = ?
        WHERE user_id = ?
          AND inventory_type = ?
          AND allowance_period_start IS NOT NULL
          AND quantity > 0
          AND expires_at > ?
        """.trimIndent(),
    ).use { statement ->
        statement.setTimestamp(1, Timestamp.from(now))
        statement.setObject(2, userId)
        statement.setString(3, inventoryType)
        statement.setTimestamp(4, Timestamp.from(now))
        statement.executeUpdate()
    }
}

// used = allowance_granted - quantity - allowance_withheld (never negative). With T = the current
// tier's per-period amount (EXCLUDED.allowance_granted):
//   granted'  = max(granted, T)           (an upgrade raises the high-water mark; nothing lowers it)
//   quantity' = max(0, T - used)          (usable now)
//   withheld' = granted' - used - quantity'
// The WHERE skips the write when nothing changes (the row is still locked, which is harmless: the
// caller is about to read or spend it).
private val UPSERT_INCLUDED_ALLOWANCE_PERIOD_QUERY =
    """
    INSERT INTO premium_inventory AS pi (
        id,
        user_id,
        inventory_type,
        quantity,
        source,
        expires_at,
        allowance_period_start,
        allowance_granted,
        allowance_withheld,
        allowance_tier,
        updated_at
    )
    VALUES (?, ?, ?, ?, 'subscription', ?, ?, ?, 0, ?, ?)
    ON CONFLICT (user_id, inventory_type, allowance_period_start) WHERE allowance_period_start IS NOT NULL
    DO UPDATE SET
        quantity = GREATEST(0, EXCLUDED.allowance_granted - GREATEST(0, pi.allowance_granted - pi.quantity - pi.allowance_withheld)),
        allowance_withheld =
            GREATEST(pi.allowance_granted, EXCLUDED.allowance_granted)
            - GREATEST(0, pi.allowance_granted - pi.quantity - pi.allowance_withheld)
            - GREATEST(0, EXCLUDED.allowance_granted - GREATEST(0, pi.allowance_granted - pi.quantity - pi.allowance_withheld)),
        allowance_granted = GREATEST(pi.allowance_granted, EXCLUDED.allowance_granted),
        allowance_tier =
            CASE
                WHEN EXCLUDED.allowance_granted > pi.allowance_granted THEN EXCLUDED.allowance_tier
                ELSE pi.allowance_tier
            END,
        updated_at = EXCLUDED.updated_at
    WHERE pi.allowance_granted < EXCLUDED.allowance_granted
       OR pi.quantity <> GREATEST(0, EXCLUDED.allowance_granted - GREATEST(0, pi.allowance_granted - pi.quantity - pi.allowance_withheld))
    """.trimIndent()

private val CONSUME_PREMIUM_INVENTORY_QUERY =
    """
    WITH candidate AS (
        SELECT id
        FROM premium_inventory
        WHERE user_id = ?
          AND inventory_type = ?
          AND quantity > 0
          AND (expires_at IS NULL OR expires_at > ?)
        -- The plan's included units for the current period go first (they lapse at the period
        -- end), then whatever expires soonest; purchased units are touched only when those are gone.
        ORDER BY (allowance_period_start IS NOT NULL) DESC, expires_at ASC NULLS LAST, created_at ASC, id ASC
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
