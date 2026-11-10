package com.pynapli.app.profilehub

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pynapli.app.appsettings.AppSettingsUiState
import com.pynapli.app.design.PynapliLayout
import com.pynapli.app.design.icons.Icons
import com.pynapli.app.design.icons.automirrored.filled.Logout
import com.pynapli.app.design.icons.filled.CardMembership
import com.pynapli.app.design.icons.filled.ChatBubble
import com.pynapli.app.design.icons.filled.Delete
import com.pynapli.app.design.icons.filled.Download
import com.pynapli.app.design.icons.filled.Edit
import com.pynapli.app.design.icons.filled.LocalFireDepartment
import com.pynapli.app.design.icons.filled.Lock
import com.pynapli.app.design.icons.filled.Notifications
import com.pynapli.app.design.icons.filled.People
import com.pynapli.app.design.icons.filled.PhotoLibrary
import com.pynapli.app.design.icons.filled.Security
import com.pynapli.app.design.icons.filled.Settings
import com.pynapli.app.design.icons.filled.Tune
import com.pynapli.app.design.icons.filled.Visibility
import com.pynapli.app.media.MediaAlbumsUiState
import com.pynapli.app.profilelink.ProfileLinksUiState

@Composable
internal fun ProfileHubContent(
    state: ProfileHubUiState,
    appSettingsState: AppSettingsUiState,
    mediaState: MediaAlbumsUiState,
    profileLinksState: ProfileLinksUiState,
    actionItemCount: Int,
    partnerChatInviteCount: Int,
    profileImageUrl: String?,
    onBack: () -> Unit,
    onOpenNotificationCenter: () -> Unit,
    onEditProfile: () -> Unit,
    onPreviewProfile: () -> Unit,
    onOpenPrivacy: () -> Unit,
    onOpenActivityControls: () -> Unit,
    onOpenSafetyCenter: () -> Unit,
    onOpenAgeVerification: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onOpenAlbums: () -> Unit,
    onOpenPartner: () -> Unit,
    onOpenStore: () -> Unit,
    onRequestDataExport: () -> Unit,
    onDeleteAccountClick: () -> Unit,
    onLogoutClick: () -> Unit,
    onClearMessage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val hasPartnerSurface =
        profileLinksState.current != null ||
            profileLinksState.incomingInvites.isNotEmpty() ||
            profileLinksState.outgoingInvites.isNotEmpty() ||
            partnerChatInviteCount > 0

    LazyColumn(
        modifier =
            modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(start = PynapliLayout.ScreenPadding, end = PynapliLayout.ScreenPadding, bottom = ProfileSurfaceBottomPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            ProfileHubTopBar(
                onBack = onBack,
                actionCount = actionItemCount,
                onNotificationsClick = onOpenNotificationCenter,
            )
        }

        item {
            ProfileSummary(
                state = state,
                profileImageUrl = profileImageUrl,
                linkedPartnerLabel = profileLinksState.current?.displayLabel,
                profilePhotoCount = mediaState.profileItems.size,
                privatePhotoCount = mediaState.privateItems.size,
            )
        }

        state.errorMessage?.let { message ->
            item {
                ProfileHubMessageBand(
                    message = message,
                    isError = true,
                    onDismiss = onClearMessage,
                )
            }
        }

        state.feedbackMessage?.let { message ->
            item {
                ProfileHubMessageBand(
                    message = message,
                    isError = false,
                    onDismiss = onClearMessage,
                )
            }
        }

        item {
            ProfileHubSection("Notifications") {
                ProfileHubRow(
                    icon = Icons.Filled.Notifications,
                    label = "Action center",
                    value = actionCenterSummary(actionItemCount),
                    onClick = onOpenNotificationCenter,
                )
            }
        }

        item {
            ProfileHubSection("Profile") {
                ProfileHubRow(
                    icon = Icons.Filled.Edit,
                    label = "Edit profile",
                    value = profileBasicsSummary(state),
                    onClick = onEditProfile,
                )
                ProfileHubRow(
                    icon = Icons.Filled.Visibility,
                    label = "Preview profile",
                    value = "See your public view",
                    onClick = onPreviewProfile,
                )
            }
        }

        if (hasPartnerSurface) {
            item {
                ProfileHubSection("Partner") {
                    ProfileHubRow(
                        icon = Icons.Filled.People,
                        label = "Linked partner",
                        value = profileLinksState.current?.displayLabel ?: pendingPartnerStatus(profileLinksState),
                        onClick = onOpenPartner,
                    )
                    if (profileLinksState.current != null || partnerChatInviteCount > 0) {
                        ProfileHubRow(
                            icon = Icons.Filled.ChatBubble,
                            label = "Partner chat invites",
                            value = partnerChatInviteSummary(partnerChatInviteCount),
                        )
                    }
                }
            }
        }

        item {
            ProfileHubSection("Albums") {
                ProfileHubRow(
                    icon = Icons.Filled.PhotoLibrary,
                    label = "Photos and private album",
                    value = albumSummary(mediaState),
                    onClick = onOpenAlbums,
                )
                ProfileHubRow(
                    icon = Icons.Filled.Lock,
                    label = "Album access",
                    value = grantSummary(mediaState),
                )
            }
        }

        item {
            ProfileHubSection("Subscription") {
                ProfileHubRow(
                    icon = Icons.Filled.CardMembership,
                    label = "Current plan",
                    value = currentPlanSummary(state),
                    onClick = onOpenStore,
                )
                if (profileLinksState.current != null) {
                    ProfileHubRow(
                        icon = Icons.Filled.People,
                        label = "Linked partner benefit",
                        value = linkedPartnerBenefitSummary(state, profileLinksState),
                        onClick = onOpenStore,
                    )
                }
            }
        }

        item {
            ProfileHubSection("Privacy") {
                ProfileHubRow(
                    icon = Icons.Filled.Visibility,
                    label = "Visibility",
                    value = visibilitySummary(state),
                    onClick = onOpenPrivacy,
                )
                ProfileHubRow(
                    icon = Icons.Filled.Tune,
                    label = "Distance display",
                    value = distanceSummary(state),
                    onClick = onOpenPrivacy,
                )
            }
        }

        item {
            ProfileHubSection("Safety") {
                ProfileHubRow(
                    icon = Icons.Filled.Security,
                    label = "Safety center",
                    value = safetyCenterSummary(state),
                    onClick = onOpenSafetyCenter,
                )
                ProfileHubRow(
                    icon = Icons.Filled.LocalFireDepartment,
                    label = "Activity controls",
                    value = activityControlsSummary(state),
                    onClick = onOpenActivityControls,
                )
                ProfileHubRow(
                    icon = Icons.Filled.Lock,
                    label = "Age verification",
                    value = ageVerificationSummary(state),
                    onClick = onOpenAgeVerification,
                )
            }
        }

        item {
            ProfileHubSection("Account") {
                ProfileHubRow(
                    icon = Icons.Filled.Settings,
                    label = "App settings",
                    value = appSettingsSummary(appSettingsState),
                    onClick = onOpenAppSettings,
                )
                ProfileHubRow(
                    icon = Icons.Filled.Download,
                    label = "Data export",
                    value = dataExportSummary(state),
                    onClick = onRequestDataExport,
                )
                ProfileHubRow(
                    icon = Icons.Filled.Delete,
                    label = "Delete account",
                    value = if (state.isDeletingAccount) "Deleting" else "Permanent",
                    onClick = onDeleteAccountClick,
                )
                ProfileHubRow(
                    icon = Icons.AutoMirrored.Filled.Logout,
                    label = "Log out",
                    value = "Keep account",
                    onClick = onLogoutClick,
                )
            }
        }
    }
}
