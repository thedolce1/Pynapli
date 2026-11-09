package com.pynapli.server.notifications

import com.pynapli.server.db.inTransaction
import com.pynapli.server.db.withConnection
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

internal class JdbcNotificationOutboxActions(
    private val dataSource: DataSource,
) {
    suspend fun insertOutboxNotification(
        notification: NotificationOutboxInsert,
        notificationIdFactory: () -> UUID,
    ): NotificationOutboxRecord? =
        dataSource.withConnection { connection ->
            connection
                .prepareStatement(
                    """
                    INSERT INTO notification_outbox (
                        id,
                        recipient_user_id,
                        actor_user_id,
                        kind,
                        subject_id,
                        idempotency_key,
                        throttle_key,
                        title,
                        body,
                        payload
                    )
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                    ON CONFLICT (idempotency_key) DO NOTHING
                    RETURNING id, recipient_user_id, kind, status, created_at
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, notificationIdFactory())
                    statement.setObject(2, notification.recipientUserId)
                    statement.setObject(3, notification.actorUserId)
                    statement.setString(4, notification.kind)
                    statement.setObject(5, notification.subjectId)
                    statement.setString(6, notification.idempotencyKey)
                    statement.setString(7, notification.throttleKey)
                    statement.setString(8, notification.title)
                    statement.setString(9, notification.body)
                    statement.setString(10, notification.payloadJson)
                    statement.executeQuery().use { rs ->
                        if (!rs.next()) return@withConnection null
                        rs.toNotificationOutboxRecord()
                    }
                }
        }

    suspend fun hasRecentNotification(
        recipientUserId: UUID,
        kind: String,
        throttleKey: String,
        since: Instant,
    ): Boolean =
        dataSource.withConnection { connection ->
            connection
                .prepareStatement(
                    """
                    SELECT EXISTS (
                        SELECT 1
                        FROM notification_outbox
                        WHERE recipient_user_id = ?
                          AND kind = ?
                          AND throttle_key = ?
                          AND created_at >= ?
                          AND status <> 'failed'
                    )
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, recipientUserId)
                    statement.setString(2, kind)
                    statement.setString(3, throttleKey)
                    statement.setTimestamp(4, Timestamp.from(since))
                    statement.executeQuery().use { rs ->
                        rs.next() && rs.getBoolean(1)
                    }
                }
        }

    suspend fun claimPendingNotifications(limit: Int): List<NotificationDeliveryRecord> =
        dataSource.withConnection { connection ->
            connection.inTransaction {
                connection
                    .prepareStatement(
                        """
                        WITH claimed AS (
                            SELECT id
                            FROM notification_outbox
                            WHERE status = 'pending'
                              AND available_at <= now()
                            ORDER BY available_at ASC, created_at ASC, id ASC
                            LIMIT ?
                            FOR UPDATE SKIP LOCKED
                        )
                        UPDATE notification_outbox n
                        SET status = 'processing',
                            attempt_count = n.attempt_count + 1,
                            updated_at = now()
                        FROM claimed
                        WHERE n.id = claimed.id
                        RETURNING n.id,
                                  n.recipient_user_id,
                                  n.actor_user_id,
                                  n.kind,
                                  n.subject_id,
                                  n.title,
                                  n.body,
                                  n.payload::text AS payload_json,
                                  n.attempt_count,
                                  n.created_at
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setInt(1, limit.coerceIn(1, 250))
                        statement.executeQuery().use { rs ->
                            buildList {
                                while (rs.next()) add(rs.toNotificationDeliveryRecord())
                            }
                        }
                    }
            }
        }

    suspend fun requeueStaleProcessingNotifications(
        staleBefore: Instant,
        limit: Int,
    ): Int =
        dataSource.withConnection { connection ->
            connection.inTransaction {
                connection
                    .prepareStatement(
                        """
                        WITH stale AS (
                            SELECT id
                            FROM notification_outbox
                            WHERE status = 'processing'
                              AND updated_at <= ?
                            ORDER BY updated_at ASC, id ASC
                            LIMIT ?
                            FOR UPDATE SKIP LOCKED
                        )
                        UPDATE notification_outbox n
                        SET status = 'pending',
                            available_at = now(),
                            last_error = ?,
                            updated_at = now()
                        FROM stale
                        WHERE n.id = stale.id
                        RETURNING n.id
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setTimestamp(1, Timestamp.from(staleBefore))
                        statement.setInt(2, limit.coerceIn(1, 250))
                        statement.setString(3, STALE_PROCESSING_RECOVERED)
                        statement.executeQuery().use { rs ->
                            var count = 0
                            while (rs.next()) count++
                            count
                        }
                    }
            }
        }

    suspend fun markNotificationSent(notificationId: UUID): Boolean =
        updateProcessingNotification(
            notificationId = notificationId,
            status = "sent",
            sentAt = Instant.now(),
        )

    suspend fun markNotificationSkipped(
        notificationId: UUID,
        reason: String,
    ): Boolean =
        updateProcessingNotification(
            notificationId = notificationId,
            status = "skipped",
            failedAt = Instant.now(),
            lastError = reason.take(MAX_NOTIFICATION_ERROR_LENGTH),
        )

    suspend fun markNotificationRetry(
        notificationId: UUID,
        retryAt: Instant,
        error: String,
    ): Boolean =
        dataSource.withConnection { connection ->
            connection
                .prepareStatement(
                    """
                    UPDATE notification_outbox
                    SET status = 'pending',
                        available_at = ?,
                        last_error = ?,
                        updated_at = now()
                    WHERE id = ?
                      AND status = 'processing'
                    """.trimIndent(),
                ).use { statement ->
                    statement.setTimestamp(1, Timestamp.from(retryAt))
                    statement.setString(2, error.take(MAX_NOTIFICATION_ERROR_LENGTH))
                    statement.setObject(3, notificationId)
                    statement.executeUpdate() > 0
                }
        }

    suspend fun markNotificationFailed(
        notificationId: UUID,
        error: String,
    ): Boolean =
        updateProcessingNotification(
            notificationId = notificationId,
            status = "failed",
            failedAt = Instant.now(),
            lastError = error.take(MAX_NOTIFICATION_ERROR_LENGTH),
        )

    private suspend fun updateProcessingNotification(
        notificationId: UUID,
        status: String,
        sentAt: Instant? = null,
        failedAt: Instant? = null,
        lastError: String? = null,
    ): Boolean =
        dataSource.withConnection { connection ->
            connection
                .prepareStatement(
                    """
                    UPDATE notification_outbox
                    SET status = ?,
                        sent_at = COALESCE(?::timestamptz, sent_at),
                        failed_at = COALESCE(?::timestamptz, failed_at),
                        last_error = COALESCE(?::text, last_error),
                        updated_at = now()
                    WHERE id = ?
                      AND status = 'processing'
                    """.trimIndent(),
                ).use { statement ->
                    statement.setString(1, status)
                    statement.setTimestamp(2, sentAt?.let { Timestamp.from(it) })
                    statement.setTimestamp(3, failedAt?.let { Timestamp.from(it) })
                    statement.setString(4, lastError)
                    statement.setObject(5, notificationId)
                    statement.executeUpdate() > 0
                }
        }

    private companion object {
        private const val MAX_NOTIFICATION_ERROR_LENGTH = 1_000
        private const val STALE_PROCESSING_RECOVERED = "STALE_PROCESSING_RECOVERED"
    }
}
