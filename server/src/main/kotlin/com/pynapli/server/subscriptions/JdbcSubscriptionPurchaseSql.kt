package com.pynapli.server.subscriptions

import java.sql.Connection
import java.sql.Timestamp
import java.sql.Types
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

internal fun Connection.upsertDevSubscriptionPurchaseRecord(
    userId: UUID,
    productId: String,
    tier: String,
    startsAt: Instant,
    endsAt: Instant,
): SubscriptionRecord =
    prepareStatement(UPSERT_DEV_SUBSCRIPTION_PURCHASE_QUERY).use { statement ->
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

internal fun Connection.listPlayStoreSubscriptionsDueForVerificationRecords(
    staleBefore: Instant,
    renewalWindowEnd: Instant,
    limit: Int,
): List<PlayStoreSubscriptionVerificationCandidate> =
    prepareStatement(LIST_PLAY_STORE_SUBSCRIPTIONS_DUE_FOR_VERIFICATION_QUERY).use { statement ->
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

internal fun Connection.findPlayStoreSubscriptionByPurchaseTokenRecord(
    purchaseToken: String,
): PlayStoreSubscriptionVerificationCandidate? =
    prepareStatement(FIND_PLAY_STORE_SUBSCRIPTION_BY_PURCHASE_TOKEN_QUERY).use { statement ->
        statement.setString(1, purchaseToken)
        statement.executeQuery().use { rs ->
            if (rs.next()) rs.toPlayStoreSubscriptionVerificationCandidate() else null
        }
    }

internal fun Connection.insertPlayStoreRtdnEventRecord(event: PlayStoreRtdnEventInsert): Boolean =
    prepareStatement(INSERT_PLAY_STORE_RTDN_EVENT_QUERY).use { statement ->
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

internal fun Connection.updatePlayStoreRtdnEventStatusRecord(
    messageId: String,
    processingStatus: String,
    errorCode: String?,
) {
    prepareStatement(UPDATE_PLAY_STORE_RTDN_EVENT_STATUS_QUERY).use { statement ->
        statement.setString(1, processingStatus)
        statement.setString(2, errorCode)
        statement.setString(3, messageId)
        statement.executeUpdate()
    }
}

internal fun Connection.upsertPendingPlayStoreSubscriptionPurchaseRecord(
    userId: UUID,
    productId: String,
    purchaseToken: String,
    tier: String,
    now: Instant,
): SubscriptionRecord =
    prepareStatement(UPSERT_PENDING_PLAY_STORE_PURCHASE_QUERY).use { statement ->
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

internal fun Connection.upsertVerifiedPlayStoreSubscriptionPurchaseRecord(
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
    prepareStatement(UPSERT_VERIFIED_PLAY_STORE_PURCHASE_QUERY).use { statement ->
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

private val UPSERT_DEV_SUBSCRIPTION_PURCHASE_QUERY =
    """
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

private val LIST_PLAY_STORE_SUBSCRIPTIONS_DUE_FOR_VERIFICATION_QUERY =
    """
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

private val FIND_PLAY_STORE_SUBSCRIPTION_BY_PURCHASE_TOKEN_QUERY =
    """
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

private val INSERT_PLAY_STORE_RTDN_EVENT_QUERY =
    """
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
        -- A redelivered message is processed again when the first attempt asked for a retry
        -- (verification was unavailable) or died mid-way (still 'received' minutes later); Pub/Sub
        -- redelivers exactly because the earlier attempt didn't succeed. Finished events and a
        -- delivery that is still being handled stay duplicates.
        ON CONFLICT (message_id) DO UPDATE
        SET processing_status = 'received',
            error_code = NULL,
            updated_at = now()
        WHERE play_store_rtdn_events.processing_status = 'retry'
           OR (
               play_store_rtdn_events.processing_status = 'received'
               AND play_store_rtdn_events.updated_at < now() - INTERVAL '2 minutes'
           )
        RETURNING 1
    )
    SELECT EXISTS(SELECT 1 FROM inserted)
    """.trimIndent()

private val UPDATE_PLAY_STORE_RTDN_EVENT_STATUS_QUERY =
    """
    UPDATE play_store_rtdn_events
    SET processing_status = ?,
        error_code = ?,
        processed_at = now(),
        updated_at = now()
    WHERE message_id = ?
    """.trimIndent()

private val UPSERT_PENDING_PLAY_STORE_PURCHASE_QUERY =
    """
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

private val UPSERT_VERIFIED_PLAY_STORE_PURCHASE_QUERY =
    """
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
