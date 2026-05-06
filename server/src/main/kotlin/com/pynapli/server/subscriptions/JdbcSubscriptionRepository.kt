package com.pynapli.server.subscriptions

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

class JdbcSubscriptionRepository(
    private val dataSource: DataSource
) : SubscriptionRepository {
    override suspend fun findBestActiveOwnSubscription(userId: UUID, now: Instant): SubscriptionRecord? =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection.prepareStatement(ACTIVE_OWN_SUBSCRIPTION_QUERY).use { statement ->
                    statement.setObject(1, userId)
                    statement.setTimestamp(2, Timestamp.from(now))
                    statement.setTimestamp(3, Timestamp.from(now))
                    statement.executeQuery().use { rs ->
                        if (rs.next()) rs.toSubscriptionRecord() else null
                    }
                }
            }
        }

    override suspend fun findBestActiveLinkedPartnerSubscription(
        userId: UUID,
        now: Instant
    ): LinkedPartnerSubscriptionRecord? = withContext(Dispatchers.IO) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(ACTIVE_LINKED_PARTNER_SUBSCRIPTION_QUERY).use { statement ->
                statement.setTimestamp(1, Timestamp.from(now))
                statement.setTimestamp(2, Timestamp.from(now))
                statement.setObject(3, userId)
                statement.executeQuery().use { rs ->
                    if (!rs.next()) return@withContext null
                    LinkedPartnerSubscriptionRecord(
                        subscription = rs.toSubscriptionRecord(),
                        linkedPartnerUserId = rs.getObject("linked_partner_user_id", UUID::class.java),
                        partnerLinkId = rs.getObject("partner_link_id", UUID::class.java)
                    )
                }
            }
        }
    }

    override suspend fun findBestActiveStoredEntitlement(userId: UUID, now: Instant): StoredEntitlementRecord? =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection.prepareStatement(ACTIVE_STORED_ENTITLEMENT_QUERY).use { statement ->
                    statement.setObject(1, userId)
                    statement.setTimestamp(2, Timestamp.from(now))
                    statement.setTimestamp(3, Timestamp.from(now))
                    statement.executeQuery().use { rs ->
                        if (rs.next()) rs.toStoredEntitlementRecord() else null
                    }
                }
            }
        }

    override suspend fun consumeIntroNoteInventory(userId: UUID, now: Instant): Boolean =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection.prepareStatement(CONSUME_INTRO_NOTE_INVENTORY_QUERY).use { statement ->
                    statement.setObject(1, userId)
                    statement.setTimestamp(2, Timestamp.from(now))
                    statement.executeQuery().use { rs -> rs.next() }
                }
            }
        }

    private fun ResultSet.toSubscriptionRecord(): SubscriptionRecord =
        SubscriptionRecord(
            subscriptionId = getObject("subscription_id", UUID::class.java),
            userId = getObject("subscription_user_id", UUID::class.java),
            tier = getString("tier"),
            status = getString("status"),
            currentPeriodStart = getTimestamp("current_period_start").toInstant(),
            currentPeriodEnd = getTimestamp("current_period_end").toInstant()
        )

    private fun ResultSet.toStoredEntitlementRecord(): StoredEntitlementRecord =
        StoredEntitlementRecord(
            entitlementId = getObject("entitlement_id", UUID::class.java),
            userId = getObject("entitlement_user_id", UUID::class.java),
            tier = getString("tier"),
            source = getString("source"),
            sourceId = getObject("source_id", UUID::class.java),
            startsAt = getTimestamp("starts_at").toInstant(),
            endsAt = getTimestamp("ends_at")?.toInstant()
        )

    companion object {
        private val ACTIVE_STATUSES = "('active', 'grace')"

        private val ACTIVE_OWN_SUBSCRIPTION_QUERY = """
            SELECT s.id AS subscription_id,
                   s.user_id AS subscription_user_id,
                   s.tier,
                   s.status,
                   s.current_period_start,
                   s.current_period_end
            FROM subscriptions s
            JOIN users u
              ON u.id = s.user_id
             AND u.status = 'active'
             AND u.deleted_at IS NULL
            WHERE s.user_id = ?
              AND s.status IN $ACTIVE_STATUSES
              AND s.current_period_start <= ?
              AND s.current_period_end > ?
            ORDER BY
                CASE s.tier
                    WHEN 'select' THEN 2
                    WHEN 'social' THEN 1
                    ELSE 0
                END DESC,
                s.current_period_end DESC,
                s.id ASC
            LIMIT 1
        """.trimIndent()

        private val ACTIVE_LINKED_PARTNER_SUBSCRIPTION_QUERY = """
            SELECT s.id AS subscription_id,
                   s.user_id AS subscription_user_id,
                   s.tier,
                   s.status,
                   s.current_period_start,
                   s.current_period_end,
                   linked_user.id AS linked_partner_user_id,
                   upl.id AS partner_link_id
            FROM user_profile_links upl
            JOIN users linked_user
              ON linked_user.id = upl.linked_user_id
             AND linked_user.status = 'active'
             AND linked_user.deleted_at IS NULL
            JOIN subscriptions s
              ON s.user_id = linked_user.id
             AND s.status IN $ACTIVE_STATUSES
             AND s.current_period_start <= ?
             AND s.current_period_end > ?
            WHERE upl.user_id = ?
              AND upl.status = 'active'
              AND upl.unlinked_at IS NULL
            ORDER BY
                CASE s.tier
                    WHEN 'select' THEN 2
                    WHEN 'social' THEN 1
                    ELSE 0
                END DESC,
                s.current_period_end DESC,
                s.id ASC
            LIMIT 1
        """.trimIndent()

        private val ACTIVE_STORED_ENTITLEMENT_QUERY = """
            SELECT e.id AS entitlement_id,
                   e.user_id AS entitlement_user_id,
                   e.tier,
                   e.source,
                   e.source_id,
                   e.starts_at,
                   e.ends_at
            FROM entitlements e
            JOIN users u
              ON u.id = e.user_id
             AND u.status = 'active'
             AND u.deleted_at IS NULL
            WHERE e.user_id = ?
              AND e.status = 'active'
              AND e.starts_at <= ?
              AND (e.ends_at IS NULL OR e.ends_at > ?)
            ORDER BY
                CASE e.tier
                    WHEN 'select' THEN 2
                    WHEN 'social' THEN 1
                    ELSE 0
                END DESC,
                CASE e.source
                    WHEN 'admin' THEN 3
                    WHEN 'promo' THEN 2
                    WHEN 'own_subscription' THEN 1
                    ELSE 0
                END DESC,
                e.ends_at DESC NULLS FIRST,
                e.id ASC
            LIMIT 1
        """.trimIndent()

        private val CONSUME_INTRO_NOTE_INVENTORY_QUERY = """
            WITH candidate AS (
                SELECT id
                FROM premium_inventory
                WHERE user_id = ?
                  AND inventory_type = 'intro_note'
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
    }
}
