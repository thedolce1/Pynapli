package com.pynapli.shared.network

import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.network.dto.NotificationPreferencesDto
import com.pynapli.shared.network.dto.PushDeviceRegistrationResponseDto
import com.pynapli.shared.network.dto.RegisterPushDeviceRequestDto
import com.pynapli.shared.network.dto.UnregisterPushDeviceRequestDto
import com.pynapli.shared.network.dto.UpdateNotificationPreferencesRequestDto

/**
 * Notifications slice of the API surface, split out of the monolithic [PynapliApiClient]
 * (which now composes the per-feature interfaces). Covers notification preferences and
 * push-device registration. Signatures are unchanged.
 */
interface NotificationApi {
    suspend fun getNotificationPreferences(): AppResult<NotificationPreferencesDto>

    suspend fun updateNotificationPreferences(
        request: UpdateNotificationPreferencesRequestDto,
    ): AppResult<NotificationPreferencesDto>

    suspend fun registerPushDevice(
        request: RegisterPushDeviceRequestDto,
    ): AppResult<PushDeviceRegistrationResponseDto>

    suspend fun unregisterPushDevice(request: UnregisterPushDeviceRequestDto): AppResult<Unit>
}
