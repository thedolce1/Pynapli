package com.pynapli.shared.network.dto

import kotlinx.serialization.Serializable

object NotificationDetailLevel {
    const val DISCREET = "discreet"
    const val STANDARD = "standard"
}

@Serializable
data class NotificationPreferencesDto(
    val detailLevel: String,
    val messagesEnabled: Boolean,
    val matchesEnabled: Boolean,
    val activityEnabled: Boolean,
    val flirtsEnabled: Boolean,
    val albumRequestsEnabled: Boolean,
    val partnerInvitesEnabled: Boolean,
    val systemSafetyEnabled: Boolean,
    val quietHoursEnabled: Boolean = false,
    val quietHoursStartMinute: Int = 1320,
    val quietHoursEndMinute: Int = 480,
    val quietHoursTimezone: String = "UTC",
)

@Serializable
data class UpdateNotificationPreferencesRequestDto(
    val detailLevel: String? = null,
    val messagesEnabled: Boolean? = null,
    val matchesEnabled: Boolean? = null,
    val activityEnabled: Boolean? = null,
    val flirtsEnabled: Boolean? = null,
    val albumRequestsEnabled: Boolean? = null,
    val partnerInvitesEnabled: Boolean? = null,
    val systemSafetyEnabled: Boolean? = null,
    val quietHoursEnabled: Boolean? = null,
    val quietHoursStartMinute: Int? = null,
    val quietHoursEndMinute: Int? = null,
    val quietHoursTimezone: String? = null,
)

@Serializable
data class RegisterPushDeviceRequestDto(
    val platform: String,
    val deviceToken: String,
    val appVersion: String? = null,
    val locale: String? = null,
    val timezone: String? = null,
    val notificationsEnabled: Boolean = true,
)

@Serializable
data class UnregisterPushDeviceRequestDto(
    val platform: String,
    val deviceToken: String,
)

@Serializable
data class PushDeviceDto(
    val deviceId: String,
    val platform: String,
    val notificationsEnabled: Boolean,
    val lastSeenAt: String,
)

@Serializable
data class PushDeviceRegistrationResponseDto(
    val device: PushDeviceDto,
)
