package com.pynapli.app.profilehub

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.CardMembership
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pynapli.app.appsettings.AppSettingsStateHolder
import com.pynapli.app.appsettings.AppSettingsUiState
import com.pynapli.app.conversation.titleFor
import com.pynapli.app.media.MediaAlbumsUiState
import com.pynapli.app.messages.MessagesUiState
import com.pynapli.app.profilelink.ProfileLinksUiState
import com.pynapli.app.profilelink.ProfileLinkUi
import com.pynapli.shared.network.dto.ConversationDto
import kotlinx.coroutines.launch

private val ProfileSurfaceBottomPadding = 118.dp

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
private fun ProfileHubContent(
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
    onOpenPrivacy: () -> Unit,
    onOpenActivityControls: () -> Unit,
    onOpenAgeVerification: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onOpenAlbums: () -> Unit,
    onLogoutClick: () -> Unit,
    onClearMessage: () -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = ProfileSurfaceBottomPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            ProfileHubTopBar(
                onBack = onBack,
                actionCount = actionItemCount,
                onNotificationsClick = onOpenNotificationCenter
            )
        }

        item {
            ProfileSummary(
                state = state,
                profileImageUrl = profileImageUrl,
                linkedPartnerLabel = profileLinksState.current?.displayLabel,
                profilePhotoCount = mediaState.profileItems.size,
                privatePhotoCount = mediaState.privateItems.size
            )
        }

        state.errorMessage?.let { message ->
            item {
                ProfileHubMessageBand(
                    message = message,
                    isError = true,
                    onDismiss = onClearMessage
                )
            }
        }

        state.feedbackMessage?.let { message ->
            item {
                ProfileHubMessageBand(
                    message = message,
                    isError = false,
                    onDismiss = onClearMessage
                )
            }
        }

        item {
            ProfileHubSection("Notifications") {
                ProfileHubRow(
                    icon = Icons.Filled.Notifications,
                    label = "Action center",
                    value = actionCenterSummary(actionItemCount),
                    onClick = onOpenNotificationCenter
                )
            }
        }

        item {
            ProfileHubSection("Profile") {
                ProfileHubRow(
                    icon = Icons.Filled.Edit,
                    label = "Edit profile",
                    value = profileBasicsSummary(state),
                    onClick = onEditProfile
                )
                ProfileHubRow(
                    icon = Icons.Filled.Visibility,
                    label = "Preview profile",
                    value = "Public view"
                )
            }
        }

        item {
            ProfileHubSection("Partner") {
                ProfileHubRow(
                    icon = Icons.Filled.People,
                    label = "Linked partner",
                    value = profileLinksState.current?.displayLabel ?: pendingPartnerStatus(profileLinksState),
                    onClick = onOpenAlbums
                )
                ProfileHubRow(
                    icon = Icons.Filled.ChatBubble,
                    label = "Partner chat invites",
                    value = partnerChatInviteSummary(partnerChatInviteCount)
                )
            }
        }

        item {
            ProfileHubSection("Albums") {
                ProfileHubRow(
                    icon = Icons.Filled.PhotoLibrary,
                    label = "Photos and private album",
                    value = albumSummary(mediaState),
                    onClick = onOpenAlbums
                )
                ProfileHubRow(
                    icon = Icons.Filled.Lock,
                    label = "Album access",
                    value = grantSummary(mediaState)
                )
            }
        }

        item {
            ProfileHubSection("Subscription") {
                ProfileHubRow(
                    icon = Icons.Filled.CardMembership,
                    label = "Current plan",
                    value = "Free"
                )
                ProfileHubRow(
                    icon = Icons.Filled.People,
                    label = "Linked partner benefit",
                    value = if (profileLinksState.current == null) "No active link" else "Eligible"
                )
            }
        }

        item {
            ProfileHubSection("Privacy") {
                ProfileHubRow(
                    icon = Icons.Filled.Visibility,
                    label = "Visibility",
                    value = visibilitySummary(state),
                    onClick = onOpenPrivacy
                )
                ProfileHubRow(
                    icon = Icons.Filled.Tune,
                    label = "Distance display",
                    value = distanceSummary(state),
                    onClick = onOpenPrivacy
                )
            }
        }

        item {
            ProfileHubSection("Safety") {
                ProfileHubRow(
                    icon = Icons.Filled.Security,
                    label = "Safety center",
                    value = "Guidelines"
                )
                ProfileHubRow(
                    icon = Icons.Filled.LocalFireDepartment,
                    label = "Activity controls",
                    value = activityControlsSummary(state),
                    onClick = onOpenActivityControls
                )
                ProfileHubRow(
                    icon = Icons.Filled.Lock,
                    label = "Age verification",
                    value = ageVerificationSummary(state),
                    onClick = onOpenAgeVerification
                )
            }
        }

        item {
            ProfileHubSection("Account") {
                ProfileHubRow(
                    icon = Icons.Filled.Settings,
                    label = "App settings",
                    value = appSettingsSummary(appSettingsState),
                    onClick = onOpenAppSettings
                )
                ProfileHubRow(
                    icon = Icons.AutoMirrored.Filled.Logout,
                    label = "Log out",
                    value = "Keep account",
                    onClick = onLogoutClick
                )
            }
        }
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

