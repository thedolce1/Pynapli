package com.pynapli.app.appsettings

import com.pynapli.shared.network.dto.NotificationPreferencesDto
import com.pynapli.shared.network.dto.UpdateNotificationPreferencesRequestDto
import com.pynapli.shared.settings.AppSettings

internal fun AppSettings.toDraft(): AppSettingsDraft =
    AppSettingsDraft(
        themeMode = themeMode.normalizedThemeMode(),
        distanceUnit = distanceUnit.normalizedDistanceUnit(),
        notificationDetail = notificationDetail.normalizedNotificationDetail(),
        messageNotifications = messageNotifications,
        matchNotifications = matchNotifications,
        activityNotifications = activityNotifications,
        flirtNotifications = flirtNotifications,
        albumRequestNotifications = albumRequestNotifications,
        partnerInviteNotifications = partnerInviteNotifications,
        systemSafetyNotifications = systemSafetyNotifications,
        quietHoursEnabled = quietHoursEnabled,
        quietHoursStartMinute = quietHoursStartMinute.normalizedQuietHoursMinute(AppSettings.DEFAULT_QUIET_HOURS_START_MINUTE),
        quietHoursEndMinute = quietHoursEndMinute.normalizedQuietHoursMinute(AppSettings.DEFAULT_QUIET_HOURS_END_MINUTE),
        quietHoursTimezone = quietHoursTimezone.normalizedQuietHoursTimezone(),
    )

internal fun AppSettingsDraft.toSettings(): AppSettings =
    AppSettings(
        themeMode = themeMode.normalizedThemeMode(),
        distanceUnit = distanceUnit.normalizedDistanceUnit(),
        notificationDetail = notificationDetail.normalizedNotificationDetail(),
        messageNotifications = messageNotifications,
        matchNotifications = matchNotifications,
        activityNotifications = activityNotifications,
        flirtNotifications = flirtNotifications,
        albumRequestNotifications = albumRequestNotifications,
        partnerInviteNotifications = partnerInviteNotifications,
        systemSafetyNotifications = systemSafetyNotifications,
        quietHoursEnabled = quietHoursEnabled,
        quietHoursStartMinute = quietHoursStartMinute.normalizedQuietHoursMinute(AppSettings.DEFAULT_QUIET_HOURS_START_MINUTE),
        quietHoursEndMinute = quietHoursEndMinute.normalizedQuietHoursMinute(AppSettings.DEFAULT_QUIET_HOURS_END_MINUTE),
        quietHoursTimezone = quietHoursTimezone.normalizedQuietHoursTimezone(),
    )

internal fun AppSettings.normalized(): AppSettings =
    AppSettings(
        themeMode = themeMode.normalizedThemeMode(),
        distanceUnit = distanceUnit.normalizedDistanceUnit(),
        notificationDetail = notificationDetail.normalizedNotificationDetail(),
        messageNotifications = messageNotifications,
        matchNotifications = matchNotifications,
        activityNotifications = activityNotifications,
        flirtNotifications = flirtNotifications,
        albumRequestNotifications = albumRequestNotifications,
        partnerInviteNotifications = partnerInviteNotifications,
        systemSafetyNotifications = systemSafetyNotifications,
        quietHoursEnabled = quietHoursEnabled,
        quietHoursStartMinute = quietHoursStartMinute.normalizedQuietHoursMinute(AppSettings.DEFAULT_QUIET_HOURS_START_MINUTE),
        quietHoursEndMinute = quietHoursEndMinute.normalizedQuietHoursMinute(AppSettings.DEFAULT_QUIET_HOURS_END_MINUTE),
        quietHoursTimezone = quietHoursTimezone.normalizedQuietHoursTimezone(),
    )

internal fun AppSettings.mergeNotificationPreferences(preferences: NotificationPreferencesDto): AppSettings =
    copy(
        notificationDetail = preferences.detailLevel.normalizedNotificationDetail(),
        messageNotifications = preferences.messagesEnabled,
        matchNotifications = preferences.matchesEnabled,
        activityNotifications = preferences.activityEnabled,
        flirtNotifications = preferences.flirtsEnabled,
        albumRequestNotifications = preferences.albumRequestsEnabled,
        partnerInviteNotifications = preferences.partnerInvitesEnabled,
        systemSafetyNotifications = preferences.systemSafetyEnabled,
        quietHoursEnabled = preferences.quietHoursEnabled,
        quietHoursStartMinute =
            preferences.quietHoursStartMinute.normalizedQuietHoursMinute(
                AppSettings.DEFAULT_QUIET_HOURS_START_MINUTE,
            ),
        quietHoursEndMinute =
            preferences.quietHoursEndMinute.normalizedQuietHoursMinute(
                AppSettings.DEFAULT_QUIET_HOURS_END_MINUTE,
            ),
        quietHoursTimezone = preferences.quietHoursTimezone.normalizedQuietHoursTimezone(),
    )

internal fun AppSettings.toNotificationPreferencesRequest(): UpdateNotificationPreferencesRequestDto =
    UpdateNotificationPreferencesRequestDto(
        detailLevel = notificationDetail.normalizedNotificationDetail(),
        messagesEnabled = messageNotifications,
        matchesEnabled = matchNotifications,
        activityEnabled = activityNotifications,
        flirtsEnabled = flirtNotifications,
        albumRequestsEnabled = albumRequestNotifications,
        partnerInvitesEnabled = partnerInviteNotifications,
        systemSafetyEnabled = systemSafetyNotifications,
        quietHoursEnabled = quietHoursEnabled,
        quietHoursStartMinute =
            quietHoursStartMinute.normalizedQuietHoursMinute(
                AppSettings.DEFAULT_QUIET_HOURS_START_MINUTE,
            ),
        quietHoursEndMinute =
            quietHoursEndMinute.normalizedQuietHoursMinute(
                AppSettings.DEFAULT_QUIET_HOURS_END_MINUTE,
            ),
        quietHoursTimezone = quietHoursTimezone.normalizedQuietHoursTimezone(),
    )

internal fun String.normalizedThemeMode(): String = takeIf { it in themeModes } ?: AppSettings.THEME_DARK

internal fun String.normalizedDistanceUnit(): String = takeIf { it in distanceUnits } ?: AppSettings.UNIT_METRIC

internal fun String.normalizedNotificationDetail(): String = takeIf { it in notificationDetails } ?: AppSettings.NOTIFICATION_DISCREET

internal fun Int.normalizedQuietHoursMinute(defaultValue: Int): Int = takeIf { it in 0..1439 } ?: defaultValue

private fun String.normalizedQuietHoursTimezone(): String = trim().takeIf { it.isNotEmpty() } ?: AppSettings.DEFAULT_QUIET_HOURS_TIMEZONE

private val themeModes =
    setOf(
        AppSettings.THEME_DARK,
        AppSettings.THEME_LIGHT,
        AppSettings.THEME_SYSTEM,
    )

private val distanceUnits = setOf(AppSettings.UNIT_METRIC, AppSettings.UNIT_IMPERIAL)

private val notificationDetails =
    setOf(
        AppSettings.NOTIFICATION_DISCREET,
        AppSettings.NOTIFICATION_STANDARD,
    )
