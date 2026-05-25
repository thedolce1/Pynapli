package com.pynapli.app.appsettings

import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.network.PynapliApiClient
import com.pynapli.shared.network.dto.NotificationPreferencesDto
import com.pynapli.shared.network.dto.UpdateNotificationPreferencesRequestDto
import com.pynapli.shared.settings.AppSettings
import com.pynapli.shared.settings.AppSettingsStore

interface AppSettingsRepository {
    suspend fun load(): AppSettings

    suspend fun save(settings: AppSettings)
}

class LocalAppSettingsRepository(
    private val store: AppSettingsStore
) : AppSettingsRepository {
    override suspend fun load(): AppSettings = store.load()

    override suspend fun save(settings: AppSettings) {
        store.save(settings)
    }
}

interface NotificationPreferencesGateway {
    suspend fun getNotificationPreferences(): AppResult<NotificationPreferencesDto>

    suspend fun updateNotificationPreferences(
        request: UpdateNotificationPreferencesRequestDto
    ): AppResult<NotificationPreferencesDto>
}

class ApiNotificationPreferencesGateway(
    private val apiClient: PynapliApiClient
) : NotificationPreferencesGateway {
    override suspend fun getNotificationPreferences(): AppResult<NotificationPreferencesDto> =
        apiClient.getNotificationPreferences()

    override suspend fun updateNotificationPreferences(
        request: UpdateNotificationPreferencesRequestDto
    ): AppResult<NotificationPreferencesDto> =
        apiClient.updateNotificationPreferences(request)
}

class ServerBackedAppSettingsRepository(
    private val localRepository: AppSettingsRepository,
    private val notificationPreferencesGateway: NotificationPreferencesGateway,
    private val timezoneProvider: () -> String? = { null },
    private val remoteSyncEnabled: () -> Boolean = { true }
) : AppSettingsRepository {
    override suspend fun load(): AppSettings {
        val localSettings = localRepository.load().normalized()
        if (!remoteSyncEnabled()) return localSettings

        return when (val result = notificationPreferencesGateway.getNotificationPreferences()) {
            is AppResult.Success -> localSettings.mergeNotificationPreferences(result.value).also { merged ->
                localRepository.save(merged)
            }
            is AppResult.Failure -> localSettings
        }
    }

    override suspend fun save(settings: AppSettings) {
        val normalized = settings.normalized().let { normalizedSettings ->
            normalizedSettings.copy(
                quietHoursTimezone = timezoneProvider()?.trim()?.takeIf { it.isNotEmpty() }
                    ?: normalizedSettings.quietHoursTimezone
            )
        }
        localRepository.save(normalized)
        if (!remoteSyncEnabled()) return

        val request = normalized.toNotificationPreferencesRequest()
        when (val result = notificationPreferencesGateway.updateNotificationPreferences(request)) {
            is AppResult.Success -> localRepository.save(
                normalized.mergeNotificationPreferences(result.value)
            )
            is AppResult.Failure -> Unit
        }
    }
}
