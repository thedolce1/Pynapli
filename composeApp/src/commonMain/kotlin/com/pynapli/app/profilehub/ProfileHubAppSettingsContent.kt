package com.pynapli.app.profilehub

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pynapli.app.appsettings.AppSettingsUiState

@Composable
internal fun AppSettingsContent(
    state: AppSettingsUiState,
    onBack: () -> Unit,
    onThemeModeChanged: (String) -> Unit,
    onDistanceUnitChanged: (String) -> Unit,
    onNotificationDetailChanged: (String) -> Unit,
    onMessageNotificationsChanged: (Boolean) -> Unit,
    onMatchNotificationsChanged: (Boolean) -> Unit,
    onActivityNotificationsChanged: (Boolean) -> Unit,
    onFlirtNotificationsChanged: (Boolean) -> Unit,
    onAlbumRequestNotificationsChanged: (Boolean) -> Unit,
    onPartnerInviteNotificationsChanged: (Boolean) -> Unit,
    onSystemSafetyNotificationsChanged: (Boolean) -> Unit,
    onQuietHoursEnabledChanged: (Boolean) -> Unit,
    onQuietHoursWindowChanged: (Int, Int) -> Unit,
    onClearError: () -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
    ) {
        ProfileHubTopBar(
            title = "App settings",
            onBack = onBack,
            modifier = Modifier.padding(horizontal = 16.dp),
        )

        LazyColumn(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .weight(1f),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            state.errorMessage?.let { message ->
                item {
                    ProfileHubMessageBand(
                        message = message,
                        isError = true,
                        onDismiss = onClearError,
                    )
                }
            }

            item {
                ProfileHubSection("Theme") {
                    themeModeOptions.forEach { option ->
                        DiscoveryStyleRow(
                            label = option.label,
                            value = option.value,
                            selected = state.draft.themeMode == option.value,
                            onSelected = onThemeModeChanged,
                        )
                    }
                }
            }

            item {
                ProfileHubSection("Units") {
                    distanceUnitOptions.forEach { option ->
                        DiscoveryStyleRow(
                            label = option.label,
                            value = option.value,
                            selected = state.draft.distanceUnit == option.value,
                            onSelected = onDistanceUnitChanged,
                        )
                    }
                }
            }

            item {
                ProfileHubSection("Notifications") {
                    notificationDetailOptions.forEach { option ->
                        DiscoveryStyleRow(
                            label = option.label,
                            value = option.value,
                            selected = state.draft.notificationDetail == option.value,
                            onSelected = onNotificationDetailChanged,
                        )
                    }
                    PrivacyToggleRow(
                        label = "Messages",
                        value = if (state.draft.messageNotifications) "On" else "Off",
                        checked = state.draft.messageNotifications,
                        onCheckedChange = onMessageNotificationsChanged,
                    )
                    PrivacyToggleRow(
                        label = "Matches",
                        value = if (state.draft.matchNotifications) "On" else "Off",
                        checked = state.draft.matchNotifications,
                        onCheckedChange = onMatchNotificationsChanged,
                    )
                    PrivacyToggleRow(
                        label = "Activity",
                        value = if (state.draft.activityNotifications) "On" else "Off",
                        checked = state.draft.activityNotifications,
                        onCheckedChange = onActivityNotificationsChanged,
                    )
                    PrivacyToggleRow(
                        label = "Flirts",
                        value = if (state.draft.flirtNotifications) "On" else "Off",
                        checked = state.draft.flirtNotifications,
                        onCheckedChange = onFlirtNotificationsChanged,
                    )
                    PrivacyToggleRow(
                        label = "Album requests",
                        value = if (state.draft.albumRequestNotifications) "On" else "Off",
                        checked = state.draft.albumRequestNotifications,
                        onCheckedChange = onAlbumRequestNotificationsChanged,
                    )
                    PrivacyToggleRow(
                        label = "Partner invites",
                        value = if (state.draft.partnerInviteNotifications) "On" else "Off",
                        checked = state.draft.partnerInviteNotifications,
                        onCheckedChange = onPartnerInviteNotificationsChanged,
                    )
                    PrivacyToggleRow(
                        label = "System & safety",
                        value = if (state.draft.systemSafetyNotifications) "On" else "Off",
                        checked = state.draft.systemSafetyNotifications,
                        onCheckedChange = onSystemSafetyNotificationsChanged,
                    )
                    PrivacyToggleRow(
                        label = "Quiet hours",
                        value = quietHoursSummary(state.draft),
                        checked = state.draft.quietHoursEnabled,
                        onCheckedChange = onQuietHoursEnabledChanged,
                    )
                    if (state.draft.quietHoursEnabled) {
                        quietHoursOptions.forEach { option ->
                            DiscoveryStyleRow(
                                label = option.label,
                                value = option.value,
                                selected =
                                    state.draft.quietHoursStartMinute == option.startMinute &&
                                        state.draft.quietHoursEndMinute == option.endMinute,
                                onSelected = { onQuietHoursWindowChanged(option.startMinute, option.endMinute) },
                            )
                        }
                    }
                }
            }
        }

        ProfileHubSaveButton(
            label = "Save settings",
            isSaving = state.isSaving,
            onSave = onSave,
        )
    }
}
