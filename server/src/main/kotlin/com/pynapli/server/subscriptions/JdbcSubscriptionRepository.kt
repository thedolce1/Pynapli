package com.pynapli.server.subscriptions

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.ResultSet
import java.sql.Timestamp
import java.sql.Types
import java.time.Instant
import java.time.temporal.ChronoUnit
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

    override suspend fun listPlayStoreSubscriptionsDueForVerification(
        staleBefore: Instant,
        renewalWindowEnd: Instant,
        limit: Int
    ): List<PlayStoreSubscriptionVerificationCandidate> = withContext(Dispatchers.IO) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(LIST_PLAY_STORE_SUBSCRIPTIONS_DUE_FOR_VERIFICATION_QUERY).use { statement ->
                statement.setTimestamp(1, Timestamp.from(staleBefore))
                statement.setTimestamp(2, Timestamp.from(renewalWindowEnd))
                statement.setInt(3, limit.coerceIn(1, 500))
                statement.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(rs.toPlayStoreSubscriptionVerificationCandidate())
                        }
                    }
                }
            }
        }
    }

    override suspend fun findPlayStoreSubscriptionByPurchaseToken(
        purchaseToken: String
    ): PlayStoreSubscriptionVerificationCandidate? = withContext(Dispatchers.IO) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(FIND_PLAY_STORE_SUBSCRIPTION_BY_PURCHASE_TOKEN_QUERY).use { statement ->
                statement.setString(1, purchaseToken)
                statement.executeQuery().use { rs ->
                    if (rs.next()) rs.toPlayStoreSubscriptionVerificationCandidate() else null
                }
            }
        }
    }

    override suspend fun insertPlayStoreRtdnEvent(event: PlayStoreRtdnEventInsert): Boolean =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection.prepareStatement(INSERT_PLAY_STORE_RTDN_EVENT_QUERY).use { statement ->
                    statement.setObject(1, UUID.randomUUID())
                    statement.setString(2, event.messageId)
                    statement.setString(3, event.packageName)
                    statement.setString(4, event.eventType)
                    if (event.notificationType == null) {
                        statement.setNull(5, Types.INTEGER)
                    } else {
                        statement.setInt(5, event.notificationType)
                    }
                    statement.setString(6, event.purchaseToken)
                    statement.setString(7, event.productId)
                    if (event.eventTime == null) {
                        statement.setNull(8, Types.TIMESTAMP_WITH_TIMEZONE)
                    } else {
                        statement.setTimestamp(8, Timestamp.from(event.eventTime))
                    }
                    statement.setString(9, event.rawPayload)
                    statement.executeQuery().use { rs ->
                        rs.next()
                        rs.getBoolean(1)
                    }
                }
            }
        }

    override suspend fun updatePlayStoreRtdnEventStatus(
        messageId: String,
        processingStatus: String,
        errorCode: String?
    ): Unit = withContext(Dispatchers.IO) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(UPDATE_PLAY_STORE_RTDN_EVENT_STATUS_QUERY).use { statement ->
                statement.setString(1, processingStatus)
                statement.setString(2, errorCode)
                statement.setString(3, messageId)
                statement.executeUpdate()
            }
        }
    }

    override suspend fun upsertPendingPlayStoreSubscriptionPurchase(
        userId: UUID,
        productId: String,
        purchaseToken: String,
        tier: String,
        now: Instant
    ): SubscriptionRecord = withContext(Dispatchers.IO) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(UPSERT_PENDING_PLAY_STORE_PURCHASE_QUERY).use { statement ->
                statement.setObject(1, UUID.randomUUID())
                statement.setObject(2, userId)
                statement.setString(3, purchaseToken)
                statement.setString(4, productId)
                statement.setString(5, tier)
                statement.setTimestamp(6, Timestamp.from(now))
                statement.setTimestamp(7, Timestamp.from(now.plus(3, ChronoUnit.DAYS)))
                statement.executeQuery().use { rs ->
                    if (rs.next()) {
                        rs.toSubscriptionRecord()
                    } else {
                        throw SubscriptionException("PURCHASE_ALREADY_LINKED")
                    }
                }
            }
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
        verifiedAt: Instant
    ): SubscriptionRecord = withContext(Dispatchers.IO) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(UPSERT_VERIFIED_PLAY_STORE_PURCHASE_QUERY).use { statement ->
                statement.setObject(1, UUID.randomUUID())
                statement.setObject(2, userId)
                statement.setString(3, purchaseToken)
                statement.setString(4, productId)
                statement.setString(5, orderId)
                statement.setString(6, externalStatus)
                statement.setString(7, acknowledgementState)
                if (acknowledgedAt == null) {
                    statement.setNull(8, Types.TIMESTAMP_WITH_TIMEZONE)
                } else {
                    statement.setTimestamp(8, Timestamp.from(acknowledgedAt))
                }
                statement.setTimestamp(9, Timestamp.from(verifiedAt))
                statement.setString(10, tier)
                statement.setString(11, status)
                statement.setTimestamp(12, Timestamp.from(currentPeriodStart))
                statement.setTimestamp(13, Timestamp.from(currentPeriodEnd))
                statement.executeQuery().use { rs ->
                    if (rs.next()) {
                        rs.toSubscriptionRecord()
                    } else {
                        throw SubscriptionException("PURCHASE_ALREADY_LINKED")
                    }
                }
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

    private fun ResultSet.toPlayStoreSubscriptionVerificationCandidate(): PlayStoreSubscriptionVerificationCandidate =
        PlayStoreSubscriptionVerificationCandidate(
            userId = getObject("subscription_user_id", UUID::class.java),
            productId = getString("external_product_id"),
            purchaseToken = getString("external_subscription_id"),
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

        private val LIST_PLAY_STORE_SUBSCRIPTIONS_DUE_FOR_VERIFICATION_QUERY = """
            SELECT user_id AS subscription_user_id,
                   external_product_id,
                   external_subscription_id,
                   tier,
                   status,
                   current_period_start,
                   current_period_end,
                   last_verified_at
            FROM subscriptions
            WHERE platform = 'play_store'
              AND external_product_id IS NOT NULL
              AND status IN ('pending', 'active', 'grace', 'canceled')
              AND (
                  last_verified_at IS NULL
                  OR last_verified_at <= ?
                  OR status = 'pending'
                  OR current_period_end <= ?
              )
            ORDER BY
                CASE status
                    WHEN 'pending' THEN 0
                    WHEN 'grace' THEN 1
                    WHEN 'active' THEN 2
                    ELSE 3
                END ASC,
                last_verified_at ASC NULLS FIRST,
                current_period_end ASC,
                id ASC
            LIMIT ?
        """.trimIndent()

        private val FIND_PLAY_STORE_SUBSCRIPTION_BY_PURCHASE_TOKEN_QUERY = """
            SELECT user_id AS subscription_user_id,
                   external_product_id,
                   external_subscription_id,
                   tier,
                   status,
                   current_period_start,
                   current_period_end
            FROM subscriptions
            WHERE platform = 'play_store'
              AND external_subscription_id = ?
            LIMIT 1
        """.trimIndent()

        private val INSERT_PLAY_STORE_RTDN_EVENT_QUERY = """
            WITH inserted AS (
                INSERT INTO play_store_rtdn_events (
                    id,
                    message_id,
                    package_name,
                    event_type,
                    notification_type,
                    purchase_token,
                    product_id,
                    event_time,
                    raw_payload,
                    processing_status
                ) VALUES (
                    ?,
                    ?,
                    ?,
                    ?,
                    ?,
                    ?,
                    ?,
                    ?,
                    ?,
                    'received'
                )
                ON CONFLICT (message_id) DO NOTHING
                RETURNING 1
            )
            SELECT EXISTS(SELECT 1 FROM inserted)
        """.trimIndent()

        private val UPDATE_PLAY_STORE_RTDN_EVENT_STATUS_QUERY = """
            UPDATE play_store_rtdn_events
            SET processing_status = ?,
                error_code = ?,
                processed_at = now(),
                updated_at = now()
            WHERE message_id = ?
        """.trimIndent()

        private val UPSERT_PENDING_PLAY_STORE_PURCHASE_QUERY = """
            INSERT INTO subscriptions (
                id,
                user_id,
                platform,
                external_subscription_id,
                external_product_id,
                tier,
                status,
                current_period_start,
                current_period_end
            ) VALUES (
                ?,
                ?,
                'play_store',
                ?,
                ?,
                ?,
                'pending',
                ?,
                ?
            )
            ON CONFLICT (platform, external_subscription_id)
            DO UPDATE SET
                external_product_id = EXCLUDED.external_product_id,
                tier = EXCLUDED.tier,
                status = CASE
                    WHEN subscriptions.status IN ('active', 'grace') THEN subscriptions.status
                    ELSE 'pending'
                END,
                updated_at = now()
            WHERE subscriptions.user_id = EXCLUDED.user_id
            RETURNING id AS subscription_id,
                      user_id AS subscription_user_id,
                      tier,
                      status,
                      current_period_start,
                      current_period_end
        """.trimIndent()

        private val UPSERT_VERIFIED_PLAY_STORE_PURCHASE_QUERY = """
            INSERT INTO subscriptions (
                id,
                user_id,
                platform,
                external_subscription_id,
                external_product_id,
                external_order_id,
                external_status,
                acknowledgement_state,
                acknowledged_at,
                last_verified_at,
                tier,
                status,
                current_period_start,
                current_period_end
            ) VALUES (
                ?,
                ?,
                'play_store',
                ?,
                ?,
                ?,
                ?,
                ?,
                ?,
                ?,
                ?,
                ?,
                ?,
                ?
            )
            ON CONFLICT (platform, external_subscription_id)
            DO UPDATE SET
                external_product_id = EXCLUDED.external_product_id,
                external_order_id = COALESCE(EXCLUDED.external_order_id, subscriptions.external_order_id),
                external_status = EXCLUDED.external_status,
                acknowledgement_state = EXCLUDED.acknowledgement_state,
                acknowledged_at = COALESCE(EXCLUDED.acknowledged_at, subscriptions.acknowledged_at),
                last_verified_at = EXCLUDED.last_verified_at,
                tier = EXCLUDED.tier,
                status = EXCLUDED.status,
                current_period_start = EXCLUDED.current_period_start,
                current_period_end = EXCLUDED.current_period_end,
                updated_at = now()
            WHERE subscriptions.user_id = EXCLUDED.user_id
            RETURNING id AS subscription_id,
                      user_id AS subscription_user_id,
                      tier,
                      status,
                      current_period_start,
                      current_period_end
        """.trimIndent()
    }
}
