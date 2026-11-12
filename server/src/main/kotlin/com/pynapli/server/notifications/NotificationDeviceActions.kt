package com.pynapli.server.notifications

import com.pynapli.shared.network.dto.PushDeviceRegistrationResponseDto
import com.pynapli.shared.network.dto.RegisterPushDeviceRequestDto
import com.pynapli.shared.network.dto.UnregisterPushDeviceRequestDto
import java.util.UUID

internal class NotificationDeviceActions(
    private val repository: NotificationRepository,
) {
    suspend fun registerPushDevice(
        userId: UUID,
        request: RegisterPushDeviceRequestDto,
        sessionId: UUID? = null,
    ): PushDeviceRegistrationResponseDto {
        val platform = request.platform.trim().lowercase()
        if (platform !in PUSH_PLATFORMS) throw NotificationException("INVALID_PUSH_PLATFORM")

        val token = request.deviceToken.trim()
        if (token.length !in MIN_PUSH_TOKEN_LENGTH..MAX_PUSH_TOKEN_LENGTH) {
            throw NotificationException("INVALID_PUSH_TOKEN")
        }

        val record =
            repository.upsertPushDevice(
                userId = userId,
                registration =
                    PushDeviceRegistration(
                        platform = platform,
                        deviceToken = token,
                        appVersion = request.appVersion.cleaned(MAX_APP_VERSION_LENGTH),
                        locale = request.locale.cleaned(MAX_LOCALE_LENGTH),
                        timezone = request.timezone.cleaned(MAX_TIMEZONE_LENGTH),
                        notificationsEnabled = request.notificationsEnabled,
                        sessionId = sessionId,
                    ),
                deviceIdFactory = UUID::randomUUID,
            )

        return PushDeviceRegistrationResponseDto(record.toDto())
    }

    suspend fun unregisterPushDevice(
        userId: UUID,
        request: UnregisterPushDeviceRequestDto,
    ) {
        val platform = request.platform.trim().lowercase()
        if (platform !in PUSH_PLATFORMS) throw NotificationException("INVALID_PUSH_PLATFORM")

        val token = request.deviceToken.trim()
        if (token.length !in MIN_PUSH_TOKEN_LENGTH..MAX_PUSH_TOKEN_LENGTH) {
            throw NotificationException("INVALID_PUSH_TOKEN")
        }

        repository.unregisterPushDevice(
            userId = userId,
            platform = platform,
            deviceToken = token,
        )
    }

    private fun String?.cleaned(maxLength: Int): String? {
        val value = this?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return value.take(maxLength)
    }
}
