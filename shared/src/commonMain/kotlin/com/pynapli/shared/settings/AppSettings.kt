package com.pynapli.shared.settings

import kotlinx.serialization.Serializable

@Serializable
data class AppSettings(
    val themeMode: String = THEME_DARK,
    val distanceUnit: String = UNIT_METRIC,
    val notificationDetail: String = NOTIFICATION_DISCREET,
    val messageNotifications: Boolean = true,
    val matchNotifications: Boolean = true,
    val activityNotifications: Boolean = true,
    val quietHoursEnabled: Boolean = false,
    val quietHoursStartMinute: Int = DEFAULT_QUIET_HOURS_START_MINUTE,
    val quietHoursEndMinute: Int = DEFAULT_QUIET_HOURS_END_MINUTE,
    val quietHoursTimezone: String = DEFAULT_QUIET_HOURS_TIMEZONE,
) {
    companion object {
        const val THEME_DARK = "dark"
        const val THEME_LIGHT = "light"
        const val THEME_SYSTEM = "system"

        const val UNIT_METRIC = "metric"
        const val UNIT_IMPERIAL = "imperial"

        const val NOTIFICATION_DISCREET = "discreet"
        const val NOTIFICATION_STANDARD = "standard"

        const val DEFAULT_QUIET_HOURS_START_MINUTE = 22 * 60
        const val DEFAULT_QUIET_HOURS_END_MINUTE = 8 * 60
        const val DEFAULT_QUIET_HOURS_TIMEZONE = "UTC"
    }
}
