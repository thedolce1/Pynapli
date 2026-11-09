package com.pynapli.app.profilehub

import com.pynapli.app.appsettings.AppSettingsDraft
import com.pynapli.app.appsettings.AppSettingsUiState
import com.pynapli.app.media.MediaAlbumsUiState
import com.pynapli.app.profilelink.ProfileLinksUiState
import com.pynapli.shared.network.dto.EntitlementSource
import com.pynapli.shared.network.dto.SubscriptionTier
import com.pynapli.shared.settings.AppSettings

internal fun pendingPartnerStatus(state: ProfileLinksUiState): String =
    when {
        state.incomingInvites.isNotEmpty() -> "${state.incomingInvites.size} invite"
        state.outgoingInvites.isNotEmpty() -> "Invite sent"
        state.isLoading -> "Loading"
        else -> "Not linked"
    }

internal fun actionCenterSummary(actionItemCount: Int): String = if (actionItemCount == 0) "No action items" else "$actionItemCount action item${if (actionItemCount == 1) "" else "s"}"

internal fun partnerChatInviteSummary(count: Int): String = if (count == 0) "Ask every time" else "$count pending invite${if (count == 1) "" else "s"}"

internal fun albumSummary(state: MediaAlbumsUiState): String = "${state.profileItems.size} public, ${state.privateItems.size} private"

internal fun grantSummary(state: MediaAlbumsUiState): String = if (state.privateGrants.isEmpty()) "No active grants" else "${state.privateGrants.size} active"

internal fun currentPlanSummary(state: ProfileHubUiState): String = state.entitlement?.displayName ?: if (state.isLoading) "Loading" else "Free"

// Compact tier label for the summary-card status pill ("Free"/"Social"/"Select").
internal fun planBadgeLabel(state: ProfileHubUiState): String =
    when (state.entitlement?.tier) {
        SubscriptionTier.SELECT -> "Select"
        SubscriptionTier.SOCIAL -> "Social"
        else -> "Free"
    }

internal fun linkedPartnerBenefitSummary(
    state: ProfileHubUiState,
    profileLinksState: ProfileLinksUiState,
): String {
    val entitlement = state.entitlement
    return when {
        entitlement?.source == EntitlementSource.LINKED_PARTNER_BENEFIT -> "Active via your partner"
        entitlement?.linkedPartnerIncluded == true ->
            if (profileLinksState.current != null) "Shared with your partner" else "Included — link a partner"
        entitlement == null && state.isLoading -> "Loading"
        else -> "Not included on Free"
    }
}

internal fun profileBasicsSummary(state: ProfileHubUiState): String {
    val profile = state.me?.profile ?: return if (state.isLoading) "Loading" else "Set up"
    val completeFields =
        listOf(
            profile.displayName,
            profile.birthdate,
            profile.gender,
            profile.orientation,
            profile.relationshipContext,
            profile.discoveryStyle,
        ).count { !it.isNullOrBlank() }
    return "$completeFields of 6 fields"
}

internal fun visibilitySummary(state: ProfileHubUiState): String {
    val settings = state.me?.discoverySettings ?: return if (state.isLoading) "Loading" else "Set up"
    return when {
        settings.gridVisible && settings.swipeVisible -> "Grid + swipe"
        settings.gridVisible -> "Grid only"
        settings.swipeVisible -> "Swipe only"
        else -> "Hidden"
    }
}

internal fun distanceSummary(state: ProfileHubUiState): String {
    val settings = state.me?.discoverySettings ?: return if (state.isLoading) "Loading" else "Set up"
    return if (settings.showDistance) "Shown" else "Hidden"
}

internal fun activityControlsSummary(state: ProfileHubUiState): String {
    val settings = state.me?.discoverySettings ?: return if (state.isLoading) "Loading" else "Set up"
    val viewed = if (settings.showInViewedLists) "Viewed on" else "Viewed hidden"
    val allowedSignals =
        listOf(
            settings.allowGridMessages && settings.gridMessagePolicy != "no_one",
            settings.allowFlirts,
            settings.allowIntroNotes,
            settings.allowAlbumRequests,
        ).count { it }
    return "$viewed, $allowedSignals allowed"
}

internal fun ageVerificationSummary(state: ProfileHubUiState): String {
    if (state.me?.profile?.ageVerified == true) return "Verified"
    return when (state.ageVerificationStatus?.status) {
        "verified" -> "Verified"
        "pending" -> "Pending review"
        "rejected" -> "Needs review"
        else ->
            if (state.me
                    ?.profile
                    ?.birthdate
                    .isNullOrBlank()
            ) {
                "Add birthdate"
            } else {
                "Start"
            }
    }
}

