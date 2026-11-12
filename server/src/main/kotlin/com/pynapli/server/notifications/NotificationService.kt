package com.pynapli.server.notifications

import com.pynapli.shared.network.dto.NotificationPreferencesDto
import com.pynapli.shared.network.dto.PushDeviceRegistrationResponseDto
import com.pynapli.shared.network.dto.RegisterPushDeviceRequestDto
import com.pynapli.shared.network.dto.UnregisterPushDeviceRequestDto
import com.pynapli.shared.network.dto.UpdateNotificationPreferencesRequestDto
import java.time.Clock
import java.util.UUID

interface NotificationPublisher {
    suspend fun enqueue(notification: NotificationEventDraft): NotificationOutboxRecord?
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
}
