package com.pynapli.app.appsettings

import androidx.compose.runtime.Immutable
import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.network.PynapliApiClient
import com.pynapli.shared.network.dto.NotificationPreferencesDto
import com.pynapli.shared.network.dto.UpdateNotificationPreferencesRequestDto
import com.pynapli.shared.settings.AppSettings
import com.pynapli.shared.settings.AppSettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

@Immutable
data class AppSettingsUiState(
    val settings: AppSettings = AppSettings(),
    val draft: AppSettingsDraft = AppSettings().toDraft(),
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val errorMessage: String? = null
)

@Immutable
data class AppSettingsDraft(
    val themeMode: String = AppSettings.THEME_DARK,
    val distanceUnit: String = AppSettings.UNIT_METRIC,
    val notificationDetail: String = AppSettings.NOTIFICATION_DISCREET,
    val messageNotifications: Boolean = true,
    val matchNotifications: Boolean = true,
    val activityNotifications: Boolean = true,
    val quietHoursEnabled: Boolean = false,
    val quietHoursStartMinute: Int = AppSettings.DEFAULT_QUIET_HOURS_START_MINUTE,
    val quietHoursEndMinute: Int = AppSettings.DEFAULT_QUIET_HOURS_END_MINUTE,
    val quietHoursTimezone: String = AppSettings.DEFAULT_QUIET_HOURS_TIMEZONE
)

class AppSettingsStateHolder(
    private val repository: AppSettingsRepository
) {
    private val _state = MutableStateFlow(AppSettingsUiState())
    val state: StateFlow<AppSettingsUiState> = _state.asStateFlow()

    suspend fun load() {
        if (state.value.isLoading) return
        _state.update { it.copy(isLoading = true, errorMessage = null) }

        runCatching { repository.load() }
            .onSuccess { settings ->
                _state.update {
                    it.copy(
                        settings = settings,
                        draft = settings.toDraft(),
                        isLoading = false,
                        errorMessage = null
                    )
                }
            }
            .onFailure { error ->
                _state.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = error.message ?: "Could not load app settings."
                    )
                }
            }
    }

    fun editFromCurrent() {
        _state.update {
            it.copy(
                draft = it.settings.toDraft(),
                errorMessage = null
            )
        }
    }

    fun updateThemeMode(value: String) = updateDraft { it.copy(themeMode = value.normalizedThemeMode()) }

    fun updateDistanceUnit(value: String) = updateDraft { it.copy(distanceUnit = value.normalizedDistanceUnit()) }

    fun updateNotificationDetail(value: String) =
        updateDraft { it.copy(notificationDetail = value.normalizedNotificationDetail()) }

    fun updateMessageNotifications(value: Boolean) = updateDraft { it.copy(messageNotifications = value) }

    fun updateMatchNotifications(value: Boolean) = updateDraft { it.copy(matchNotifications = value) }

    fun updateActivityNotifications(value: Boolean) = updateDraft { it.copy(activityNotifications = value) }

    fun updateQuietHoursEnabled(value: Boolean) = updateDraft { it.copy(quietHoursEnabled = value) }

    fun updateQuietHoursWindow(startMinute: Int, endMinute: Int) = updateDraft {
        it.copy(
            quietHoursStartMinute = startMinute.normalizedQuietHoursMinute(AppSettings.DEFAULT_QUIET_HOURS_START_MINUTE),
            quietHoursEndMinute = endMinute.normalizedQuietHoursMinute(AppSettings.DEFAULT_QUIET_HOURS_END_MINUTE)
        )
    }

    fun clearError() {
        _state.update { it.copy(errorMessage = null) }
    }

    suspend fun saveDraft(): Boolean {
        val current = state.value
        if (current.isSaving) return false

        _state.update { it.copy(isSaving = true, errorMessage = null) }

        return runCatching {
            val settings = current.draft.toSettings()
            repository.save(settings)
            settings
        }.fold(
            onSuccess = { settings ->
                _state.update {
                    it.copy(
                        settings = settings,
                        draft = settings.toDraft(),
                        isSaving = false,
                        errorMessage = null
                    )
                }
                true
            },
            onFailure = { error ->
                _state.update {
                    it.copy(
                        isSaving = false,
                        errorMessage = error.message ?: "Could not save app settings."
                    )
                }
                false
            }
        )
    }

    private fun updateDraft(transform: (AppSettingsDraft) -> AppSettingsDraft) {
        _state.update {
            it.copy(
                draft = transform(it.draft),
                errorMessage = null
            )
        }
    }
}

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

private fun AppSettings.toDraft(): AppSettingsDraft = AppSettingsDraft(
    themeMode = themeMode.normalizedThemeMode(),
    distanceUnit = distanceUnit.normalizedDistanceUnit(),
    notificationDetail = notificationDetail.normalizedNotificationDetail(),
    messageNotifications = messageNotifications,
    matchNotifications = matchNotifications,
    activityNotifications = activityNotifications,
    quietHoursEnabled = quietHoursEnabled,
    quietHoursStartMinute = quietHoursStartMinute.normalizedQuietHoursMinute(AppSettings.DEFAULT_QUIET_HOURS_START_MINUTE),
    quietHoursEndMinute = quietHoursEndMinute.normalizedQuietHoursMinute(AppSettings.DEFAULT_QUIET_HOURS_END_MINUTE),
    quietHoursTimezone = quietHoursTimezone.normalizedQuietHoursTimezone()
)

