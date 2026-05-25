package com.pynapli.server.subscriptions

import com.pynapli.server.moderation.insertAdminAuditLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.sql.Connection
import java.sql.Timestamp
import java.sql.Types
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import javax.sql.DataSource

class JdbcSubscriptionRepository(
    private val dataSource: DataSource
) : SubscriptionRepository {
    override suspend fun findBestActiveEntitlement(userId: UUID, now: Instant): ActiveEntitlementRecord? =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection.prepareStatement(ACTIVE_ENTITLEMENT_QUERY).use { statement ->
                    statement.setObject(1, userId)
                    statement.setTimestamp(2, Timestamp.from(now))
                    statement.setTimestamp(3, Timestamp.from(now))
                    statement.setObject(4, userId)
                    statement.setTimestamp(5, Timestamp.from(now))
                    statement.setTimestamp(6, Timestamp.from(now))
                    statement.setTimestamp(7, Timestamp.from(now))
                    statement.setTimestamp(8, Timestamp.from(now))
                    statement.setObject(9, userId)
                    statement.executeQuery().use { rs ->
                        if (rs.next()) rs.toActiveEntitlementRecord() else null
                    }
                }
            }
        }

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

    override suspend fun listStoredEntitlementsForUser(userId: UUID, limit: Int): List<StoredEntitlementRecord> =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection.prepareStatement(LIST_STORED_ENTITLEMENTS_FOR_USER_QUERY).use { statement ->
                    statement.setObject(1, userId)
                    statement.setInt(2, limit.coerceIn(1, 100))
                    statement.executeQuery().use { rs ->
                        buildList {
                            while (rs.next()) {
                                add(rs.toStoredEntitlementRecord())
                            }
                        }
                    }
                }
            }
        }

    override suspend fun grantStoredEntitlement(
        userId: UUID,
        tier: String,
        source: String,
        startsAt: Instant,
        endsAt: Instant?,
        actorId: String,
        reason: String
    ): StoredEntitlementRecord? = withContext(Dispatchers.IO) {
        dataSource.connection.use { connection ->
            connection.runInTransaction {
                val record = grantStoredEntitlementRecord(
                    userId = userId,
                    tier = tier,
                    source = source,
                    startsAt = startsAt,
                    endsAt = endsAt
                ) ?: return@runInTransaction null
                insertAdminAuditLog(
                    actorId = actorId,
                    action = "subscription_entitlement.grant",
                    targetType = "entitlement",
                    targetId = record.entitlementId,
                    reason = reason,
                    metadataJson = buildJsonObject {
                        put("userId", userId.toString())
                        put("tier", tier)
                        put("source", source)
                        put("startsAt", startsAt.toString())
                        put("endsAt", endsAt?.toString())
                    }.toString()
                )
                record
            }
        }
    }

    override suspend fun revokeStoredEntitlement(
        entitlementId: UUID,
        actorId: String,
        reason: String
    ): StoredEntitlementRecord? = withContext(Dispatchers.IO) {
        dataSource.connection.use { connection ->
            connection.runInTransaction {
                val record = revokeStoredEntitlementRecord(entitlementId) ?: return@runInTransaction null
                insertAdminAuditLog(
                    actorId = actorId,
                    action = "subscription_entitlement.revoke",
                    targetType = "entitlement",
                    targetId = record.entitlementId,
                    reason = reason,
                    metadataJson = buildJsonObject {
                        put("userId", record.userId.toString())
                        put("tier", record.tier)
                        put("source", record.source)
                    }.toString()
                )
                record
            }
        }
    }

    override suspend fun consumeIntroNoteInventory(userId: UUID, now: Instant): Boolean =
        consumePremiumInventory(userId = userId, inventoryType = "intro_note", now = now)

    override suspend fun consumePineappleLikeInventory(userId: UUID, now: Instant): Boolean =
        consumePineappleLikeInventoryReturningId(userId, now) != null

    override suspend fun consumePineappleLikeInventoryReturningId(userId: UUID, now: Instant): UUID? =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection.consumePremiumInventoryRecordReturningId(userId, "pineapple_like", now)
            }
        }

    override suspend fun refundInventoryById(inventoryId: UUID, now: Instant): Boolean =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection.refundPremiumInventoryRecord(inventoryId, now)
            }
        }

    override suspend fun listPremiumInventoryBalances(
        userId: UUID,
        now: Instant
    ): List<PremiumInventoryBalanceRecord> = withContext(Dispatchers.IO) {
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
        now: Instant
    ): PremiumInventoryBalanceRecord? = withContext(Dispatchers.IO) {
        dataSource.connection.use { connection ->
            connection.runInTransaction {
                grantPremiumInventoryRecord(
                    userId = userId,
                    inventoryType = inventoryType,
                    quantity = quantity,
                    source = source,
                    expiresAt = expiresAt,
                    now = now
                )
                listPremiumInventoryBalanceRecord(userId, inventoryType, now)
            }
        }
    }

    private suspend fun consumePremiumInventory(userId: UUID, inventoryType: String, now: Instant): Boolean =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection.consumePremiumInventoryRecord(userId, inventoryType, now)
            }
        }

    override suspend fun activateBoost(
        userId: UUID,
        now: Instant,
        duration: Duration
    ): BoostActivationResult? = withContext(Dispatchers.IO) {
        dataSource.connection.use { connection ->
            connection.runInTransaction {
                findActiveBoostActivation(userId, now)?.let { active ->
                    return@runInTransaction BoostActivationResult(
                        activation = active,
                        consumedInventory = false
                    )
                }
                if (!consumePremiumInventoryRecord(userId, "boost", now)) {
                    return@runInTransaction null
                }
                BoostActivationResult(
                    activation = insertBoostActivation(userId, now, duration),
                    consumedInventory = true
                )
            }
        }
    }

    override suspend fun upsertDevSubscriptionPurchase(
        userId: UUID,
        productId: String,
        tier: String,
        startsAt: Instant,
        endsAt: Instant
    ): SubscriptionRecord = withContext(Dispatchers.IO) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(UPSERT_DEV_SUBSCRIPTION_PURCHASE_QUERY).use { statement ->
                statement.setObject(1, UUID.randomUUID())
                statement.setObject(2, userId)
                statement.setString(3, "dev:$userId:$productId")
                statement.setString(4, productId)
                statement.setString(5, tier)
                statement.setTimestamp(6, Timestamp.from(startsAt))
                statement.setTimestamp(7, Timestamp.from(endsAt))
                statement.setTimestamp(8, Timestamp.from(startsAt))
                statement.executeQuery().use { rs ->
                    rs.next()
                    rs.toSubscriptionRecord()
                }
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

    private fun Connection.grantStoredEntitlementRecord(
        userId: UUID,
        tier: String,
        source: String,
        startsAt: Instant,
        endsAt: Instant?
    ): StoredEntitlementRecord? =
        prepareStatement(GRANT_STORED_ENTITLEMENT_QUERY).use { statement ->
            statement.setObject(1, userId)
            statement.setObject(2, UUID.randomUUID())
            statement.setString(3, tier)
            statement.setString(4, source)
            statement.setTimestamp(5, Timestamp.from(startsAt))
            if (endsAt == null) {
                statement.setNull(6, Types.TIMESTAMP_WITH_TIMEZONE)
            } else {
                statement.setTimestamp(6, Timestamp.from(endsAt))
            }
            statement.executeQuery().use { rs ->
                if (rs.next()) rs.toStoredEntitlementRecord() else null
            }
        }

    private fun Connection.revokeStoredEntitlementRecord(entitlementId: UUID): StoredEntitlementRecord? =
        prepareStatement(REVOKE_STORED_ENTITLEMENT_QUERY).use { statement ->
            statement.setObject(1, entitlementId)
            statement.executeQuery().use { rs ->
                if (rs.next()) rs.toStoredEntitlementRecord() else null
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

    companion object {
        private val ACTIVE_STATUSES = "('active', 'grace')"

        private val ACTIVE_ENTITLEMENT_QUERY = """
            SELECT tier,
                   source,
                   source_subscription_id,
                   source_user_id,
                   linked_partner_user_id,
                   starts_at,
                   ends_at
            FROM (
                SELECT s.id AS stable_id,
                       s.id AS source_subscription_id,
                       s.user_id AS source_user_id,
                       NULL::uuid AS linked_partner_user_id,
                       s.tier,
                       'own_subscription' AS source,
                       s.current_period_start AS starts_at,
                       s.current_period_end AS ends_at,
                       CASE s.tier
                           WHEN 'select' THEN 2
                           WHEN 'social' THEN 1
                           ELSE 0
                       END AS tier_rank,
                       3 AS source_rank
                FROM subscriptions s
                JOIN users u
                  ON u.id = s.user_id
                 AND u.status = 'active'
                 AND u.deleted_at IS NULL
                WHERE s.user_id = ?
                  AND s.status IN $ACTIVE_STATUSES
                  AND s.current_period_start <= ?
                  AND s.current_period_end > ?

                UNION ALL

                SELECT e.id AS stable_id,
                       e.source_id AS source_subscription_id,
                       e.user_id AS source_user_id,
                       NULL::uuid AS linked_partner_user_id,
                       e.tier,
                       e.source,
                       e.starts_at AS starts_at,
                       e.ends_at AS ends_at,
                       CASE e.tier
                           WHEN 'select' THEN 2
                           WHEN 'social' THEN 1
                           ELSE 0
                       END AS tier_rank,
                       CASE e.source
                           WHEN 'own_subscription' THEN 3
                           WHEN 'admin' THEN 2
                           WHEN 'promo' THEN 1
                           WHEN 'linked_partner_benefit' THEN 0
                           ELSE -1
                       END AS source_rank
                FROM entitlements e
                JOIN users u
                  ON u.id = e.user_id
                 AND u.status = 'active'
                 AND u.deleted_at IS NULL
                WHERE e.user_id = ?
                  AND e.status = 'active'
                  AND e.starts_at <= ?
                  AND (e.ends_at IS NULL OR e.ends_at > ?)

                UNION ALL

                SELECT s.id AS stable_id,
                       s.id AS source_subscription_id,
                       linked_user.id AS source_user_id,
                       linked_user.id AS linked_partner_user_id,
                       s.tier,
                       'linked_partner_benefit' AS source,
                       s.current_period_start AS starts_at,
                       s.current_period_end AS ends_at,
                       CASE s.tier
                           WHEN 'select' THEN 2
                           WHEN 'social' THEN 1
                           ELSE 0
                       END AS tier_rank,
                       0 AS source_rank
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
            ) candidates
            ORDER BY tier_rank DESC,
                     source_rank DESC,
                     ends_at DESC NULLS FIRST,
                     stable_id ASC
            LIMIT 1
        """.trimIndent()

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
                   e.ends_at,
                   e.status
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

        private val LIST_STORED_ENTITLEMENTS_FOR_USER_QUERY = """
            SELECT e.id AS entitlement_id,
                   e.user_id AS entitlement_user_id,
                   e.tier,
                   e.source,
                   e.source_id,
                   e.starts_at,
                   e.ends_at,
                   e.status
            FROM entitlements e
            WHERE e.user_id = ?
            ORDER BY e.starts_at DESC,
                     e.created_at DESC,
                     e.id ASC
            LIMIT ?
        """.trimIndent()

        private val GRANT_STORED_ENTITLEMENT_QUERY = """
            WITH target_user AS (
                SELECT id
                FROM users
                WHERE id = ?
                  AND status = 'active'
                  AND deleted_at IS NULL
            )
            INSERT INTO entitlements (
                id,
                user_id,
                tier,
                source,
                source_id,
                starts_at,
                ends_at,
                status
            )
            SELECT ?,
                   target_user.id,
                   ?,
                   ?,
                   NULL,
                   ?,
                   ?,
                   'active'
            FROM target_user
            RETURNING id AS entitlement_id,
                      user_id AS entitlement_user_id,
                      tier,
                      source,
                      source_id,
                      starts_at,
                      ends_at,
                      status
        """.trimIndent()

        private val REVOKE_STORED_ENTITLEMENT_QUERY = """
            UPDATE entitlements
            SET status = 'revoked',
                updated_at = now()
            WHERE id = ?
              AND status = 'active'
            RETURNING id AS entitlement_id,
                      user_id AS entitlement_user_id,
                      tier,
                      source,
                      source_id,
                      starts_at,
                      ends_at,
                      status
        """.trimIndent()

        private val UPSERT_DEV_SUBSCRIPTION_PURCHASE_QUERY = """
            INSERT INTO subscriptions (
                id,
                user_id,
                platform,
                external_subscription_id,
                external_product_id,
                tier,
                status,
                current_period_start,
                current_period_end,
                last_verified_at
            )
            VALUES (?, ?, 'dev', ?, ?, ?, 'active', ?, ?, ?)
            ON CONFLICT (platform, external_subscription_id) DO UPDATE
            SET tier = EXCLUDED.tier,
                status = 'active',
                current_period_start = EXCLUDED.current_period_start,
                current_period_end = EXCLUDED.current_period_end,
                external_product_id = EXCLUDED.external_product_id,
                last_verified_at = EXCLUDED.last_verified_at,
                updated_at = now()
            RETURNING id AS subscription_id,
                      user_id AS subscription_user_id,
                      tier,
                      status,
                      current_period_start,
                      current_period_end
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
