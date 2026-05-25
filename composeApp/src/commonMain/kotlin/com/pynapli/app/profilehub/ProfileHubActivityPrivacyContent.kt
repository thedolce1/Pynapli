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

@Composable
internal fun ActivityControlsContent(
    state: ProfileHubUiState,
    onBack: () -> Unit,
    onShowInViewedListsChanged: (Boolean) -> Unit,
    onAllowGridMessagesChanged: (Boolean) -> Unit,
    onGridMessagePolicyChanged: (String) -> Unit,
    onAllowFlirtsChanged: (Boolean) -> Unit,
    onAllowIntroNotesChanged: (Boolean) -> Unit,
    onAllowAlbumRequestsChanged: (Boolean) -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        ProfileHubTopBar(
            title = "Activity controls",
            onBack = onBack,
            modifier = Modifier.padding(horizontal = 16.dp)
        )

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            state.errorMessage?.let { message ->
                item {
                    ProfileHubMessageBand(
                        message = message,
                        isError = true,
                        onDismiss = { }
                    )
                }
            }

            item {
                ProfileHubSection("Activity visibility") {
                    PrivacyToggleRow(
                        label = "Appear in Viewed lists",
                        value = if (state.activityDraft.showInViewedLists) "Shown" else "Hidden",
                        checked = state.activityDraft.showInViewedLists,
                        onCheckedChange = onShowInViewedListsChanged
                    )
                }
            }

            item {
                ProfileHubSection("Signals") {
                    PrivacyToggleRow(
                        label = "Allow flirts",
                        value = if (state.activityDraft.allowFlirts) "On" else "Off",
                        checked = state.activityDraft.allowFlirts,
                        onCheckedChange = onAllowFlirtsChanged
                    )
                    PrivacyToggleRow(
                        label = "Allow Intro Notes",
                        value = if (state.activityDraft.allowIntroNotes) "On" else "Off",
                        checked = state.activityDraft.allowIntroNotes,
                        onCheckedChange = onAllowIntroNotesChanged
                    )
                    PrivacyToggleRow(
                        label = "Allow album requests",
                        value = if (state.activityDraft.allowAlbumRequests) "On" else "Off",
                        checked = state.activityDraft.allowAlbumRequests,
                        onCheckedChange = onAllowAlbumRequestsChanged
                    )
                }
            }

            item {
                ProfileHubSection("Grid messages") {
                    PrivacyToggleRow(
                        label = "Allow grid messages",
                        value = if (state.activityDraft.allowGridMessages) {
                            gridMessagePolicyLabel(state.activityDraft.gridMessagePolicy)
                        } else {
                            "Off"
                        },
                        checked = state.activityDraft.allowGridMessages,
                        onCheckedChange = onAllowGridMessagesChanged
                    )
                    gridMessagePolicyOptions.forEach { option ->
                        DiscoveryStyleRow(
                            label = option.label,
                            value = option.value,
                            selected = state.activityDraft.gridMessagePolicy == option.value,
                            onSelected = onGridMessagePolicyChanged
                        )
                    }
                }
            }
        }

        ProfileHubSaveButton(
            label = "Save controls",
            isSaving = state.isSaving,
            onSave = onSave
        )
    }
}

@Composable
internal fun PrivacyDiscoveryContent(
    state: ProfileHubUiState,
    onBack: () -> Unit,
    onGridVisibleChanged: (Boolean) -> Unit,
    onSwipeVisibleChanged: (Boolean) -> Unit,
    onShowDistanceChanged: (Boolean) -> Unit,
    onVisibleWhileTravelingChanged: (Boolean) -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        ProfileHubTopBar(
            title = "Privacy",
            onBack = onBack,
            modifier = Modifier.padding(horizontal = 16.dp)
        )

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            state.errorMessage?.let { message ->
                item {
                    ProfileHubMessageBand(
                        message = message,
                        isError = true,
                        onDismiss = { }
                    )
                }
            }

            item {
                ProfileHubSection("Discovery visibility") {
                    PrivacyToggleRow(
                        label = "Grid visibility",
                        value = if (state.privacyDraft.gridVisible) "On" else "Off",
                        checked = state.privacyDraft.gridVisible,
                        onCheckedChange = onGridVisibleChanged
                    )
                    PrivacyToggleRow(
                        label = "Swipe visibility",
                        value = if (state.privacyDraft.swipeVisible) "On" else "Off",
                        checked = state.privacyDraft.swipeVisible,
                        onCheckedChange = onSwipeVisibleChanged
                    )
                    // PROFILE_SETTINGS_SPEC L221-245: opting out hides the user
                    // from every discovery surface whenever their travel session
                    // is active. Off means invisible while traveling.
                    PrivacyToggleRow(
                        label = "Visible while traveling",
                        value = if (state.privacyDraft.visibleWhileTraveling) "On" else "Off",
                        checked = state.privacyDraft.visibleWhileTraveling,
                        onCheckedChange = onVisibleWhileTravelingChanged
                    )
                }
            }

            item {
                ProfileHubSection("Location") {
                    PrivacyToggleRow(
                        label = "Distance display",
                        value = if (state.privacyDraft.showDistance) "Shown" else "Hidden",
                        checked = state.privacyDraft.showDistance,
                        onCheckedChange = onShowDistanceChanged
                    )
                }
            }
        }

        ProfileHubSaveButton(
            label = "Save privacy",
            isSaving = state.isSaving,
            onSave = onSave
        )
    }
}
