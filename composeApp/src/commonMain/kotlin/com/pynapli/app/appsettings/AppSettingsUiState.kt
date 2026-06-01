package com.pynapli.app.appsettings

import androidx.compose.runtime.Immutable
import com.pynapli.shared.settings.AppSettings

@Immutable
data class AppSettingsUiState(
    val settings: AppSettings = AppSettings(),
    val draft: AppSettingsDraft = AppSettings().toDraft(),
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val errorMessage: String? = null,
)

@Immutable
data class AppSettingsDraft(
    val themeMode: String = AppSettings.THEME_DARK,
    val distanceUnit: String = AppSettings.UNIT_METRIC,
    val notificationDetail: String = AppSettings.NOTIFICATION_DISCREET,
    val messageNotifications: Boolean = true,
    val matchNotifications: Boolean = true,
    val activityNotifications: Boolean = true,
    val flirtNotifications: Boolean = true,
    val albumRequestNotifications: Boolean = true,
    val partnerInviteNotifications: Boolean = true,
    val systemSafetyNotifications: Boolean = true,
    val quietHoursEnabled: Boolean = false,
    val quietHoursStartMinute: Int = AppSettings.DEFAULT_QUIET_HOURS_START_MINUTE,
    val quietHoursEndMinute: Int = AppSettings.DEFAULT_QUIET_HOURS_END_MINUTE,
    val quietHoursTimezone: String = AppSettings.DEFAULT_QUIET_HOURS_TIMEZONE,
)
