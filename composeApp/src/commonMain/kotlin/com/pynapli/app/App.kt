package com.pynapli.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material.icons.filled.Style
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pynapli.app.activity.ActivityScreen
import com.pynapli.app.activity.ActivityStateHolder
import com.pynapli.app.activity.ActivityTabUi
import com.pynapli.app.appsettings.AppSettingsStateHolder
import com.pynapli.app.auth.AuthScreen
import com.pynapli.app.auth.AuthStateHolder
import com.pynapli.app.auth.DevLoginCredentials
import com.pynapli.app.conversation.ConversationSheet
import com.pynapli.app.conversation.titleFor
import com.pynapli.app.conversation.ConversationStateHolder
import com.pynapli.app.design.PynapliTheme
import com.pynapli.app.grid.GridScreen
import com.pynapli.app.grid.GridStateHolder
import com.pynapli.app.location.LocationStateHolder
import com.pynapli.app.media.MediaAlbumKindUi
import com.pynapli.app.media.MediaAlbumsScreen
import com.pynapli.app.media.MediaAlbumsStateHolder
import com.pynapli.app.media.primaryOwnerProfilePhotoUrl
import com.pynapli.app.messages.MessagesScreen
import com.pynapli.app.messages.MessagesStateHolder
import com.pynapli.app.profile.ProfileDetailStateHolder
import com.pynapli.app.profilehub.ProfileHubScreen
import com.pynapli.app.profilehub.ProfileHubStateHolder
import com.pynapli.app.profilelink.ProfileLinksStateHolder
import com.pynapli.app.store.StoreScreen
import com.pynapli.app.store.StoreStateHolder
import com.pynapli.app.swipe.SwipeScreen
import com.pynapli.app.swipe.SwipeStateHolder
import com.pynapli.shared.network.dto.ActivityEventDto
import com.pynapli.shared.network.dto.ConversationDto
import com.pynapli.shared.network.dto.MatchDto
import com.pynapli.shared.settings.AppSettings
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun PynapliApp(
    authStateHolder: AuthStateHolder,
    appSettingsStateHolder: AppSettingsStateHolder,
    gridStateHolder: GridStateHolder,
    profileDetailStateHolder: ProfileDetailStateHolder,
    conversationStateHolder: ConversationStateHolder,
    messagesStateHolder: MessagesStateHolder,
    activityStateHolder: ActivityStateHolder,
    locationStateHolder: LocationStateHolder,
    mediaAlbumsStateHolder: MediaAlbumsStateHolder,
    profileHubStateHolder: ProfileHubStateHolder,
    profileLinksStateHolder: ProfileLinksStateHolder,
    swipeStateHolder: SwipeStateHolder,
    storeStateHolder: StoreStateHolder,
    savedGridFilterStateHolder: com.pynapli.app.grid.SavedGridFilterStateHolder? = null,
    savedSwipeFilterStateHolder: com.pynapli.app.swipe.SavedSwipeFilterStateHolder? = null,
    topPicksStateHolder: com.pynapli.app.swipe.TopPicksStateHolder? = null,
    travelLocationStateHolder: com.pynapli.app.location.TravelLocationStateHolder? = null,
    tonightAvailabilityStateHolder: com.pynapli.app.grid.TonightAvailabilityStateHolder? = null,
    deepLinkStateHolder: AppDeepLinkStateHolder? = null,
    onUseCurrentLocation: () -> Unit,
    onPickMediaForAlbum: (MediaAlbumKindUi) -> Unit = {},
    devLoginCredentials: DevLoginCredentials? = null
) {
    val appSettingsState by appSettingsStateHolder.state.collectAsState()

    LaunchedEffect(appSettingsStateHolder) {
        appSettingsStateHolder.load()
    }

    PynapliTheme(themeMode = appSettingsState.settings.themeMode) {
        val authState by authStateHolder.state.collectAsState()
        val locationState by locationStateHolder.state.collectAsState()
        val conversationState by conversationStateHolder.state.collectAsState()
        val messagesState by messagesStateHolder.state.collectAsState()
        val activityState by activityStateHolder.state.collectAsState()
        val mediaAlbumsState by mediaAlbumsStateHolder.state.collectAsState()
        val profileLinksState by profileLinksStateHolder.state.collectAsState()
        val profileHubState by profileHubStateHolder.state.collectAsState()
        val scope = rememberCoroutineScope()
        var selectedTab by remember { mutableStateOf(PynapliTab.Grid) }
        var profileSurface by remember { mutableStateOf(ProfileSurface.None) }
        var activeNotification by remember { mutableStateOf<InAppNotificationUi?>(null) }
        var messageUnreadSnapshot by remember { mutableStateOf<Map<String, Int>?>(null) }
        var activityEventSnapshot by remember { mutableStateOf<Set<String>?>(null) }

        LaunchedEffect(authStateHolder) {
            authStateHolder.restoreSession()
        }

        LaunchedEffect(authState.authenticated, appSettingsStateHolder) {
            if (authState.authenticated) {
                appSettingsStateHolder.load()
            }
        }

        LaunchedEffect(authState.authenticated) {
            if (!authState.authenticated) {
                profileSurface = ProfileSurface.None
                activeNotification = null
                messageUnreadSnapshot = null
                activityEventSnapshot = null
                activityStateHolder.reset()
                profileHubStateHolder.reset()
                profileLinksStateHolder.reset()
            }
        }

        LaunchedEffect(authState.authenticated, messagesStateHolder) {
            if (authState.authenticated) {
                messagesStateHolder.load()
            }
        }

        LaunchedEffect(authState.authenticated, mediaAlbumsStateHolder) {
            if (authState.authenticated) {
                mediaAlbumsStateHolder.load()
            }
        }

        LaunchedEffect(authState.authenticated, profileHubStateHolder) {
            if (authState.authenticated) {
                profileHubStateHolder.load()
            }
        }

        LaunchedEffect(authState.authenticated, profileLinksStateHolder) {
            if (authState.authenticated) {
                profileLinksStateHolder.load()
            }
        }

        LaunchedEffect(authState.authenticated, deepLinkStateHolder) {
            val holder = deepLinkStateHolder ?: return@LaunchedEffect
            if (!authState.authenticated) return@LaunchedEffect
            holder.state.collect { command ->
                if (command == null) return@collect
                profileSurface = ProfileSurface.None
                when (command.target) {
                    AppDeepLinkStateHolder.TARGET_ACTIVITY -> {
                        selectedTab = PynapliTab.Activity
                        val activityTab = command.activityTab
                        if (activityTab != null) {
                            activityStateHolder.selectTab(activityTab)
                        } else {
                            activityStateHolder.load()
                        }
                        activityStateHolder.focusEvent(command.activityEventId)
                    }

                    AppDeepLinkStateHolder.TARGET_MESSAGES -> {
                        selectedTab = PynapliTab.Messages
                        messagesStateHolder.refreshSilently()
                        command.conversationId?.let { conversationId ->
                            conversationStateHolder.openConversationById(
                                conversationId = conversationId,
                                viewerUserId = authState.session?.userId,
                                title = "Conversation"
                            )
                        }
                    }
                }
                holder.markHandled(command.commandId)
            }
        }

        LaunchedEffect(authState.authenticated, messagesStateHolder) {
            if (authState.authenticated) {
                while (true) {
                    delay(INBOX_REFRESH_INTERVAL_MS)
                    messagesStateHolder.refreshSilently()
                }
            }
        }

        LaunchedEffect(authState.authenticated, activityStateHolder) {
            if (authState.authenticated) {
                activityStateHolder.refreshSummarySilently()
                while (true) {
                    delay(ACTIVITY_SUMMARY_REFRESH_INTERVAL_MS)
                    activityStateHolder.refreshSummarySilently()
                }
            }
        }

        LaunchedEffect(
            authState.authenticated,
            appSettingsState.settings.activityNotifications,
            activityStateHolder
        ) {
            if (authState.authenticated && appSettingsState.settings.activityNotifications) {
                activityStateHolder.refreshSilently()
                while (true) {
                    delay(ACTIVITY_REFRESH_INTERVAL_MS)
                    activityStateHolder.refreshSilently()
                }
            }
        }

        LaunchedEffect(
            conversationState.detail?.conversation?.conversationId,
            conversationState.visible,
            conversationStateHolder
        ) {
            if (conversationState.visible && conversationState.detail != null) {
                while (true) {
                    delay(OPEN_CONVERSATION_REFRESH_INTERVAL_MS)
                    conversationStateHolder.refreshOpenConversation()
                    messagesStateHolder.refreshSilently()
                }
            }
        }

        LaunchedEffect(
            messagesState.conversations,
            authState.authenticated,
            appSettingsState.settings.messageNotifications,
            appSettingsState.settings.notificationDetail,
            authState.session?.userId
        ) {
            if (!authState.authenticated) return@LaunchedEffect
            val currentUnread = messagesState.conversations.associate { conversation ->
                conversation.conversationId to conversation.unreadCount
            }
            val previousUnread = messageUnreadSnapshot
            if (previousUnread == null) {
                messageUnreadSnapshot = currentUnread
                return@LaunchedEffect
            }

            if (appSettingsState.settings.messageNotifications) {
                val updatedConversation = messagesState.conversations.firstOrNull { conversation ->
                    conversation.unreadCount > (previousUnread[conversation.conversationId] ?: 0)
                }
                if (updatedConversation != null) {
                    activeNotification = updatedConversation.toMessageNotification(
                        viewerUserId = authState.session?.userId,
                        settings = appSettingsState.settings
                    )
                }
            }
            messageUnreadSnapshot = currentUnread
        }

        LaunchedEffect(
            activityState.items,
            authState.authenticated,
            appSettingsState.settings.activityNotifications,
            appSettingsState.settings.notificationDetail
        ) {
            if (!authState.authenticated) return@LaunchedEffect
            val currentIds = activityState.items.mapTo(mutableSetOf()) { item -> item.eventId }
            val previousIds = activityEventSnapshot
            if (previousIds == null) {
                activityEventSnapshot = currentIds
                return@LaunchedEffect
            }

            if (appSettingsState.settings.activityNotifications) {
                val newEvent = activityState.items.firstOrNull { item -> item.eventId !in previousIds }
                if (newEvent != null) {
                    activeNotification = newEvent.toActivityNotification(appSettingsState.settings)
                }
            }
            activityEventSnapshot = currentIds
        }

        activeNotification?.let { notification ->
            LaunchedEffect(notification.id) {
                delay(IN_APP_NOTIFICATION_DURATION_MS)
                if (activeNotification?.id == notification.id) {
                    activeNotification = null
                }
            }
        }

        if (authState.isCheckingSession) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
            ) {
                Text(
                    text = "Pynapli",
                    color = MaterialTheme.colorScheme.primary
                )
            }
            return@PynapliTheme
        }

        if (!authState.authenticated) {
            AuthScreen(
                state = authState,
                onEmailChanged = authStateHolder::updateEmail,
                onPasswordChanged = authStateHolder::updatePassword,
                onBirthdateChanged = authStateHolder::updateBirthdate,
                onAcceptedAgeAndTermsChanged = authStateHolder::updateAcceptedAgeAndTerms,
                onModeSelected = authStateHolder::switchMode,
                onSubmit = {
                    scope.launch { authStateHolder.submit() }
                },
                onDevLogin = devLoginCredentials?.let { credentials ->
                    { scope.launch { authStateHolder.submitDevLogin(credentials) } }
                }
            )
            return@PynapliTheme
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                val profileImageUrl = mediaAlbumsState.primaryOwnerProfilePhotoUrl()
                val distanceUnit = appSettingsState.settings.distanceUnit

                if (profileSurface == ProfileSurface.Hub) {
                    ProfileHubScreen(
                        stateHolder = profileHubStateHolder,
                        appSettingsStateHolder = appSettingsStateHolder,
                        mediaState = mediaAlbumsState,
                        profileLinksState = profileLinksState,
                        messagesState = messagesState,
                        viewerUserId = authState.session?.userId,
                        profileImageUrl = profileImageUrl,
                        onBack = { profileSurface = ProfileSurface.None },
                        onOpenAlbums = { profileSurface = ProfileSurface.MediaAlbums },
                        onAcceptProfileLinkInvite = { linkId ->
                            scope.launch {
                                profileLinksStateHolder.acceptInvitation(linkId)
                                profileHubStateHolder.load()
                            }
                        },
                        onDeclineProfileLinkInvite = { linkId ->
                            scope.launch {
                                profileLinksStateHolder.declineInvitation(linkId)
                            }
                        },
                        onAcceptPartnerChatInvite = { conversationId ->
                            scope.launch {
                                messagesStateHolder.acceptPartnerInvite(conversationId)
                            }
                        },
                        onDeclinePartnerChatInvite = { conversationId ->
                            scope.launch {
                                messagesStateHolder.declinePartnerInvite(conversationId, authState.session?.userId)
                            }
                        },
                        onClearProfileLinkMessages = profileLinksStateHolder::clearMessages,
                        onClearMessagesError = messagesStateHolder::clearError,
                        onLogout = {
                            profileSurface = ProfileSurface.None
                            scope.launch { authStateHolder.logout() }
                        },
                        modifier = Modifier.weight(1f)
                    )
                } else if (profileSurface == ProfileSurface.MediaAlbums) {
                    MediaAlbumsScreen(
                        stateHolder = mediaAlbumsStateHolder,
                        profileLinksStateHolder = profileLinksStateHolder,
                        onBack = { profileSurface = ProfileSurface.Hub },
                        onPickMedia = onPickMediaForAlbum,
                        modifier = Modifier.weight(1f)
                    )
                } else when (selectedTab) {
                    PynapliTab.Grid -> GridScreen(
                        modifier = Modifier.weight(1f),
                        stateHolder = gridStateHolder,
                        profileDetailStateHolder = profileDetailStateHolder,
                        conversationStateHolder = conversationStateHolder,
                        viewerUserId = authState.session?.userId,
                        locationState = locationState,
                        profileImageUrl = profileImageUrl,
                        profileLinksStateHolder = profileLinksStateHolder,
                        savedGridFilterStateHolder = savedGridFilterStateHolder,
                        tonightAvailabilityStateHolder = tonightAvailabilityStateHolder,
                        lifestyleFiltersAllowed = profileHubState.me?.profile?.ageVerified == true,
                        distanceUnit = distanceUnit,
                        onUseCurrentLocation = onUseCurrentLocation,
                        onProfileIconClick = { profileSurface = ProfileSurface.Hub },
                        onOpenStore = { selectedTab = PynapliTab.Store }
                    )

                    PynapliTab.Messages -> MessagesScreen(
                        modifier = Modifier.weight(1f),
                        stateHolder = messagesStateHolder,
                        viewerUserId = authState.session?.userId,
                        onThreadSelected = { conversation ->
                            messagesStateHolder.markConversationReadLocal(conversation.conversationId)
                            scope.launch {
                                conversationStateHolder.openConversation(
                                    conversation = conversation,
                                    viewerUserId = authState.session?.userId
                                )
                            }
                        }
                    )

                    PynapliTab.Activity -> ActivityScreen(
                        modifier = Modifier.weight(1f),
                        stateHolder = activityStateHolder,
                        distanceUnit = distanceUnit,
                        onOpenStore = { selectedTab = PynapliTab.Store },
                        onOpenMatch = { event ->
                            scope.launch {
                                conversationStateHolder.openSwipeMatch(
                                    match = MatchDto(
                                        matchId = event.matchId ?: event.eventId,
                                        matchedUserId = event.actorUserId,
                                        matchedDisplayName = event.actorDisplayName,
                                        matchedPhotoUrl = event.actorPhotoUrl,
                                        createdAt = event.occurredAt
                                    ),
                                    viewerUserId = authState.session?.userId
                                )
                                messagesStateHolder.refreshSilently()
                            }
                        },
                        onOpenPartnerChatInvite = { conversationId ->
                            scope.launch {
                                conversationStateHolder.openConversationById(
                                    conversationId = conversationId,
                                    viewerUserId = authState.session?.userId,
                                    title = "Chat invite"
                                )
                                messagesStateHolder.refreshSilently()
                            }
                        }
                    )

                    PynapliTab.Swipe -> SwipeScreen(
                        modifier = Modifier.weight(1f),
                        stateHolder = swipeStateHolder,
                        profileDetailStateHolder = profileDetailStateHolder,
                        conversationStateHolder = conversationStateHolder,
                        viewerUserId = authState.session?.userId,
                        distanceUnit = distanceUnit,
                        matchNotificationsEnabled = appSettingsState.settings.matchNotifications,
                        notificationDetail = appSettingsState.settings.notificationDetail,
                        onOpenStore = { selectedTab = PynapliTab.Store },
                        savedSwipeFilterStateHolder = savedSwipeFilterStateHolder,
                        topPicksStateHolder = topPicksStateHolder,
                        travelLocationStateHolder = travelLocationStateHolder
                    )

                    PynapliTab.Store -> StoreScreen(
                        modifier = Modifier.weight(1f),
                        stateHolder = storeStateHolder
                    )
                }

                PynapliBottomNavigation(
                    selectedTab = selectedTab,
                    unreadMessages = messagesState.unreadCount,
                    activityBadgeCount = activityState.activityBadgeCount,
                    onTabSelected = {
                        profileSurface = ProfileSurface.None
                        selectedTab = it
                    }
                )
            }

            activeNotification?.let { notification ->
                InAppNotificationBanner(
                    notification = notification,
                    onClick = {
                        profileSurface = ProfileSurface.None
                        selectedTab = notification.targetTab
                        notification.activityTab?.let { tab ->
                            scope.launch { activityStateHolder.selectTab(tab) }
                        }
                        activeNotification = null
                    },
                    modifier = Modifier.align(Alignment.TopCenter)
                )
            }
        }

        if (conversationState.visible) {
            ConversationSheet(
                state = conversationState,
                onDismiss = conversationStateHolder::dismiss,
                onDraftChanged = conversationStateHolder::updateDraft,
                onSend = {
                    scope.launch { conversationStateHolder.sendDraft() }
                },
                onLoadOlder = {
                    scope.launch { conversationStateHolder.loadOlderMessages() }
                },
                linkedPartnerUserId = profileLinksState.current?.otherUserId,
                onInvitePartner = {
                    scope.launch {
                        conversationStateHolder.inviteLinkedPartner()
                        messagesStateHolder.refreshSilently()
                    }
                },
                onAcceptInvite = {
                    scope.launch {
                        conversationStateHolder.acceptPartnerInvite()
                        messagesStateHolder.refreshSilently()
                    }
                },
                onDeclineInvite = {
                    scope.launch {
                        conversationStateHolder.declinePartnerInvite()
                        messagesStateHolder.refreshSilently()
                    }
                },
                onLeaveConversation = {
                    scope.launch {
                        conversationStateHolder.leaveConversation()
                        messagesStateHolder.refreshSilently()
                    }
                }
            )
        }
    }
}