internal fun safetyCenterSummary(state: ProfileHubUiState): String =
    when {
        state.isBlocksLoading || state.isHiddenProfilesLoading || state.isReportHistoryLoading -> "Loading safety lists"
        state.blockedUsers.isEmpty() && state.hiddenProfiles.isEmpty() && state.reportHistory.isEmpty() ->
            "Guidelines, blocks, hidden"
        else -> "${state.blockedUsers.size} blocked, ${state.hiddenProfiles.size} hidden, ${state.reportHistory.size} reports"
    }

internal fun appSettingsSummary(state: AppSettingsUiState): String {
    if (state.isLoading) return "Loading"
    return "${themeModeLabel(state.settings.themeMode)}, ${distanceUnitShortLabel(state.settings.distanceUnit)}"
}

internal fun dataExportSummary(state: ProfileHubUiState): String =
    when {
        state.isDataExportMutating -> "Updating"
        state.latestDataExport == null -> "Request a copy"
        state.latestDataExport.status == "ready" -> "Download"
        state.latestDataExport.status == "processing" -> "Preparing"
        state.latestDataExport.status == "failed" -> "Needs retry"
        state.latestDataExport.status == "expired" -> "Expired"
        else -> "Requested"
    }

internal fun quietHoursSummary(draft: AppSettingsDraft): String =
    if (!draft.quietHoursEnabled) {
        "Off"
    } else {
        "${minuteLabel(draft.quietHoursStartMinute)}-${minuteLabel(draft.quietHoursEndMinute)}"
    }

private fun minuteLabel(minuteOfDay: Int): String {
    val hour24 = (minuteOfDay / 60).coerceIn(0, 23)
    val minute = (minuteOfDay % 60).coerceIn(0, 59)
    val suffix = if (hour24 < 12) "AM" else "PM"
    val hour12 =
        when (val normalized = hour24 % 12) {
            0 -> 12
            else -> normalized
        }
    return if (minute == 0) {
        "$hour12 $suffix"
    } else {
        "$hour12:${minute.toString().padStart(2, '0')} $suffix"
    }
}

internal fun gridMessagePolicyLabel(value: String): String =
    when (value) {
        "matches_only" -> "Matches only"
        "no_one" -> "Off"
        else -> "Everyone"
    }

private fun themeModeLabel(value: String): String =
    when (value) {
        AppSettings.THEME_LIGHT -> "Light"
        AppSettings.THEME_SYSTEM -> "System"
        else -> "Dark"
    }

private fun distanceUnitShortLabel(value: String): String =
    when (value) {
        AppSettings.UNIT_IMPERIAL -> "mi"
        else -> "km"
    }

internal data class DiscoveryStyleOption(
    val value: String,
    val label: String,
)

internal data class QuietHoursOption(
    val value: String,
    val label: String,
    val startMinute: Int,
    val endMinute: Int,
)

internal val discoveryStyleOptions =
    listOf(
        DiscoveryStyleOption("solo", "Solo"),
        DiscoveryStyleOption("together", "Together"),
        DiscoveryStyleOption("both", "Solo or together"),
        DiscoveryStyleOption("social", "Social"),
        DiscoveryStyleOption("ask_in_chat", "Ask in chat"),
    )

internal val gridMessagePolicyOptions =
    listOf(
        DiscoveryStyleOption("everyone", "Everyone"),
        DiscoveryStyleOption("matches_only", "Matches only"),
        DiscoveryStyleOption("no_one", "No first messages"),
    )

internal val themeModeOptions =
    listOf(
        DiscoveryStyleOption(AppSettings.THEME_DARK, "Dark"),
        DiscoveryStyleOption(AppSettings.THEME_LIGHT, "Light"),
        DiscoveryStyleOption(AppSettings.THEME_SYSTEM, "System"),
    )

internal val distanceUnitOptions =
    listOf(
        DiscoveryStyleOption(AppSettings.UNIT_METRIC, "Kilometers"),
        DiscoveryStyleOption(AppSettings.UNIT_IMPERIAL, "Miles"),
    )

internal val notificationDetailOptions =
    listOf(
        DiscoveryStyleOption(AppSettings.NOTIFICATION_DISCREET, "Discreet"),
        DiscoveryStyleOption(AppSettings.NOTIFICATION_STANDARD, "Standard"),
    )

internal val quietHoursOptions =
    listOf(
        QuietHoursOption("22:8", "10 PM-8 AM", 22 * 60, 8 * 60),
        QuietHoursOption("23:7", "11 PM-7 AM", 23 * 60, 7 * 60),
        QuietHoursOption("0:8", "Midnight-8 AM", 0, 8 * 60),
    )