private fun AppSettingsDraft.toSettings(): AppSettings = AppSettings(
    themeMode = themeMode.normalizedThemeMode(),
    distanceUnit = distanceUnit.normalizedDistanceUnit(),
    notificationDetail = notificationDetail.normalizedNotificationDetail(),
    messageNotifications = messageNotifications,
    matchNotifications = matchNotifications,
    activityNotifications = activityNotifications,
    quietHoursEnabled = quietHoursEnabled,
    quietHoursStartMinute = quietHoursStartMinute.normalizedQuietHoursMinute(AppSettings.DEFAULT_QUIET_HOURS_START_MINUTE),
    quietHoursEndMinute = quietHoursEndMinute.normalizedQuietHoursMinute(AppSettings.DEFAULT_QUIET_HOURS_END_MINUTE),
    quietHoursTimezone = quietHoursTimezone.normalizedQuietHoursTimezone()
)

private fun AppSettings.normalized(): AppSettings = AppSettings(
    themeMode = themeMode.normalizedThemeMode(),
    distanceUnit = distanceUnit.normalizedDistanceUnit(),
    notificationDetail = notificationDetail.normalizedNotificationDetail(),
    messageNotifications = messageNotifications,
    matchNotifications = matchNotifications,
    activityNotifications = activityNotifications,
    quietHoursEnabled = quietHoursEnabled,
    quietHoursStartMinute = quietHoursStartMinute.normalizedQuietHoursMinute(AppSettings.DEFAULT_QUIET_HOURS_START_MINUTE),
    quietHoursEndMinute = quietHoursEndMinute.normalizedQuietHoursMinute(AppSettings.DEFAULT_QUIET_HOURS_END_MINUTE),
    quietHoursTimezone = quietHoursTimezone.normalizedQuietHoursTimezone()
)

private fun AppSettings.mergeNotificationPreferences(preferences: NotificationPreferencesDto): AppSettings =
    copy(
        notificationDetail = preferences.detailLevel.normalizedNotificationDetail(),
        messageNotifications = preferences.messagesEnabled,
        matchNotifications = preferences.matchesEnabled,
        activityNotifications = preferences.activityEnabled &&
            preferences.flirtsEnabled &&
            preferences.albumRequestsEnabled &&
            preferences.partnerInvitesEnabled,
        quietHoursEnabled = preferences.quietHoursEnabled,
        quietHoursStartMinute = preferences.quietHoursStartMinute.normalizedQuietHoursMinute(
            AppSettings.DEFAULT_QUIET_HOURS_START_MINUTE
        ),
        quietHoursEndMinute = preferences.quietHoursEndMinute.normalizedQuietHoursMinute(
            AppSettings.DEFAULT_QUIET_HOURS_END_MINUTE
        ),
        quietHoursTimezone = preferences.quietHoursTimezone.normalizedQuietHoursTimezone()
    )

private fun AppSettings.toNotificationPreferencesRequest(): UpdateNotificationPreferencesRequestDto =
    UpdateNotificationPreferencesRequestDto(
        detailLevel = notificationDetail.normalizedNotificationDetail(),
        messagesEnabled = messageNotifications,
        matchesEnabled = matchNotifications,
        activityEnabled = activityNotifications,
        flirtsEnabled = activityNotifications,
        albumRequestsEnabled = activityNotifications,
        partnerInvitesEnabled = activityNotifications,
        systemSafetyEnabled = true,
        quietHoursEnabled = quietHoursEnabled,
        quietHoursStartMinute = quietHoursStartMinute.normalizedQuietHoursMinute(
            AppSettings.DEFAULT_QUIET_HOURS_START_MINUTE
        ),
        quietHoursEndMinute = quietHoursEndMinute.normalizedQuietHoursMinute(
            AppSettings.DEFAULT_QUIET_HOURS_END_MINUTE
        ),
        quietHoursTimezone = quietHoursTimezone.normalizedQuietHoursTimezone()
    )

private fun String.normalizedThemeMode(): String =
    takeIf { it in themeModes } ?: AppSettings.THEME_DARK

private fun String.normalizedDistanceUnit(): String =
    takeIf { it in distanceUnits } ?: AppSettings.UNIT_METRIC

private fun String.normalizedNotificationDetail(): String =
    takeIf { it in notificationDetails } ?: AppSettings.NOTIFICATION_DISCREET

private fun Int.normalizedQuietHoursMinute(defaultValue: Int): Int =
    takeIf { it in 0..1439 } ?: defaultValue

private fun String.normalizedQuietHoursTimezone(): String =
    trim().takeIf { it.isNotEmpty() } ?: AppSettings.DEFAULT_QUIET_HOURS_TIMEZONE

private val themeModes = setOf(
    AppSettings.THEME_DARK,
    AppSettings.THEME_LIGHT,
    AppSettings.THEME_SYSTEM
)

private val distanceUnits = setOf(AppSettings.UNIT_METRIC, AppSettings.UNIT_IMPERIAL)

private val notificationDetails = setOf(
    AppSettings.NOTIFICATION_DISCREET,
    AppSettings.NOTIFICATION_STANDARD
)