private const val INBOX_REFRESH_INTERVAL_MS = 15_000L
private const val OPEN_CONVERSATION_REFRESH_INTERVAL_MS = 7_500L
private const val ACTIVITY_REFRESH_INTERVAL_MS = 45_000L
private const val ACTIVITY_SUMMARY_REFRESH_INTERVAL_MS = 45_000L
private const val IN_APP_NOTIFICATION_DURATION_MS = 4_000L

private data class InAppNotificationUi(
    val id: String,
    val kind: InAppNotificationKind,
    val title: String,
    val body: String,
    val targetTab: PynapliTab,
    val activityTab: ActivityTabUi? = null
)

private enum class InAppNotificationKind(
    val icon: ImageVector
) {
    Message(Icons.Filled.ChatBubble),
    Activity(Icons.Filled.LocalFireDepartment)
}

@Composable
private fun InAppNotificationBanner(
    notification: InAppNotificationUi,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(
            imageVector = notification.kind.icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(22.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = notification.title,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = notification.body,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

private fun ConversationDto.toMessageNotification(
    viewerUserId: String?,
    settings: AppSettings
): InAppNotificationUi {
    val standardDetail = settings.notificationDetail == AppSettings.NOTIFICATION_STANDARD
    return InAppNotificationUi(
        id = "message:$conversationId:${lastMessage?.messageId ?: updatedAt}:$unreadCount",
        kind = InAppNotificationKind.Message,
        title = if (standardDetail) titleFor(viewerUserId) else "New message",
        body = if (standardDetail) {
            lastMessage?.body?.cleanNotificationText() ?: "Open messages"
        } else {
            "Open messages"
        },
        targetTab = PynapliTab.Messages
    )
}

private fun ActivityEventDto.toActivityNotification(settings: AppSettings): InAppNotificationUi {
    val standardDetail = settings.notificationDetail == AppSettings.NOTIFICATION_STANDARD
    return InAppNotificationUi(
        id = "activity:$eventId",
        kind = InAppNotificationKind.Activity,
        title = if (standardDetail) actorDisplayName else "New activity",
        body = if (standardDetail) activityNotificationBody() else "Open Activity",
        targetTab = PynapliTab.Activity,
        activityTab = activityTab()
    )
}

private fun ActivityEventDto.activityNotificationBody(): String =
    when (type) {
        "profile_view", "viewed_profile" -> if (count > 1) "viewed you $count times" else "viewed you"
        "flirt" -> "sent a spark"
        "swipe_like" -> "liked you"
        "pineapple_like" -> "sent a Pineapple"
        "intro_note" -> "sent an Intro Note"
        "match" -> "matched with you"
        "album_request" -> "requested album access"
        "partner_chat_invite" -> "sent a chat invite"
        else -> "new activity"
    }

private fun ActivityEventDto.activityTab(): ActivityTabUi =
    when (type) {
        "profile_view", "viewed_profile" -> ActivityTabUi.Views
        "flirt" -> ActivityTabUi.Flirts
        "swipe_like", "pineapple_like", "intro_note" -> ActivityTabUi.Likes
        "match" -> ActivityTabUi.Matches
        "album_request" -> ActivityTabUi.Albums
        "partner_chat_invite", "psync_invite", "playing_tonight" -> ActivityTabUi.Invites
        else -> ActivityTabUi.All
    }

private fun String.cleanNotificationText(): String {
    val normalized = trim().replace('\n', ' ')
    return if (normalized.length <= NOTIFICATION_BODY_MAX_LENGTH) {
        normalized
    } else {
        normalized.take(NOTIFICATION_BODY_MAX_LENGTH - 3).trimEnd() + "..."
    }
}

private const val NOTIFICATION_BODY_MAX_LENGTH = 96

@Composable
private fun PynapliBottomNavigation(
    selectedTab: PynapliTab,
    unreadMessages: Int,
    activityBadgeCount: Int,
    onTabSelected: (PynapliTab) -> Unit
) {
    NavigationBar(
        modifier = Modifier.navigationBarsPadding(),
        containerColor = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 0.dp
    ) {
        PynapliTab.entries.forEach { tab ->
            val enabled = tab == PynapliTab.Grid ||
                tab == PynapliTab.Swipe ||
                tab == PynapliTab.Activity ||
                tab == PynapliTab.Messages ||
                tab == PynapliTab.Store
            NavigationBarItem(
                enabled = enabled,
                selected = selectedTab == tab,
                onClick = { if (enabled) onTabSelected(tab) },
                icon = {
                    val badgeCount = when (tab) {
                        PynapliTab.Messages -> unreadMessages
                        PynapliTab.Activity -> activityBadgeCount
                        else -> 0
                    }
                    if (badgeCount > 0) {
                        BadgedBox(
                            badge = {
                                Badge {
                                    Text(text = badgeCount.toBadgeText())
                                }
                            }
                        ) {
                            Icon(
                                imageVector = tab.icon,
                                contentDescription = tab.label
                            )
                        }
                    } else {
                        Icon(
                            imageVector = tab.icon,
                            contentDescription = tab.label
                        )
                    }
                },
                label = { Text(text = tab.label) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.primary,
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                    indicatorColor = MaterialTheme.colorScheme.surfaceVariant,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            )
        }
    }
}

private fun Int.toBadgeText(): String = if (this > 9) "9+" else toString()

private enum class PynapliTab(
    val label: String,
    val icon: ImageVector
) {
    Grid("Grid", Icons.Filled.GridView),
    Swipe("Swipe", Icons.Filled.Style),
    Activity("Activity", Icons.Filled.LocalFireDepartment),
    Messages("Messages", Icons.Filled.ChatBubble),
    Store("Store", Icons.Filled.ShoppingBag)
}

private enum class ProfileSurface {
    None,
    Hub,
    MediaAlbums
}
