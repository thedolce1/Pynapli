package com.pynapli.server.notifications

import java.sql.Connection
import java.sql.ResultSet
import java.util.UUID

internal fun Connection.ensureNotificationPreferences(userId: UUID) {
    prepareStatement(
        """
        INSERT INTO notification_preferences (user_id)
        SELECT id
        FROM users
        WHERE id = ?
          AND deleted_at IS NULL
        ON CONFLICT (user_id) DO NOTHING
        """.trimIndent(),
    ).use { statement ->
        statement.setObject(1, userId)
        statement.executeUpdate()
    }
}

internal fun ResultSet.toNotificationPreferencesRecord(): NotificationPreferencesRecord =
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
        quietHoursTimezone = getString("quiet_hours_timezone"),
    )

internal fun ResultSet.toPushDeviceRecord(): PushDeviceRecord =
    PushDeviceRecord(
        deviceId = getObject("id", UUID::class.java),
        userId = getObject("user_id", UUID::class.java),
        platform = getString("platform"),
        notificationsEnabled = getBoolean("notifications_enabled"),
        lastSeenAt = getTimestamp("last_seen_at").toInstant(),
    )

internal fun ResultSet.toNotificationOutboxRecord(): NotificationOutboxRecord =
    NotificationOutboxRecord(
        notificationId = getObject("id", UUID::class.java),
        recipientUserId = getObject("recipient_user_id", UUID::class.java),
        kind = getString("kind"),
        status = getString("status"),
        createdAt = getTimestamp("created_at").toInstant(),
    )

internal fun ResultSet.toNotificationDeliveryRecord(): NotificationDeliveryRecord =
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
        createdAt = getTimestamp("created_at").toInstant(),
    )

internal fun ResultSet.toPushDeliveryTarget(): PushDeliveryTarget =
    PushDeliveryTarget(
        deviceId = getObject("id", UUID::class.java),
        userId = getObject("user_id", UUID::class.java),
        platform = getString("platform"),
        deviceToken = getString("device_token"),
    )
