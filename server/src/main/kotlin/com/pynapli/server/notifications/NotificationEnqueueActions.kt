package com.pynapli.server.notifications

import com.pynapli.server.contactblocks.ContactBlockVisibility
import java.time.Clock
import java.util.UUID

internal class NotificationEnqueueActions(
    private val repository: NotificationRepository,
    private val clock: Clock,
    private val contactBlockVisibility: ContactBlockVisibility? = null,
) {
    suspend fun enqueue(
        notification: NotificationEventDraft,
        propagateWriteFailure: Boolean = false,
    ): NotificationOutboxRecord? {
        if (notification.actorUserId == notification.recipientUserId) return null
        val kind = notification.kind.trim().lowercase()
        if (kind !in NOTIFICATION_KINDS) throw NotificationException("INVALID_NOTIFICATION_KIND")

        // "Hide from people you know": nothing the actor does reaches a recipient they hide from. The
        // check and the gate read always run, so the only difference is the skipped write, which looks
        // the same as a recipient with no device or with this kind turned off.
        val actorHidden =
            notification.actorUserId?.let { actor ->
                contactBlockVisibility?.isHiddenFrom(viewerUserId = notification.recipientUserId, otherUserId = actor)
            } ?: false
        val gate = repository.getDeliveryGate(notification.recipientUserId) ?: return null
        if (actorHidden) return null
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
        // A push is a courtesy on top of the action that caused it (a message, an invite, an
        // approval). Failing to queue one must never fail that action, so a write error is logged
        // and dropped. NotificationOutboxKindsIntegrationTest guards the known cause (kind check).
        // A caller with its own retry (the match side-effect outbox) asks for the error instead.
        return try {
            repository.insertOutboxNotification(
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
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Exception) {
            if (propagateWriteFailure) throw error
            // Kind and error type only: never recipients, payloads or bodies (logging rule).
            logger.warn("Notification outbox write failed for kind {}: {}", kind, error.javaClass.simpleName)
            null
        }
    }

    private companion object {
        private val logger = org.slf4j.LoggerFactory.getLogger(NotificationEnqueueActions::class.java)
    }
}
