package com.pynapli.server.notifications

import com.pynapli.shared.network.dto.NotificationPreferencesDto
import com.pynapli.shared.network.dto.PushDeviceRegistrationResponseDto
import com.pynapli.shared.network.dto.RegisterPushDeviceRequestDto
import com.pynapli.shared.network.dto.UnregisterPushDeviceRequestDto
import com.pynapli.shared.network.dto.UpdateNotificationPreferencesRequestDto
import java.time.Clock
import java.util.UUID

interface NotificationPublisher {
    /** Queues a push; a failed outbox write is logged and dropped (a push never fails its action). */
    suspend fun enqueue(notification: NotificationEventDraft): NotificationOutboxRecord?

    /**
     * Same as [enqueue], but a failed outbox write throws instead of being dropped. For callers that
     * own their own retry, such as the match side-effect outbox: its drainer can only retry a push
     * enqueue it knows failed. Safe to repeat: the outbox write is idempotent per idempotency key.
     */
    suspend fun enqueueOrThrow(notification: NotificationEventDraft): NotificationOutboxRecord? = enqueue(notification)
}

class NotificationService(
    repository: NotificationRepository,
    clock: Clock = Clock.systemUTC(),
) : NotificationPublisher {
    private val preferenceActions = NotificationPreferenceActions(repository)
    private val deviceActions = NotificationDeviceActions(repository)
    private val enqueueActions =
        NotificationEnqueueActions(
            repository = repository,
            clock = clock,
        )

    suspend fun getPreferences(userId: UUID): NotificationPreferencesDto = preferenceActions.getPreferences(userId)

    suspend fun updatePreferences(
        userId: UUID,
        request: UpdateNotificationPreferencesRequestDto,
    ): NotificationPreferencesDto =
        preferenceActions.updatePreferences(
            userId = userId,
            request = request,
        )

    suspend fun registerPushDevice(
        userId: UUID,
        request: RegisterPushDeviceRequestDto,
        sessionId: UUID? = null,
    ): PushDeviceRegistrationResponseDto =
        deviceActions.registerPushDevice(
            userId = userId,
            request = request,
            sessionId = sessionId,
        )

    suspend fun unregisterPushDevice(
        userId: UUID,
        request: UnregisterPushDeviceRequestDto,
    ) {
        deviceActions.unregisterPushDevice(
            userId = userId,
            request = request,
        )
    }

    override suspend fun enqueue(notification: NotificationEventDraft): NotificationOutboxRecord? = enqueueActions.enqueue(notification)

    override suspend fun enqueueOrThrow(notification: NotificationEventDraft): NotificationOutboxRecord? = enqueueActions.enqueue(notification, propagateWriteFailure = true)
}
