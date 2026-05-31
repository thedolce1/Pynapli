package com.pynapli.server.subscriptions

import java.sql.Connection
import java.sql.Timestamp
import java.sql.Types
import java.time.Instant
import java.util.UUID

internal fun Connection.loadBestActiveEntitlement(
    userId: UUID,
    now: Instant,
): ActiveEntitlementRecord? =
    prepareStatement(ACTIVE_ENTITLEMENT_QUERY).use { statement ->
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

internal fun Connection.loadBestActiveOwnSubscription(
    userId: UUID,
    now: Instant,
): SubscriptionRecord? =
    prepareStatement(ACTIVE_OWN_SUBSCRIPTION_QUERY).use { statement ->
        statement.setObject(1, userId)
        statement.setTimestamp(2, Timestamp.from(now))
        statement.setTimestamp(3, Timestamp.from(now))
        statement.executeQuery().use { rs ->
            if (rs.next()) rs.toSubscriptionRecord() else null
        }
    }

internal fun Connection.loadBestActiveLinkedPartnerSubscription(
    userId: UUID,
    now: Instant,
): LinkedPartnerSubscriptionRecord? =
    prepareStatement(ACTIVE_LINKED_PARTNER_SUBSCRIPTION_QUERY).use { statement ->
        statement.setTimestamp(1, Timestamp.from(now))
        statement.setTimestamp(2, Timestamp.from(now))
        statement.setObject(3, userId)
        statement.executeQuery().use { rs ->
            if (!rs.next()) return null
            LinkedPartnerSubscriptionRecord(
                subscription = rs.toSubscriptionRecord(),
                linkedPartnerUserId = rs.getObject("linked_partner_user_id", UUID::class.java),
                partnerLinkId = rs.getObject("partner_link_id", UUID::class.java),
            )
        }
    }

internal fun Connection.loadBestActiveStoredEntitlement(
    userId: UUID,
    now: Instant,
): StoredEntitlementRecord? =
    prepareStatement(ACTIVE_STORED_ENTITLEMENT_QUERY).use { statement ->
        statement.setObject(1, userId)
        statement.setTimestamp(2, Timestamp.from(now))
        statement.setTimestamp(3, Timestamp.from(now))
        statement.executeQuery().use { rs ->
            if (rs.next()) rs.toStoredEntitlementRecord() else null
        }
    }

internal fun Connection.listStoredEntitlementRecordsForUser(
    userId: UUID,
    limit: Int,
): List<StoredEntitlementRecord> =
    prepareStatement(LIST_STORED_ENTITLEMENTS_FOR_USER_QUERY).use { statement ->
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

internal fun Connection.grantStoredEntitlementRecord(
    userId: UUID,
    tier: String,
    source: String,
    startsAt: Instant,
    endsAt: Instant?,
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

internal fun Connection.revokeStoredEntitlementRecord(entitlementId: UUID): StoredEntitlementRecord? =
    prepareStatement(REVOKE_STORED_ENTITLEMENT_QUERY).use { statement ->
        statement.setObject(1, entitlementId)
        statement.executeQuery().use { rs ->
            if (rs.next()) rs.toStoredEntitlementRecord() else null
        }
    }

private val ACTIVE_STATUSES = "('active', 'grace')"

private val ACTIVE_ENTITLEMENT_QUERY =
    """
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

private val ACTIVE_OWN_SUBSCRIPTION_QUERY =
    """
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

private val ACTIVE_LINKED_PARTNER_SUBSCRIPTION_QUERY =
    """
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

private val ACTIVE_STORED_ENTITLEMENT_QUERY =
    """
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

private val LIST_STORED_ENTITLEMENTS_FOR_USER_QUERY =
    """
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

private val GRANT_STORED_ENTITLEMENT_QUERY =
    """
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

private val REVOKE_STORED_ENTITLEMENT_QUERY =
    """
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
