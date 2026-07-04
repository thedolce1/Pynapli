package com.pynapli.app.profilehub

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.pynapli.app.appsettings.AppSettingsStateHolder
import com.pynapli.app.design.PynapliConfirmDialog
import com.pynapli.app.media.MediaAlbumsUiState
import com.pynapli.app.messages.MessagesUiState
import com.pynapli.app.profilelink.ProfileLinksUiState
import com.pynapli.shared.network.dto.SupportContactDto
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
    onUnlinkProfileLink: () -> Unit,
    onAcceptPartnerChatInvite: (String) -> Unit,
    onDeclinePartnerChatInvite: (String) -> Unit,
    onClearProfileLinkMessages: () -> Unit,
    onClearMessagesError: () -> Unit,
    onLogout: () -> Unit,
    onOpenStore: () -> Unit = {},
    onPreviewProfile: () -> Unit = {},
    onSetDiscreetIcon: (Boolean) -> Unit = {},
    discreetIconSupported: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val state by stateHolder.state.collectAsState()
    val appSettingsState by appSettingsStateHolder.state.collectAsState()
    val partnerChatInvites = messagesState.partnerChatInvites(viewerUserId)
    val actionItemCount = profileLinksState.incomingInvites.size + partnerChatInvites.size
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    var showLogoutConfirm by remember { mutableStateOf(false) }
    var showDeleteAccountConfirm by remember { mutableStateOf(false) }
    var deleteAccountConfirmation by remember { mutableStateOf("") }

    LaunchedEffect(stateHolder) {
        stateHolder.load()
    }

    if (state.editVisible) {
        ProfileEditContent(
            state = state,
            onBack = stateHolder::dismissEditor,
            onDisplayNameChanged = stateHolder::updateDisplayName,
            onBirthdateChanged = stateHolder::updateBirthdate,
            onBioChanged = stateHolder::updateBio,
            onNeighborhoodChanged = stateHolder::updateNeighborhood,
            onGenderChanged = stateHolder::updateGender,
            onGenderDetailChanged = stateHolder::updateGenderDetail,
            onOrientationChanged = stateHolder::updateOrientation,
            onOrientationDetailChanged = stateHolder::updateOrientationDetail,
            onRelationshipContextChanged = stateHolder::updateRelationshipContext,
            onDiscoveryStyleChanged = stateHolder::updateDiscoveryStyle,
            onToggleLifestyleTag = stateHolder::toggleLifestyleTag,
            onToggleLogisticsTag = stateHolder::toggleLogisticsTag,
            onToggleIntent = stateHolder::toggleIntent,
            onSave = { scope.launch { stateHolder.saveDraft() } },
            onDismissError = stateHolder::clearMessage,
            onManagePhotos = onOpenAlbums,
            profilePhotos = mediaState.profileItems,
            modifier = modifier,
        )
    } else if (state.privacyVisible) {
        PrivacyDiscoveryContent(
            state = state,
            onBack = stateHolder::dismissPrivacySettings,
            onGridVisibleChanged = stateHolder::updateGridVisible,
            onSwipeVisibleChanged = stateHolder::updateSwipeVisible,
            onShowDistanceChanged = stateHolder::updateShowDistance,
            onVisibleWhileTravelingChanged = stateHolder::updateVisibleWhileTraveling,
            onSharePsyncSoloMatchesChanged = stateHolder::updateSharePsyncSoloMatches,
            onSave = { scope.launch { stateHolder.savePrivacySettings() } },
            modifier = modifier,
        )
    } else if (state.partnerVisible) {
        PartnerManagementContent(
            state = state,
            profileLinksState = profileLinksState,
            onBack = stateHolder::dismissPartner,
            onAcceptInvite = onAcceptProfileLinkInvite,
            onDeclineInvite = onDeclineProfileLinkInvite,
            onUnlink = onUnlinkProfileLink,
            onPartnerVisibilityChanged = stateHolder::updatePartnerVisibility,
            onSave = { scope.launch { stateHolder.savePartner() } },
            modifier = modifier,
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
            modifier = modifier,
        )
    } else if (state.appSettingsVisible) {
        AppSettingsContent(
            state = appSettingsState,
            onBack = stateHolder::dismissAppSettings,
            onThemeModeChanged = appSettingsStateHolder::updateThemeMode,
            onDistanceUnitChanged = appSettingsStateHolder::updateDistanceUnit,
            onDiscreetIconChanged = { enabled ->
                appSettingsStateHolder.updateDiscreetIcon(enabled)
                onSetDiscreetIcon(enabled)
            },
            onNotificationDetailChanged = appSettingsStateHolder::updateNotificationDetail,
            onMessageNotificationsChanged = appSettingsStateHolder::updateMessageNotifications,
            onMatchNotificationsChanged = appSettingsStateHolder::updateMatchNotifications,
            onActivityNotificationsChanged = appSettingsStateHolder::updateActivityNotifications,
            onFlirtNotificationsChanged = appSettingsStateHolder::updateFlirtNotifications,
            onAlbumRequestNotificationsChanged = appSettingsStateHolder::updateAlbumRequestNotifications,
            onPartnerInviteNotificationsChanged = appSettingsStateHolder::updatePartnerInviteNotifications,
            onSystemSafetyNotificationsChanged = appSettingsStateHolder::updateSystemSafetyNotifications,
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
            modifier = modifier,
            discreetIconSupported = discreetIconSupported,
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
            modifier = modifier,
        )
    } else if (state.safetyCenterVisible) {
        ProfileHubSafetyCenterContent(
            state = state,
            onBack = stateHolder::dismissSafetyCenter,
            onUnblockUser = { userId -> scope.launch { stateHolder.unblockUser(userId) } },
            onUnhideProfile = { userId -> scope.launch { stateHolder.unhideProfile(userId) } },
            onContactSupport = { contact -> runCatching { uriHandler.openUri(contact.toMailtoUri()) } },
            onClearMessage = stateHolder::clearMessage,
            modifier = modifier,
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
            onPreviewProfile = onPreviewProfile,
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
            onOpenPartner = stateHolder::openPartner,
            onOpenStore = onOpenStore,
            onRequestDataExport = { scope.launch { stateHolder.requestDataExport() } },
            onDeleteAccountClick = {
                deleteAccountConfirmation = ""
                showDeleteAccountConfirm = true
            },
            onLogoutClick = { showLogoutConfirm = true },
            onClearMessage = stateHolder::clearMessage,
            modifier = modifier,
        )
    }

    if (state.ageVerificationDialogVisible) {
        AgeVerificationDialog(
            state = state,
            onDismiss = stateHolder::dismissAgeVerification,
            onEditProfile = stateHolder::openEditor,
            onStart = { scope.launch { stateHolder.startAgeVerification() } },
        )
    }

    if (showLogoutConfirm) {
        PynapliConfirmDialog(
            title = "Log out?",
            body = "Your account stays active.",
            confirmLabel = "Log out",
            onConfirm = {
                showLogoutConfirm = false
                onLogout()
            },
            onDismiss = { showLogoutConfirm = false },
        )
    }

    if (showDeleteAccountConfirm) {
        DeleteAccountDialog(
            confirmation = deleteAccountConfirmation,
            isDeleting = state.isDeletingAccount,
            onConfirmationChanged = { deleteAccountConfirmation = it },
            onDismiss = {
                if (!state.isDeletingAccount) {
                    showDeleteAccountConfirm = false
                    deleteAccountConfirmation = ""
                }
            },
            onDelete = {
                scope.launch {
                    if (stateHolder.deleteAccount(deleteAccountConfirmation)) {
                        showDeleteAccountConfirm = false
                        deleteAccountConfirmation = ""
                        onLogout()
                    }
                }
            },
        )
    }
}