@Composable
private fun NotificationCenterContent(
    profileLinksState: ProfileLinksUiState,
    messagesState: MessagesUiState,
    partnerChatInvites: List<ConversationDto>,
    viewerUserId: String?,
    onBack: () -> Unit,
    onAcceptProfileLinkInvite: (String) -> Unit,
    onDeclineProfileLinkInvite: (String) -> Unit,
    onAcceptPartnerChatInvite: (String) -> Unit,
    onDeclinePartnerChatInvite: (String) -> Unit,
    onClearProfileLinkMessages: () -> Unit,
    onClearMessagesError: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isEmpty = profileLinksState.incomingInvites.isEmpty() && partnerChatInvites.isEmpty()

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = ProfileSurfaceBottomPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            ProfileHubTopBar(
                title = "Notifications",
                onBack = onBack
            )
        }

        profileLinksState.errorMessage?.let { message ->
            item {
                ProfileHubMessageBand(
                    message = message,
                    isError = true,
                    onDismiss = onClearProfileLinkMessages
                )
            }
        }

        messagesState.errorMessage?.let { message ->
            item {
                ProfileHubMessageBand(
                    message = message,
                    isError = true,
                    onDismiss = onClearMessagesError
                )
            }
        }

        if (isEmpty) {
            item {
                EmptyActionCenter()
            }
        }

        if (profileLinksState.incomingInvites.isNotEmpty()) {
            item {
                ProfileHubSection("Profile link invites") {
                    profileLinksState.incomingInvites.forEach { invite ->
                        ProfileLinkInviteRow(
                            invite = invite,
                            isMutating = invite.linkId in profileLinksState.mutatingLinkIds,
                            onAccept = { onAcceptProfileLinkInvite(invite.linkId) },
                            onDecline = { onDeclineProfileLinkInvite(invite.linkId) }
                        )
                    }
                }
            }
        }

        if (partnerChatInvites.isNotEmpty()) {
            item {
                ProfileHubSection("Partner chat invites") {
                    partnerChatInvites.forEach { conversation ->
                        PartnerChatInviteRow(
                            conversation = conversation,
                            viewerUserId = viewerUserId,
                            isMutating = conversation.conversationId in messagesState.mutatingPartnerInviteConversationIds,
                            onAccept = { onAcceptPartnerChatInvite(conversation.conversationId) },
                            onDecline = { onDeclinePartnerChatInvite(conversation.conversationId) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyActionCenter() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = "No action items",
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = "Profile link and partner chat invites will appear here.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
private fun ProfileLinkInviteRow(
    invite: ProfileLinkUi,
    isMutating: Boolean,
    onAccept: () -> Unit,
    onDecline: () -> Unit
) {
    ActionCenterRow(
        icon = Icons.Filled.People,
        title = "Profile link invite",
        subtitle = "${invite.displayLabel} wants to link profiles.",
        isMutating = isMutating,
        acceptLabel = "Accept",
        declineLabel = "Decline",
        onAccept = onAccept,
        onDecline = onDecline
    )
}

@Composable
private fun PartnerChatInviteRow(
    conversation: ConversationDto,
    viewerUserId: String?,
    isMutating: Boolean,
    onAccept: () -> Unit,
    onDecline: () -> Unit
) {
    ActionCenterRow(
        icon = Icons.Filled.ChatBubble,
        title = "Partner chat invite",
        subtitle = "Join ${conversation.titleFor(viewerUserId)} when you are ready.",
        isMutating = isMutating,
        acceptLabel = "Join",
        declineLabel = "Pass",
        onAccept = onAccept,
        onDecline = onDecline
    )
}

@Composable
private fun ActionCenterRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    isMutating: Boolean,
    acceptLabel: String,
    declineLabel: String,
    onAccept: () -> Unit,
    onDecline: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(
                    text = title,
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = subtitle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (isMutating) {
                CircularProgressIndicator(
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(
                onClick = onDecline,
                enabled = !isMutating
            ) {
                Text(declineLabel)
            }
            TextButton(
                onClick = onAccept,
                enabled = !isMutating
            ) {
                Text(acceptLabel)
            }
        }
    }
}

@Composable
private fun AppSettingsContent(
    state: AppSettingsUiState,
    onBack: () -> Unit,
    onThemeModeChanged: (String) -> Unit,
    onDistanceUnitChanged: (String) -> Unit,
    onNotificationDetailChanged: (String) -> Unit,
    onMessageNotificationsChanged: (Boolean) -> Unit,
    onMatchNotificationsChanged: (Boolean) -> Unit,
    onActivityNotificationsChanged: (Boolean) -> Unit,
    onQuietHoursEnabledChanged: (Boolean) -> Unit,
    onQuietHoursWindowChanged: (Int, Int) -> Unit,
    onClearError: () -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        ProfileHubTopBar(
            title = "App settings",
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
                        onDismiss = onClearError
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
                            onSelected = onThemeModeChanged
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
                            onSelected = onDistanceUnitChanged
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
                            onSelected = onNotificationDetailChanged
                        )
                    }
                    PrivacyToggleRow(
                        label = "Messages",
                        value = if (state.draft.messageNotifications) "On" else "Off",
                        checked = state.draft.messageNotifications,
                        onCheckedChange = onMessageNotificationsChanged
                    )
                    PrivacyToggleRow(
                        label = "Matches",
                        value = if (state.draft.matchNotifications) "On" else "Off",
                        checked = state.draft.matchNotifications,
                        onCheckedChange = onMatchNotificationsChanged
                    )
                    PrivacyToggleRow(
                        label = "Activity",
                        value = if (state.draft.activityNotifications) "On" else "Off",
                        checked = state.draft.activityNotifications,
                        onCheckedChange = onActivityNotificationsChanged
                    )
                    PrivacyToggleRow(
                        label = "Quiet hours",
                        value = quietHoursSummary(state.draft),
                        checked = state.draft.quietHoursEnabled,
                        onCheckedChange = onQuietHoursEnabledChanged
                    )
                    if (state.draft.quietHoursEnabled) {
                        quietHoursOptions.forEach { option ->
                            DiscoveryStyleRow(
                                label = option.label,
                                value = option.value,
                                selected = state.draft.quietHoursStartMinute == option.startMinute &&
                                    state.draft.quietHoursEndMinute == option.endMinute,
                                onSelected = { onQuietHoursWindowChanged(option.startMinute, option.endMinute) }
                            )
                        }
                    }
                }
            }
        }

        Button(
            onClick = onSave,
            enabled = !state.isSaving,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 16.dp)
        ) {
            if (state.isSaving) {
                CircularProgressIndicator(
                    color = MaterialTheme.colorScheme.onPrimary,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(18.dp)
                )
            } else {
                Text("Save settings")
            }
        }
    }
}

