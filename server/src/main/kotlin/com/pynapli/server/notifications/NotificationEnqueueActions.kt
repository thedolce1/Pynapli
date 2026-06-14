package com.pynapli.server.notifications

import java.time.Clock
import java.util.UUID

internal class NotificationEnqueueActions(
    private val repository: NotificationRepository,
    private val clock: Clock,
) {
    suspend fun enqueue(notification: NotificationEventDraft): NotificationOutboxRecord? {
        if (notification.actorUserId == notification.recipientUserId) return null
        val kind = notification.kind.trim().lowercase()
        if (kind !in NOTIFICATION_KINDS) throw NotificationException("INVALID_NOTIFICATION_KIND")

        val gate = repository.getDeliveryGate(notification.recipientUserId) ?: return null
        if (gate.activeDeviceCount <= 0) return null
        if (!gate.preferences.allows(kind)) return null

        val throttlePolicy = notification.throttlePolicy(kind)
        if (
            throttlePolicy != null &&
            repository.hasRecentNotification(
                recipientUserId = notification.recipientUserId,
                kind = kind,
                throttleKey = throttlePolicy.key,
                since = clock.instant().minusSeconds(throttlePolicy.windowSeconds),
            )
        ) {
            return null
        }

        val copy = notificationCopy(kind = kind, detailLevel = gate.preferences.detailLevel)
        val idempotencyKey =
            throttlePolicy?.bucketedIdempotencyKey(clock.instant().epochSecond)
                ?: notification.idempotencyKey
        return repository.insertOutboxNotification(
            notification =
                NotificationOutboxInsert(
                    recipientUserId = notification.recipientUserId,
                    actorUserId = notification.actorUserId,
                    kind = kind,
                    subjectId = notification.subjectId,
                    idempotencyKey = idempotencyKey,
                    throttleKey = throttlePolicy?.key,
                    title = copy.title,
                    body = copy.body,
                    payloadJson = notification.payload.toPayloadJson(kind, gate.preferences.detailLevel),
                ),
            notificationIdFactory = UUID::randomUUID,
        )
    }
}
