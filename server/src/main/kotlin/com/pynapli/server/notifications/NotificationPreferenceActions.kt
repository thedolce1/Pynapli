package com.pynapli.server.notifications

import com.pynapli.shared.network.dto.NotificationPreferencesDto
import com.pynapli.shared.network.dto.UpdateNotificationPreferencesRequestDto
import java.time.ZoneId
import java.util.UUID

internal class NotificationPreferenceActions(
    private val repository: NotificationRepository,
) {
    suspend fun getPreferences(userId: UUID): NotificationPreferencesDto =
        repository.getPreferences(userId)?.toDto()
            ?: throw NotificationException("NOTIFICATION_PREFERENCES_NOT_FOUND")

    suspend fun updatePreferences(
        userId: UUID,
        request: UpdateNotificationPreferencesRequestDto,
    ): NotificationPreferencesDto {
        val current =
            repository.getPreferences(userId)
                ?: throw NotificationException("NOTIFICATION_PREFERENCES_NOT_FOUND")
        val detailLevel =
            request.detailLevel?.trim()?.lowercase()?.also { detail ->
                if (detail !in NOTIFICATION_DETAIL_LEVELS) throw NotificationException("INVALID_NOTIFICATION_DETAIL_LEVEL")
            }
        val quietHoursStartMinute =
            request.quietHoursStartMinute?.also { minute ->
                if (minute !in MIN_QUIET_HOURS_MINUTE..MAX_QUIET_HOURS_MINUTE) {
                    throw NotificationException("INVALID_QUIET_HOURS_WINDOW")
                }
            }
        val quietHoursEndMinute =
            request.quietHoursEndMinute?.also { minute ->
                if (minute !in MIN_QUIET_HOURS_MINUTE..MAX_QUIET_HOURS_MINUTE) {
                    throw NotificationException("INVALID_QUIET_HOURS_WINDOW")
                }
            }
        val effectiveQuietHoursEnabled = request.quietHoursEnabled ?: current.quietHoursEnabled
        val effectiveQuietHoursStartMinute = quietHoursStartMinute ?: current.quietHoursStartMinute
        val effectiveQuietHoursEndMinute = quietHoursEndMinute ?: current.quietHoursEndMinute
        if (effectiveQuietHoursEnabled && effectiveQuietHoursStartMinute == effectiveQuietHoursEndMinute) {
            throw NotificationException("INVALID_QUIET_HOURS_WINDOW")
        }
        val quietHoursTimezone =
            request.quietHoursTimezone?.trim()?.takeIf { it.isNotEmpty() }?.also { timezone ->
                if (timezone.length > MAX_TIMEZONE_LENGTH || runCatching { ZoneId.of(timezone) }.isFailure) {
                    throw NotificationException("INVALID_QUIET_HOURS_TIMEZONE")
                }
            }

        return repository
            .updatePreferences(
                userId = userId,
                update =
                    NotificationPreferencesUpdate(
                        detailLevel = detailLevel,
                        messagesEnabled = request.messagesEnabled,
                        matchesEnabled = request.matchesEnabled,
                        activityEnabled = request.activityEnabled,
                        flirtsEnabled = request.flirtsEnabled,
                        albumRequestsEnabled = request.albumRequestsEnabled,
                        partnerInvitesEnabled = request.partnerInvitesEnabled,
                        systemSafetyEnabled = request.systemSafetyEnabled,
                        quietHoursEnabled = request.quietHoursEnabled,
                        quietHoursStartMinute = quietHoursStartMinute,
                        quietHoursEndMinute = quietHoursEndMinute,
                        quietHoursTimezone = quietHoursTimezone,
                    ),
            )?.toDto() ?: throw NotificationException("NOTIFICATION_PREFERENCES_NOT_FOUND")
    }
}
