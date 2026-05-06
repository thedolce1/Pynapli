package com.pynapli.app.profilehub

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.CardMembership
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.pynapli.app.appsettings.AppSettingsDraft
import com.pynapli.app.appsettings.AppSettingsStateHolder
import com.pynapli.app.appsettings.AppSettingsUiState
import com.pynapli.app.conversation.titleFor
import com.pynapli.app.media.MediaAlbumsUiState
import com.pynapli.app.messages.MessagesUiState
import com.pynapli.app.profilelink.ProfileLinksUiState
import com.pynapli.app.profilelink.ProfileLinkUi
import com.pynapli.shared.network.dto.ConversationDto
import com.pynapli.shared.settings.AppSettings
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

@Composable
private fun ProfileHubTopBar(
    title: String = "Profile",
    onBack: () -> Unit,
    actionCount: Int = 0,
    onNotificationsClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        IconButton(
            onClick = onBack,
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                tint = MaterialTheme.colorScheme.onSurface
            )
        }
        Text(
            text = title,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (onNotificationsClick != null) {
            IconButton(
                onClick = onNotificationsClick,
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                if (actionCount > 0) {
                    BadgedBox(
                        badge = {
                            Badge {
                                Text(text = if (actionCount > 9) "9+" else actionCount.toString())
                            }
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Notifications,
                            contentDescription = "Notifications",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                } else {
                    Icon(
                        imageVector = Icons.Filled.Notifications,
                        contentDescription = "Notifications",
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

@Composable
private fun ProfileSummary(
    state: ProfileHubUiState,
    profileImageUrl: String?,
    linkedPartnerLabel: String?,
    profilePhotoCount: Int,
    privatePhotoCount: Int
) {
    val profile = state.me?.profile
    val userId = state.me?.user?.userId
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ProfileAvatar(profileImageUrl = profileImageUrl)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = profile?.displayName?.takeIf { it.isNotBlank() } ?: "Your profile",
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = userId?.let { "Member ${it.take(8)}" } ?: if (state.isLoading) "Loading" else "Signed in",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            StatusPill(text = "Free")
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SummaryMetric(
                label = "Public",
                value = profilePhotoCount.toString(),
                modifier = Modifier.weight(1f)
            )
            SummaryMetric(
                label = "Private",
                value = privatePhotoCount.toString(),
                modifier = Modifier.weight(1f)
            )
            SummaryMetric(
                label = "Partner",
                value = linkedPartnerLabel?.take(9) ?: "None",
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun ProfileAvatar(
    profileImageUrl: String?
) {
    Box(
        modifier = Modifier
            .size(62.dp)
            .clip(CircleShape)
            .background(
                Brush.linearGradient(
                    listOf(Color(0xFF4E342E), Color(0xFFC39A49))
                )
            )
            .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        if (profileImageUrl != null) {
            AsyncImage(
                model = profileImageUrl,
                contentDescription = "Profile photo",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Icon(
                imageVector = Icons.Filled.Person,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(30.dp)
            )
        }
    }
}

@Composable
private fun SummaryMetric(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = value,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun StatusPill(
    text: String
) {
    Text(
        text = text,
        color = MaterialTheme.colorScheme.onPrimary,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.primary)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}

@Composable
private fun ProfileHubMessageBand(
    message: String,
    isError: Boolean,
    onDismiss: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (isError) Color(0xFF351317) else Color(0xFF172A1F))
            .border(
                width = 1.dp,
                color = if (isError) MaterialTheme.colorScheme.error else Color(0xFF275C3B),
                shape = RoundedCornerShape(8.dp)
            )
            .clickable(onClick = onDismiss)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = message,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun ProfileHubSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = title,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(start = 2.dp)
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surface)
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp)),
            content = content
        )
    }
}

@Composable
private fun ProfileHubRow(
    icon: ImageVector,
    label: String,
    value: String,
    onClick: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick == null) Modifier else Modifier.clickable(onClick = onClick))
            .padding(horizontal = 12.dp, vertical = 13.dp),
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
                text = label,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = value,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (onClick != null) {
            Icon(
                imageVector = Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun PrivacyToggleRow(
    label: String,
    value: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 12.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Text(
                text = label,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = value,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = null
        )
    }
}

private fun pendingPartnerStatus(state: ProfileLinksUiState): String = when {
    state.incomingInvites.isNotEmpty() -> "${state.incomingInvites.size} invite"
    state.outgoingInvites.isNotEmpty() -> "Invite sent"
    state.isLoading -> "Loading"
    else -> "Not linked"
}

private fun actionCenterSummary(actionItemCount: Int): String =
    if (actionItemCount == 0) "No action items" else "$actionItemCount action item${if (actionItemCount == 1) "" else "s"}"

private fun partnerChatInviteSummary(count: Int): String =
    if (count == 0) "Ask every time" else "$count pending invite${if (count == 1) "" else "s"}"

private fun albumSummary(state: MediaAlbumsUiState): String =
    "${state.profileItems.size} public, ${state.privateItems.size} private"

private fun grantSummary(state: MediaAlbumsUiState): String =
    if (state.privateGrants.isEmpty()) "No active grants" else "${state.privateGrants.size} active"

private fun profileBasicsSummary(state: ProfileHubUiState): String {
    val profile = state.me?.profile ?: return if (state.isLoading) "Loading" else "Set up"
    val completeFields = listOf(
        profile.displayName,
        profile.birthdate,
        profile.gender,
        profile.orientation,
        profile.relationshipContext,
        profile.discoveryStyle
    ).count { !it.isNullOrBlank() }
    return "$completeFields of 6 fields"
}

private fun visibilitySummary(state: ProfileHubUiState): String {
    val settings = state.me?.discoverySettings ?: return if (state.isLoading) "Loading" else "Set up"
    return when {
        settings.gridVisible && settings.swipeVisible -> "Grid + swipe"
        settings.gridVisible -> "Grid only"
        settings.swipeVisible -> "Swipe only"
        else -> "Hidden"
    }
}

private fun distanceSummary(state: ProfileHubUiState): String {
    val settings = state.me?.discoverySettings ?: return if (state.isLoading) "Loading" else "Set up"
    return if (settings.showDistance) "Shown" else "Hidden"
}

private fun activityControlsSummary(state: ProfileHubUiState): String {
    val settings = state.me?.discoverySettings ?: return if (state.isLoading) "Loading" else "Set up"
    val viewed = if (settings.showInViewedLists) "Viewed on" else "Viewed hidden"
    val allowedSignals = listOf(
        settings.allowGridMessages && settings.gridMessagePolicy != "no_one",
        settings.allowFlirts,
        settings.allowIntroNotes,
        settings.allowAlbumRequests
    ).count { it }
    return "$viewed, $allowedSignals allowed"
}

private fun appSettingsSummary(state: AppSettingsUiState): String {
    if (state.isLoading) return "Loading"
    return "${themeModeLabel(state.settings.themeMode)}, ${distanceUnitShortLabel(state.settings.distanceUnit)}"
}

private fun quietHoursSummary(draft: AppSettingsDraft): String =
    if (!draft.quietHoursEnabled) {
        "Off"
    } else {
        "${minuteLabel(draft.quietHoursStartMinute)}-${minuteLabel(draft.quietHoursEndMinute)}"
    }

private fun minuteLabel(minuteOfDay: Int): String {
    val hour24 = (minuteOfDay / 60).coerceIn(0, 23)
    val minute = (minuteOfDay % 60).coerceIn(0, 59)
    val suffix = if (hour24 < 12) "AM" else "PM"
    val hour12 = when (val normalized = hour24 % 12) {
        0 -> 12
        else -> normalized
    }
    return if (minute == 0) {
        "$hour12 $suffix"
    } else {
        "$hour12:${minute.toString().padStart(2, '0')} $suffix"
    }
}

private fun gridMessagePolicyLabel(value: String): String = when (value) {
    "matches_only" -> "Matches only"
    "no_one" -> "Off"
    else -> "Everyone"
}

private fun themeModeLabel(value: String): String = when (value) {
    AppSettings.THEME_LIGHT -> "Light"
    AppSettings.THEME_SYSTEM -> "System"
    else -> "Dark"
}

private fun distanceUnitShortLabel(value: String): String = when (value) {
    AppSettings.UNIT_IMPERIAL -> "mi"
    else -> "km"
}

private data class DiscoveryStyleOption(
    val value: String,
    val label: String
)

private data class QuietHoursOption(
    val value: String,
    val label: String,
    val startMinute: Int,
    val endMinute: Int
)

private val discoveryStyleOptions = listOf(
    DiscoveryStyleOption("solo", "Solo"),
    DiscoveryStyleOption("together", "Together"),
    DiscoveryStyleOption("both", "Solo or together"),
    DiscoveryStyleOption("social", "Social"),
    DiscoveryStyleOption("ask_in_chat", "Ask in chat")
)

private val gridMessagePolicyOptions = listOf(
    DiscoveryStyleOption("everyone", "Everyone"),
    DiscoveryStyleOption("matches_only", "Matches only"),
    DiscoveryStyleOption("no_one", "No first messages")
)

private val themeModeOptions = listOf(
    DiscoveryStyleOption(AppSettings.THEME_DARK, "Dark"),
    DiscoveryStyleOption(AppSettings.THEME_LIGHT, "Light"),
    DiscoveryStyleOption(AppSettings.THEME_SYSTEM, "System")
)

private val distanceUnitOptions = listOf(
    DiscoveryStyleOption(AppSettings.UNIT_METRIC, "Kilometers"),
    DiscoveryStyleOption(AppSettings.UNIT_IMPERIAL, "Miles")
)

private val notificationDetailOptions = listOf(
    DiscoveryStyleOption(AppSettings.NOTIFICATION_DISCREET, "Discreet"),
    DiscoveryStyleOption(AppSettings.NOTIFICATION_STANDARD, "Standard")
)

private val quietHoursOptions = listOf(
    QuietHoursOption("22:8", "10 PM-8 AM", 22 * 60, 8 * 60),
    QuietHoursOption("23:7", "11 PM-7 AM", 23 * 60, 7 * 60),
    QuietHoursOption("0:8", "Midnight-8 AM", 0, 8 * 60)
)
