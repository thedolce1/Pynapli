package com.pynapli.server.notifications

import com.pynapli.shared.network.dto.NotificationPreferencesDto
import com.pynapli.shared.network.dto.PushDeviceDto

internal fun NotificationPreferencesRecord.toDto(): NotificationPreferencesDto =
    NotificationPreferencesDto(
        detailLevel = detailLevel,
        messagesEnabled = messagesEnabled,
        matchesEnabled = matchesEnabled,
        activityEnabled = activityEnabled,
        flirtsEnabled = flirtsEnabled,
        albumRequestsEnabled = albumRequestsEnabled,
        partnerInvitesEnabled = partnerInvitesEnabled,
        systemSafetyEnabled = systemSafetyEnabled,
        quietHoursEnabled = quietHoursEnabled,
        quietHoursStartMinute = quietHoursStartMinute,
        quietHoursEndMinute = quietHoursEndMinute,
        quietHoursTimezone = quietHoursTimezone,
    )

internal fun PushDeviceRecord.toDto(): PushDeviceDto =
    PushDeviceDto(
        deviceId = deviceId.toString(),
        platform = platform,
        notificationsEnabled = notificationsEnabled,
        lastSeenAt = lastSeenAt.toString(),
    )
