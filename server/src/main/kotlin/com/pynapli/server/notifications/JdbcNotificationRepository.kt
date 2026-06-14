package com.pynapli.server.notifications

import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

class JdbcNotificationRepository(
    dataSource: DataSource,
) : NotificationRepository {
    private val preferencesActions = JdbcNotificationPreferencesActions(dataSource)
    private val deviceActions = JdbcPushDeviceActions(dataSource)
    private val outboxActions = JdbcNotificationOutboxActions(dataSource)

    override suspend fun getPreferences(userId: UUID): NotificationPreferencesRecord? = preferencesActions.getPreferences(userId)

    override suspend fun getDeliveryGate(userId: UUID): NotificationDeliveryGate? = preferencesActions.getDeliveryGate(userId)

    override suspend fun updatePreferences(
        userId: UUID,
        update: NotificationPreferencesUpdate,
    ): NotificationPreferencesRecord? =
        preferencesActions.updatePreferences(
            userId = userId,
            update = update,
        )

    override suspend fun upsertPushDevice(
        userId: UUID,
        registration: PushDeviceRegistration,
        deviceIdFactory: () -> UUID,
    ): PushDeviceRecord =
        deviceActions.upsertPushDevice(
            userId = userId,
            registration = registration,
            deviceIdFactory = deviceIdFactory,
        )

    override suspend fun unregisterPushDevice(
        userId: UUID,
        platform: String,
        deviceToken: String,
    ): Boolean =
        deviceActions.unregisterPushDevice(
            userId = userId,
            platform = platform,
            deviceToken = deviceToken,
        )

    override suspend fun deactivatePushDevice(deviceId: UUID): Boolean = deviceActions.deactivatePushDevice(deviceId)

    override suspend fun insertOutboxNotification(
        notification: NotificationOutboxInsert,
        notificationIdFactory: () -> UUID,
    ): NotificationOutboxRecord? =
        outboxActions.insertOutboxNotification(
            notification = notification,
            notificationIdFactory = notificationIdFactory,
        )

    override suspend fun hasRecentNotification(
        recipientUserId: UUID,
        kind: String,
        throttleKey: String,
        since: Instant,
    ): Boolean =
        outboxActions.hasRecentNotification(
            recipientUserId = recipientUserId,
            kind = kind,
            throttleKey = throttleKey,
            since = since,
        )

    override suspend fun claimPendingNotifications(limit: Int): List<NotificationDeliveryRecord> = outboxActions.claimPendingNotifications(limit)

    override suspend fun requeueStaleProcessingNotifications(
        staleBefore: Instant,
        limit: Int,
    ): Int =
        outboxActions.requeueStaleProcessingNotifications(
            staleBefore = staleBefore,
            limit = limit,
        )

    override suspend fun listActivePushTargets(userId: UUID): List<PushDeliveryTarget> = deviceActions.listActivePushTargets(userId)

    override suspend fun markNotificationSent(notificationId: UUID): Boolean = outboxActions.markNotificationSent(notificationId)

    override suspend fun markNotificationSkipped(
        notificationId: UUID,
        reason: String,
    ): Boolean =
        outboxActions.markNotificationSkipped(
            notificationId = notificationId,
            reason = reason,
        )

    override suspend fun markNotificationRetry(
        notificationId: UUID,
        retryAt: Instant,
        error: String,
    ): Boolean =
        outboxActions.markNotificationRetry(
            notificationId = notificationId,
            retryAt = retryAt,
            error = error,
        )

    override suspend fun markNotificationFailed(
        notificationId: UUID,
        error: String,
    ): Boolean =
        outboxActions.markNotificationFailed(
            notificationId = notificationId,
            error = error,
        )
}