private fun SupportContactDto.toMailtoUri(): String = "mailto:$email?subject=${subject.mailtoEncode()}&body=${body.mailtoEncode()}"

private fun String.mailtoEncode(): String =
    buildString {
        for (char in this@mailtoEncode) {
            when {
                char.isLetterOrDigit() -> append(char)
                char == '-' || char == '_' || char == '.' || char == '~' -> append(char)
                char == ' ' -> append("%20")
                char == '\n' -> append("%0A")
                else -> {
                    append('%')
                    append(
                        char.code
                            .toString(16)
                            .uppercase()
                            .padStart(2, '0'),
                    )
                }
            }
        }
    }

@Composable
private fun DeleteAccountDialog(
    confirmation: String,
    isDeleting: Boolean,
    onConfirmationChanged: (String) -> Unit,
    onDismiss: () -> Unit,
    onDelete: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete account?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "Your profile will be removed from Grid, Swipe, events, viewed lists, and active sessions. Safety and legal retention records may be preserved.",
                )
                OutlinedTextField(
                    value = confirmation,
                    onValueChange = onConfirmationChanged,
                    label = { Text("Type DELETE") },
                    singleLine = true,
                    enabled = !isDeleting,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onDelete,
                enabled = !isDeleting && confirmation.trim() == "DELETE",
            ) {
                Text(if (isDeleting) "Deleting" else "Delete")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isDeleting,
            ) {
                Text("Cancel")
            }
        },
    )
}

@Composable
private fun AgeVerificationDialog(
    state: ProfileHubUiState,
    onDismiss: () -> Unit,
    onEditProfile: () -> Unit,
    onStart: () -> Unit,
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
                text =
                    when {
                        alreadyVerified -> "Your age verification is complete."
                        pending -> "Your age verification is pending review."
                        !hasBirthdate -> "Add your birthdate in Edit profile before starting age verification."
                        else -> "Age verification unlocks age-gated lifestyle filters. By continuing, you confirm you are 18 or older. Only the result and review status are stored."
                    },
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
                        enabled = !state.isAgeVerificationMutating,
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
        },
    )
}
