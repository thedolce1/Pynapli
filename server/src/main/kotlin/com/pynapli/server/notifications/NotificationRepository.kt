package com.pynapli.server.notifications

import java.time.Instant
import java.util.UUID

interface NotificationRepository {
    suspend fun getPreferences(userId: UUID): NotificationPreferencesRecord?

    suspend fun getDeliveryGate(userId: UUID): NotificationDeliveryGate?

    suspend fun updatePreferences(
        userId: UUID,
        update: NotificationPreferencesUpdate
    ): NotificationPreferencesRecord?

    suspend fun upsertPushDevice(
        userId: UUID,
        registration: PushDeviceRegistration,
        deviceIdFactory: () -> UUID
    ): PushDeviceRecord

    suspend fun unregisterPushDevice(
        userId: UUID,
        platform: String,
        deviceToken: String
    ): Boolean

    suspend fun insertOutboxNotification(
        notification: NotificationOutboxInsert,
        notificationIdFactory: () -> UUID
    ): NotificationOutboxRecord?

    suspend fun hasRecentNotification(
        recipientUserId: UUID,
        kind: String,
        throttleKey: String,
        since: Instant
    ): Boolean

    suspend fun claimPendingNotifications(limit: Int): List<NotificationDeliveryRecord>

    suspend fun requeueStaleProcessingNotifications(
        staleBefore: Instant,
        limit: Int
    ): Int

    suspend fun listActivePushTargets(userId: UUID): List<PushDeliveryTarget>

    suspend fun markNotificationSent(notificationId: UUID): Boolean

    suspend fun markNotificationSkipped(notificationId: UUID, reason: String): Boolean

    suspend fun markNotificationRetry(notificationId: UUID, retryAt: Instant, error: String): Boolean

    suspend fun markNotificationFailed(notificationId: UUID, error: String): Boolean
}

data class NotificationPreferencesRecord(
    val userId: UUID,
    val detailLevel: String,
    val messagesEnabled: Boolean,
    val matchesEnabled: Boolean,
    val activityEnabled: Boolean,
    val flirtsEnabled: Boolean,
    val albumRequestsEnabled: Boolean,
    val partnerInvitesEnabled: Boolean,
    val systemSafetyEnabled: Boolean,
    val quietHoursEnabled: Boolean,
    val quietHoursStartMinute: Int,
    val quietHoursEndMinute: Int,
    val quietHoursTimezone: String
)

data class NotificationPreferencesUpdate(
    val detailLevel: String?,
    val messagesEnabled: Boolean?,
    val matchesEnabled: Boolean?,
    val activityEnabled: Boolean?,
    val flirtsEnabled: Boolean?,
    val albumRequestsEnabled: Boolean?,
    val partnerInvitesEnabled: Boolean?,
    val systemSafetyEnabled: Boolean?,
    val quietHoursEnabled: Boolean?,
    val quietHoursStartMinute: Int?,
    val quietHoursEndMinute: Int?,
    val quietHoursTimezone: String?
)

data class PushDeviceRegistration(
    val platform: String,
    val deviceToken: String,
    val appVersion: String?,
    val locale: String?,
    val timezone: String?,
    val notificationsEnabled: Boolean
)

data class PushDeviceRecord(
    val deviceId: UUID,
    val userId: UUID,
    val platform: String,
    val notificationsEnabled: Boolean,
    val lastSeenAt: Instant
)

data class NotificationDeliveryGate(
    val preferences: NotificationPreferencesRecord,
    val activeDeviceCount: Int
)

data class NotificationOutboxInsert(
    val recipientUserId: UUID,
    val actorUserId: UUID?,
    val kind: String,
    val subjectId: UUID?,
    val idempotencyKey: String,
    val throttleKey: String?,
    val title: String,
    val body: String,
    val payloadJson: String
)

data class NotificationOutboxRecord(
    val notificationId: UUID,
    val recipientUserId: UUID,
    val kind: String,
    val status: String,
    val createdAt: Instant
)

data class NotificationDeliveryRecord(
    val notificationId: UUID,
    val recipientUserId: UUID,
    val actorUserId: UUID?,
    val kind: String,
    val subjectId: UUID?,
    val title: String,
    val body: String,
    val payloadJson: String,
    val attemptCount: Int,
    val createdAt: Instant
)

data class PushDeliveryTarget(
    val deviceId: UUID,
    val userId: UUID,
    val platform: String,
    val deviceToken: String
)
