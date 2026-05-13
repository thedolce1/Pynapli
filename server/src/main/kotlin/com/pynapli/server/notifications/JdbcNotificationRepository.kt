package com.pynapli.server.notifications

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

class JdbcNotificationRepository(
    private val dataSource: DataSource
) : NotificationRepository {
    override suspend fun getPreferences(userId: UUID): NotificationPreferencesRecord? = withContext(Dispatchers.IO) {
        dataSource.connection.use { connection ->
            connection.ensurePreferences(userId)
            connection.prepareStatement(PREFERENCES_QUERY).use { statement ->
                statement.setObject(1, userId)
                statement.executeQuery().use { rs ->
                    if (!rs.next()) return@withContext null
                    rs.toNotificationPreferencesRecord()
                }
            }
        }
    }

    override suspend fun getDeliveryGate(userId: UUID): NotificationDeliveryGate? = withContext(Dispatchers.IO) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                WITH ensured_preferences AS (
                    INSERT INTO notification_preferences (user_id)
                    SELECT id
                    FROM users
                    WHERE id = ?
                      AND deleted_at IS NULL
                    ON CONFLICT (user_id) DO NOTHING
                    RETURNING user_id
                )
                SELECT np.user_id,
                       np.detail_level,
                       np.messages_enabled,
                       np.matches_enabled,
                       np.activity_enabled,
                       np.flirts_enabled,
                       np.album_requests_enabled,
                       np.partner_invites_enabled,
                       np.system_safety_enabled,
                       np.quiet_hours_enabled,
                       np.quiet_hours_start_minute,
                       np.quiet_hours_end_minute,
                       np.quiet_hours_timezone,
                       COUNT(pd.id) FILTER (
                           WHERE pd.disabled_at IS NULL
                             AND pd.notifications_enabled = true
                       ) AS active_device_count
                FROM notification_preferences np
                LEFT JOIN push_devices pd
                  ON pd.user_id = np.user_id
                WHERE np.user_id = ?
                GROUP BY np.user_id,
                         np.detail_level,
                         np.messages_enabled,
                         np.matches_enabled,
                         np.activity_enabled,
                         np.flirts_enabled,
                         np.album_requests_enabled,
                         np.partner_invites_enabled,
                         np.system_safety_enabled,
                         np.quiet_hours_enabled,
                         np.quiet_hours_start_minute,
                         np.quiet_hours_end_minute,
                         np.quiet_hours_timezone
                """.trimIndent()
            ).use { statement ->
                statement.setObject(1, userId)
                statement.setObject(2, userId)
                statement.executeQuery().use { rs ->
                    if (!rs.next()) return@withContext null
                    NotificationDeliveryGate(
                        preferences = rs.toNotificationPreferencesRecord(),
                        activeDeviceCount = rs.getInt("active_device_count")
                    )
                }
            }
        }
    }

    override suspend fun updatePreferences(
        userId: UUID,
        update: NotificationPreferencesUpdate
    ): NotificationPreferencesRecord? = withContext(Dispatchers.IO) {
        dataSource.connection.use { connection ->
            connection.ensurePreferences(userId)
            connection.prepareStatement(
                """
                UPDATE notification_preferences
                SET detail_level = COALESCE(?::text, detail_level),
                    messages_enabled = COALESCE(?::boolean, messages_enabled),
                    matches_enabled = COALESCE(?::boolean, matches_enabled),
                    activity_enabled = COALESCE(?::boolean, activity_enabled),
                    flirts_enabled = COALESCE(?::boolean, flirts_enabled),
                    album_requests_enabled = COALESCE(?::boolean, album_requests_enabled),
                    partner_invites_enabled = COALESCE(?::boolean, partner_invites_enabled),
                    system_safety_enabled = COALESCE(?::boolean, system_safety_enabled),
                    quiet_hours_enabled = COALESCE(?::boolean, quiet_hours_enabled),
                    quiet_hours_start_minute = COALESCE(?::integer, quiet_hours_start_minute),
                    quiet_hours_end_minute = COALESCE(?::integer, quiet_hours_end_minute),
                    quiet_hours_timezone = COALESCE(?::text, quiet_hours_timezone),
                    updated_at = now()
                WHERE user_id = ?
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, update.detailLevel)
                statement.setObject(2, update.messagesEnabled)
                statement.setObject(3, update.matchesEnabled)
                statement.setObject(4, update.activityEnabled)
                statement.setObject(5, update.flirtsEnabled)
                statement.setObject(6, update.albumRequestsEnabled)
                statement.setObject(7, update.partnerInvitesEnabled)
                statement.setObject(8, update.systemSafetyEnabled)
                statement.setObject(9, update.quietHoursEnabled)
                statement.setObject(10, update.quietHoursStartMinute)
                statement.setObject(11, update.quietHoursEndMinute)
                statement.setString(12, update.quietHoursTimezone)
                statement.setObject(13, userId)
                if (statement.executeUpdate() == 0) return@withContext null
            }
        }

        getPreferences(userId)
    }

    override suspend fun upsertPushDevice(
        userId: UUID,
        registration: PushDeviceRegistration,
        deviceIdFactory: () -> UUID
    ): PushDeviceRecord = withContext(Dispatchers.IO) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO push_devices (
                    id,
                    user_id,
                    platform,
                    device_token,
                    app_version,
                    locale,
                    timezone,
                    notifications_enabled
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (platform, device_token) DO UPDATE
                SET user_id = EXCLUDED.user_id,
                    app_version = EXCLUDED.app_version,
                    locale = EXCLUDED.locale,
                    timezone = EXCLUDED.timezone,
                    notifications_enabled = EXCLUDED.notifications_enabled,
                    disabled_at = NULL,
                    last_seen_at = now(),
                    updated_at = now()
                RETURNING id, user_id, platform, notifications_enabled, last_seen_at
                """.trimIndent()
            ).use { statement ->
                statement.setObject(1, deviceIdFactory())
                statement.setObject(2, userId)
                statement.setString(3, registration.platform)
                statement.setString(4, registration.deviceToken)
                statement.setString(5, registration.appVersion)
                statement.setString(6, registration.locale)
                statement.setString(7, registration.timezone)
                statement.setBoolean(8, registration.notificationsEnabled)
                statement.executeQuery().use { rs ->
                    require(rs.next()) { "push device upsert returned no rows" }
                    rs.toPushDeviceRecord()
                }
            }
        }
    }

    override suspend fun unregisterPushDevice(
        userId: UUID,
        platform: String,
        deviceToken: String
    ): Boolean = withContext(Dispatchers.IO) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                UPDATE push_devices
                SET disabled_at = now(),
                    notifications_enabled = false,
                    updated_at = now()
                WHERE user_id = ?
                  AND platform = ?
                  AND device_token = ?
                  AND disabled_at IS NULL
                """.trimIndent()
            ).use { statement ->
                statement.setObject(1, userId)
                statement.setString(2, platform)
                statement.setString(3, deviceToken)
                statement.executeUpdate() > 0
            }
        }
    }

    override suspend fun insertOutboxNotification(
        notification: NotificationOutboxInsert,
        notificationIdFactory: () -> UUID
    ): NotificationOutboxRecord? = withContext(Dispatchers.IO) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
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
                """.trimIndent()
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
                    if (!rs.next()) return@withContext null
                    rs.toNotificationOutboxRecord()
                }
            }
        }
    }

    override suspend fun hasRecentNotification(
        recipientUserId: UUID,
        kind: String,
        throttleKey: String,
        since: Instant
    ): Boolean = withContext(Dispatchers.IO) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
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
                """.trimIndent()
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
    }

    override suspend fun claimPendingNotifications(limit: Int): List<NotificationDeliveryRecord> =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection.withTransaction {
                    connection.prepareStatement(
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
                        """.trimIndent()
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
        }

    override suspend fun requeueStaleProcessingNotifications(
        staleBefore: Instant,
        limit: Int
    ): Int = withContext(Dispatchers.IO) {
        dataSource.connection.use { connection ->
            connection.withTransaction {
                connection.prepareStatement(
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
                    """.trimIndent()
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
    }

    override suspend fun listActivePushTargets(userId: UUID): List<PushDeliveryTarget> =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection.prepareStatement(
                    """
                    SELECT id, user_id, platform, device_token
                    FROM push_devices
                    WHERE user_id = ?
                      AND disabled_at IS NULL
                      AND notifications_enabled = true
                    ORDER BY last_seen_at DESC, id ASC
                    """.trimIndent()
                ).use { statement ->
                    statement.setObject(1, userId)
                    statement.executeQuery().use { rs ->
                        buildList {
                            while (rs.next()) add(rs.toPushDeliveryTarget())
                        }
                    }
                }
            }
        }

    override suspend fun markNotificationSent(notificationId: UUID): Boolean =
        updateProcessingNotification(
            notificationId = notificationId,
            status = "sent",
            sentAt = Instant.now()
        )

    override suspend fun markNotificationSkipped(notificationId: UUID, reason: String): Boolean =
        updateProcessingNotification(
            notificationId = notificationId,
            status = "skipped",
            failedAt = Instant.now(),
            lastError = reason.take(MAX_NOTIFICATION_ERROR_LENGTH)
        )

    override suspend fun markNotificationRetry(notificationId: UUID, retryAt: Instant, error: String): Boolean =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection.prepareStatement(
                    """
                    UPDATE notification_outbox
                    SET status = 'pending',
                        available_at = ?,
                        last_error = ?,
                        updated_at = now()
                    WHERE id = ?
                      AND status = 'processing'
                    """.trimIndent()
                ).use { statement ->
                    statement.setTimestamp(1, Timestamp.from(retryAt))
                    statement.setString(2, error.take(MAX_NOTIFICATION_ERROR_LENGTH))
                    statement.setObject(3, notificationId)
                    statement.executeUpdate() > 0
                }
            }
        }

    override suspend fun markNotificationFailed(notificationId: UUID, error: String): Boolean =
        updateProcessingNotification(
            notificationId = notificationId,
            status = "failed",
            failedAt = Instant.now(),
            lastError = error.take(MAX_NOTIFICATION_ERROR_LENGTH)
        )

    private suspend fun updateProcessingNotification(
        notificationId: UUID,
        status: String,
        sentAt: Instant? = null,
        failedAt: Instant? = null,
        lastError: String? = null
    ): Boolean = withContext(Dispatchers.IO) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                UPDATE notification_outbox
                SET status = ?,
                    sent_at = COALESCE(?::timestamptz, sent_at),
                    failed_at = COALESCE(?::timestamptz, failed_at),
                    last_error = COALESCE(?::text, last_error),
                    updated_at = now()
                WHERE id = ?
                  AND status = 'processing'
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, status)
                statement.setTimestamp(2, sentAt?.let { Timestamp.from(it) })
                statement.setTimestamp(3, failedAt?.let { Timestamp.from(it) })
                statement.setString(4, lastError)
                statement.setObject(5, notificationId)
                statement.executeUpdate() > 0
            }
        }
    }

    private fun Connection.ensurePreferences(userId: UUID) {
        prepareStatement(
            """
            INSERT INTO notification_preferences (user_id)
            SELECT id
            FROM users
            WHERE id = ?
              AND deleted_at IS NULL
            ON CONFLICT (user_id) DO NOTHING
            """.trimIndent()
        ).use { statement ->
            statement.setObject(1, userId)
            statement.executeUpdate()
        }
    }

    private fun ResultSet.toNotificationPreferencesRecord(): NotificationPreferencesRecord =
        NotificationPreferencesRecord(
            userId = getObject("user_id", UUID::class.java),
            detailLevel = getString("detail_level"),
            messagesEnabled = getBoolean("messages_enabled"),
            matchesEnabled = getBoolean("matches_enabled"),
            activityEnabled = getBoolean("activity_enabled"),
            flirtsEnabled = getBoolean("flirts_enabled"),
            albumRequestsEnabled = getBoolean("album_requests_enabled"),
            partnerInvitesEnabled = getBoolean("partner_invites_enabled"),
            systemSafetyEnabled = getBoolean("system_safety_enabled"),
            quietHoursEnabled = getBoolean("quiet_hours_enabled"),
            quietHoursStartMinute = getInt("quiet_hours_start_minute"),
            quietHoursEndMinute = getInt("quiet_hours_end_minute"),
            quietHoursTimezone = getString("quiet_hours_timezone")
        )

    private fun ResultSet.toPushDeviceRecord(): PushDeviceRecord =
        PushDeviceRecord(
            deviceId = getObject("id", UUID::class.java),
            userId = getObject("user_id", UUID::class.java),
            platform = getString("platform"),
            notificationsEnabled = getBoolean("notifications_enabled"),
            lastSeenAt = getTimestamp("last_seen_at").toInstant()
        )

    private fun ResultSet.toNotificationOutboxRecord(): NotificationOutboxRecord =
        NotificationOutboxRecord(
            notificationId = getObject("id", UUID::class.java),
            recipientUserId = getObject("recipient_user_id", UUID::class.java),
            kind = getString("kind"),
            status = getString("status"),
            createdAt = getTimestamp("created_at").toInstant()
        )

    private fun ResultSet.toNotificationDeliveryRecord(): NotificationDeliveryRecord =
        NotificationDeliveryRecord(
            notificationId = getObject("id", UUID::class.java),
            recipientUserId = getObject("recipient_user_id", UUID::class.java),
            actorUserId = getObject("actor_user_id", UUID::class.java),
            kind = getString("kind"),
            subjectId = getObject("subject_id", UUID::class.java),
            title = getString("title"),
            body = getString("body"),
            payloadJson = getString("payload_json"),
            attemptCount = getInt("attempt_count"),
            createdAt = getTimestamp("created_at").toInstant()
        )

    private fun ResultSet.toPushDeliveryTarget(): PushDeliveryTarget =
        PushDeliveryTarget(
            deviceId = getObject("id", UUID::class.java),
            userId = getObject("user_id", UUID::class.java),
            platform = getString("platform"),
            deviceToken = getString("device_token")
        )

    private fun <T> Connection.withTransaction(block: () -> T): T {
        val previousAutoCommit = autoCommit
        autoCommit = false
        try {
            val result = block()
            commit()
            return result
        } catch (error: Throwable) {
            rollback()
            throw error
        } finally {
            autoCommit = previousAutoCommit
        }
    }

    companion object {
        private const val MAX_NOTIFICATION_ERROR_LENGTH = 1_000
        private const val STALE_PROCESSING_RECOVERED = "STALE_PROCESSING_RECOVERED"

        private val PREFERENCES_QUERY = """
            SELECT user_id,
                   detail_level,
                   messages_enabled,
                   matches_enabled,
                   activity_enabled,
                   flirts_enabled,
                   album_requests_enabled,
                   partner_invites_enabled,
                   system_safety_enabled,
                   quiet_hours_enabled,
                   quiet_hours_start_minute,
                   quiet_hours_end_minute,
                   quiet_hours_timezone
            FROM notification_preferences
            WHERE user_id = ?
        """.trimIndent()
    }
}