@Composable
private fun ActivityControlsContent(
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

        Button(
            onClick = onSave,
            enabled = !state.isSaving,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 16.dp)
        ) {
            if (state.isSaving) {
                CircularProgressIndicator(
                    color = MaterialTheme.colorScheme.onPrimary,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(18.dp)
                )
            } else {
                Text("Save controls")
            }
        }
    }
}

@Composable
private fun PrivacyDiscoveryContent(
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
                    // is active. Off → invisible while traveling.
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

        Button(
            onClick = onSave,
            enabled = !state.isSaving,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 16.dp)
        ) {
            if (state.isSaving) {
                CircularProgressIndicator(
                    color = MaterialTheme.colorScheme.onPrimary,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(18.dp)
                )
            } else {
                Text("Save privacy")
            }
        }
    }
}

@Composable
private fun ProfileEditContent(
    state: ProfileHubUiState,
    onBack: () -> Unit,
    onDisplayNameChanged: (String) -> Unit,
    onBirthdateChanged: (String) -> Unit,
    onGenderChanged: (String) -> Unit,
    onOrientationChanged: (String) -> Unit,
    onRelationshipContextChanged: (String) -> Unit,
    onDiscoveryStyleChanged: (String) -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        ProfileHubTopBar(
            title = "Edit profile",
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
                ProfileHubSection("Basics") {
                    ProfileTextField(
                        value = state.draft.displayName,
                        onValueChange = onDisplayNameChanged,
                        label = "Display name"
                    )
                    ProfileTextField(
                        value = state.draft.birthdate,
                        onValueChange = onBirthdateChanged,
                        label = "Birthdate",
                        supportingText = "YYYY-MM-DD"
                    )
                    ProfileTextField(
                        value = state.draft.gender,
                        onValueChange = onGenderChanged,
                        label = "Gender"
                    )
                    ProfileTextField(
                        value = state.draft.orientation,
                        onValueChange = onOrientationChanged,
                        label = "Orientation"
                    )
                    ProfileTextField(
                        value = state.draft.relationshipContext,
                        onValueChange = onRelationshipContextChanged,
                        label = "Relationship context"
                    )
                }
            }

            item {
                ProfileHubSection("Discovery style") {
                    discoveryStyleOptions.forEach { option ->
                        DiscoveryStyleRow(
                            label = option.label,
                            value = option.value,
                            selected = state.draft.discoveryStyle == option.value,
                            onSelected = onDiscoveryStyleChanged
                        )
                    }
                }
            }
        }

        Button(
            onClick = onSave,
            enabled = !state.isSaving,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 16.dp)
        ) {
            if (state.isSaving) {
                CircularProgressIndicator(
                    color = MaterialTheme.colorScheme.onPrimary,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(18.dp)
                )
            } else {
                Text("Save profile")
            }
        }
    }
}

@Composable
private fun ProfileTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    supportingText: String? = null
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        supportingText = supportingText?.let { { Text(it) } },
        singleLine = true,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 5.dp)
    )
}

@Composable
private fun DiscoveryStyleRow(
    label: String,
    value: String,
    selected: Boolean,
    onSelected: (String) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelected(value) }
            .padding(horizontal = 12.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(CircleShape)
                .background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent)
                .border(
                    width = 1.dp,
                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    shape = CircleShape
                )
        )
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
    }
}
