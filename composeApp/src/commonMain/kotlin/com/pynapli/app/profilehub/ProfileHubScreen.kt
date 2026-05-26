package com.pynapli.app.profilehub

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.pynapli.app.appsettings.AppSettingsStateHolder
import com.pynapli.app.media.MediaAlbumsUiState
import com.pynapli.app.messages.MessagesUiState
import com.pynapli.app.profilelink.ProfileLinksUiState
import kotlinx.coroutines.launch

@Composable
fun ProfileHubScreen(
    stateHolder: ProfileHubStateHolder,
    appSettingsStateHolder: AppSettingsStateHolder,
    mediaState: MediaAlbumsUiState,
    profileLinksState: ProfileLinksUiState,
    messagesState: MessagesUiState,
    viewerUserId: String?,
    profileImageUrl: String?,
    onBack: () -> Unit,
    onOpenAlbums: () -> Unit,
    onAcceptProfileLinkInvite: (String) -> Unit,
    onDeclineProfileLinkInvite: (String) -> Unit,
    onAcceptPartnerChatInvite: (String) -> Unit,
    onDeclinePartnerChatInvite: (String) -> Unit,
    onClearProfileLinkMessages: () -> Unit,
    onClearMessagesError: () -> Unit,
    onLogout: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state by stateHolder.state.collectAsState()
    val appSettingsState by appSettingsStateHolder.state.collectAsState()
    val partnerChatInvites = messagesState.partnerChatInvites(viewerUserId)
    val actionItemCount = profileLinksState.incomingInvites.size + partnerChatInvites.size
    val scope = rememberCoroutineScope()
    var showLogoutConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(stateHolder) {
        stateHolder.load()
    }

    if (state.editVisible) {
        ProfileEditContent(
            state = state,
            onBack = stateHolder::dismissEditor,
            onDisplayNameChanged = stateHolder::updateDisplayName,
            onBirthdateChanged = stateHolder::updateBirthdate,
            onGenderChanged = stateHolder::updateGender,
            onOrientationChanged = stateHolder::updateOrientation,
            onRelationshipContextChanged = stateHolder::updateRelationshipContext,
            onDiscoveryStyleChanged = stateHolder::updateDiscoveryStyle,
            onSave = { scope.launch { stateHolder.saveDraft() } },
            modifier = modifier
        )
    } else if (state.privacyVisible) {
        PrivacyDiscoveryContent(
            state = state,
            onBack = stateHolder::dismissPrivacySettings,
            onGridVisibleChanged = stateHolder::updateGridVisible,
            onSwipeVisibleChanged = stateHolder::updateSwipeVisible,
            onShowDistanceChanged = stateHolder::updateShowDistance,
            onVisibleWhileTravelingChanged = stateHolder::updateVisibleWhileTraveling,
            onSave = { scope.launch { stateHolder.savePrivacySettings() } },
            modifier = modifier
        )
    } else if (state.activityVisible) {
        ActivityControlsContent(
            state = state,
            onBack = stateHolder::dismissActivityControls,
            onShowInViewedListsChanged = stateHolder::updateShowInViewedLists,
            onAllowGridMessagesChanged = stateHolder::updateAllowGridMessages,
            onGridMessagePolicyChanged = stateHolder::updateGridMessagePolicy,
            onAllowFlirtsChanged = stateHolder::updateAllowFlirts,
            onAllowIntroNotesChanged = stateHolder::updateAllowIntroNotes,
            onAllowAlbumRequestsChanged = stateHolder::updateAllowAlbumRequests,
            onSave = { scope.launch { stateHolder.saveActivityControls() } },
            modifier = modifier
        )
    } else if (state.appSettingsVisible) {
        AppSettingsContent(
            state = appSettingsState,
            onBack = stateHolder::dismissAppSettings,
            onThemeModeChanged = appSettingsStateHolder::updateThemeMode,
            onDistanceUnitChanged = appSettingsStateHolder::updateDistanceUnit,
            onNotificationDetailChanged = appSettingsStateHolder::updateNotificationDetail,
            onMessageNotificationsChanged = appSettingsStateHolder::updateMessageNotifications,
            onMatchNotificationsChanged = appSettingsStateHolder::updateMatchNotifications,
            onActivityNotificationsChanged = appSettingsStateHolder::updateActivityNotifications,
            onQuietHoursEnabledChanged = appSettingsStateHolder::updateQuietHoursEnabled,
            onQuietHoursWindowChanged = appSettingsStateHolder::updateQuietHoursWindow,
            onClearError = appSettingsStateHolder::clearError,
            onSave = {
                scope.launch {
                    if (appSettingsStateHolder.saveDraft()) {
                        stateHolder.closeAppSettingsWithFeedback("App settings updated")
                    }
                }
            },
            modifier = modifier
        )
    } else if (state.notificationCenterVisible) {
        NotificationCenterContent(
            profileLinksState = profileLinksState,
            messagesState = messagesState,
            partnerChatInvites = partnerChatInvites,
            viewerUserId = viewerUserId,
            onBack = stateHolder::dismissNotificationCenter,
            onAcceptProfileLinkInvite = onAcceptProfileLinkInvite,
            onDeclineProfileLinkInvite = onDeclineProfileLinkInvite,
            onAcceptPartnerChatInvite = onAcceptPartnerChatInvite,
            onDeclinePartnerChatInvite = onDeclinePartnerChatInvite,
            onClearProfileLinkMessages = onClearProfileLinkMessages,
            onClearMessagesError = onClearMessagesError,
            modifier = modifier
        )
    } else if (state.safetyCenterVisible) {
        ProfileHubSafetyCenterContent(
            state = state,
            onBack = stateHolder::dismissSafetyCenter,
            onUnblockUser = { userId -> scope.launch { stateHolder.unblockUser(userId) } },
            onUnhideProfile = { userId -> scope.launch { stateHolder.unhideProfile(userId) } },
            onClearMessage = stateHolder::clearMessage,
            modifier = modifier
        )
    } else {
        ProfileHubContent(
            state = state,
            appSettingsState = appSettingsState,
            mediaState = mediaState,
            profileLinksState = profileLinksState,
            actionItemCount = actionItemCount,
            partnerChatInviteCount = partnerChatInvites.size,
            profileImageUrl = profileImageUrl,
            onBack = onBack,
            onOpenNotificationCenter = stateHolder::openNotificationCenter,
            onEditProfile = stateHolder::openEditor,
            onOpenPrivacy = stateHolder::openPrivacySettings,
            onOpenActivityControls = stateHolder::openActivityControls,
            onOpenSafetyCenter = {
                stateHolder.openSafetyCenter()
                scope.launch { stateHolder.loadSafetyLists() }
            },
            onOpenAgeVerification = stateHolder::openAgeVerification,
            onOpenAppSettings = {
                appSettingsStateHolder.editFromCurrent()
                stateHolder.openAppSettings()
            },
            onOpenAlbums = onOpenAlbums,
            onLogoutClick = { showLogoutConfirm = true },
            onClearMessage = stateHolder::clearMessage,
            modifier = modifier
        )
    }

    if (state.ageVerificationDialogVisible) {
        AgeVerificationDialog(
            state = state,
            onDismiss = stateHolder::dismissAgeVerification,
            onEditProfile = stateHolder::openEditor,
            onStart = { scope.launch { stateHolder.startAgeVerification() } }
        )
    }

    if (showLogoutConfirm) {
        AlertDialog(
            onDismissRequest = { showLogoutConfirm = false },
            title = { Text("Log out?") },
            text = { Text("Your account stays active.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showLogoutConfirm = false
                        onLogout()
                    }
                ) {
                    Text("Log out")
                }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutConfirm = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun AgeVerificationDialog(
    state: ProfileHubUiState,
    onDismiss: () -> Unit,
    onEditProfile: () -> Unit,
    onStart: () -> Unit
) {
    val profile = state.me?.profile
    val status = state.ageVerificationStatus?.status
    val hasBirthdate = !profile?.birthdate.isNullOrBlank()
    val alreadyVerified = profile?.ageVerified == true || status == "verified"
    val pending = status == "pending"

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Age verification") },
        text = {
            Text(
                text = when {
                    alreadyVerified -> "Your age verification is complete."
                    pending -> "Your age verification is pending review."
                    !hasBirthdate -> "Add your birthdate in Edit profile before starting age verification."
                    else -> "Age verification unlocks age-gated lifestyle filters. By continuing, you confirm you are 18 or older. Only the result and review status are stored."
                }
            )
        },
        confirmButton = {
            when {
                alreadyVerified || pending -> {
                    TextButton(onClick = onDismiss) { Text("Close") }
                }

                !hasBirthdate -> {
                    Button(onClick = onEditProfile) { Text("Edit profile") }
                }

                else -> {
                    Button(
                        onClick = onStart,
                        enabled = !state.isAgeVerificationMutating
                    ) {
                        Text(if (state.isAgeVerificationMutating) "Starting" else "Confirm & start")
                    }
                }
            }
        },
        dismissButton = {
            if (!alreadyVerified && !pending) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    )
}
