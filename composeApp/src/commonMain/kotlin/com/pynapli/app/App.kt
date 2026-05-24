package com.pynapli.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
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
import com.pynapli.app.activity.ActivityScreen
import com.pynapli.app.activity.ActivityStateHolder
import com.pynapli.app.appsettings.AppSettingsStateHolder
import com.pynapli.app.auth.AuthScreen
import com.pynapli.app.auth.AuthStateHolder
import com.pynapli.app.auth.DevLoginCredentials
import com.pynapli.app.conversation.ConversationSheet
import com.pynapli.app.conversation.ConversationScreen
import com.pynapli.app.conversation.ConversationStateHolder
import com.pynapli.app.design.PynapliTheme
import com.pynapli.app.grid.GridScreen
import com.pynapli.app.grid.GridStateHolder
import com.pynapli.app.location.LocationStateHolder
import com.pynapli.app.media.MediaAlbumKindUi
import com.pynapli.app.media.MediaAlbumsScreen
import com.pynapli.app.media.MediaAlbumsStateHolder
import com.pynapli.app.media.primaryOwnerProfilePhotoUrl
import com.pynapli.app.messages.MessageProfilePeekSheet
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
import com.pynapli.shared.network.dto.MatchDto
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
        val profileDetailState by profileDetailStateHolder.state.collectAsState()
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

        val dismissConversation: () -> Unit = {
            val conversationId = conversationState.detail?.conversation?.conversationId
            scope.launch { conversationStateHolder.sendTypingStopped(conversationId) }
            conversationStateHolder.dismiss()
        }
        val onConversationDraftChanged: (String) -> Unit = { value ->
            conversationStateHolder.updateDraft(value)
            scope.launch { conversationStateHolder.sendTypingSignal(value.isNotBlank()) }
        }
        val onConversationSend: () -> Unit = {
            scope.launch { conversationStateHolder.sendDraft() }
        }
        val onConversationLoadOlder: () -> Unit = {
            scope.launch { conversationStateHolder.loadOlderMessages() }
        }
        val onConversationInvitePartner: () -> Unit = {
            scope.launch {
                conversationStateHolder.inviteLinkedPartner()
                messagesStateHolder.refreshSilently()
            }
        }
        val onConversationAcceptInvite: () -> Unit = {
            scope.launch {
                conversationStateHolder.acceptPartnerInvite()
                messagesStateHolder.refreshSilently()
            }
        }
        val onConversationDeclineInvite: () -> Unit = {
            scope.launch {
                conversationStateHolder.declinePartnerInvite()
                messagesStateHolder.refreshSilently()
            }
        }
        val onConversationLeave: () -> Unit = {
            scope.launch {
                conversationStateHolder.leaveConversation()
                messagesStateHolder.refreshSilently()
            }
        }
        val onConversationDeleteMessage: (String) -> Unit = { messageId ->
            scope.launch {
                conversationStateHolder.deleteMessage(messageId)
                messagesStateHolder.refreshSilently()
            }
        }
        val conversationShownInline = selectedTab == PynapliTab.Messages && conversationState.visible

        LaunchedEffect(selectedTab, profileDetailState.blockedTargetUserId) {
            if (selectedTab != PynapliTab.Messages) return@LaunchedEffect
            val blockedId = profileDetailStateHolder.consumeBlockedTargetUserId() ?: return@LaunchedEffect
            profileDetailStateHolder.dismiss()
            if (conversationState.detail?.conversation?.participants?.any { participant -> participant.userId == blockedId } == true) {
                dismissConversation()
            }
            messagesStateHolder.refreshSilently()
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

                    PynapliTab.Messages -> {
                        if (conversationState.visible) {
                            ConversationScreen(
                                modifier = Modifier.weight(1f),
                                state = conversationState,
                                onBack = dismissConversation,
                                onProfileIconClick = { profileSurface = ProfileSurface.Hub },
                                onOpenParticipantProfile = { participant ->
                                    scope.launch {
                                        profileDetailStateHolder.open(participant.toConversationProfileCardUi())
                                    }
                                },
                                onDraftChanged = onConversationDraftChanged,
                                onSend = onConversationSend,
                                onLoadOlder = onConversationLoadOlder,
                                linkedPartnerUserId = profileLinksState.current?.otherUserId,
                                onInvitePartner = onConversationInvitePartner,
                                onAcceptInvite = onConversationAcceptInvite,
                                onDeclineInvite = onConversationDeclineInvite,
                                onLeaveConversation = onConversationLeave,
                                onDeleteMessage = onConversationDeleteMessage
                            )
                        } else {
                            MessagesScreen(
                                modifier = Modifier.weight(1f),
                                stateHolder = messagesStateHolder,
                                viewerUserId = authState.session?.userId,
                                onProfileIconClick = { profileSurface = ProfileSurface.Hub },
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
                        }
                    }

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
                        if (selectedTab == PynapliTab.Messages && it != PynapliTab.Messages && conversationState.visible) {
                            dismissConversation()
                        }
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

        if (selectedTab == PynapliTab.Messages && profileDetailState.visible) {
            MessageProfilePeekSheet(
                state = profileDetailState,
                onDismiss = profileDetailStateHolder::dismiss,
                onMessage = profileDetailStateHolder::dismiss,
                onSpark = {
                    scope.launch { profileDetailStateHolder.sendSpark() }
                },
                onRequestAlbum = {
                    scope.launch { profileDetailStateHolder.requestAlbumAccess() }
                },
                distanceUnit = appSettingsState.settings.distanceUnit
            )
        }

        if (conversationState.visible && !conversationShownInline) {
            ConversationSheet(
                state = conversationState,
                onDismiss = dismissConversation,
                onDraftChanged = onConversationDraftChanged,
                onSend = onConversationSend,
                onLoadOlder = onConversationLoadOlder,
                linkedPartnerUserId = profileLinksState.current?.otherUserId,
                onInvitePartner = onConversationInvitePartner,
                onAcceptInvite = onConversationAcceptInvite,
                onDeclineInvite = onConversationDeclineInvite,
                onLeaveConversation = onConversationLeave,
                onDeleteMessage = onConversationDeleteMessage
            )
        }
    }
}

private const val INBOX_REFRESH_INTERVAL_MS = 15_000L
private const val OPEN_CONVERSATION_REFRESH_INTERVAL_MS = 7_500L
private const val ACTIVITY_REFRESH_INTERVAL_MS = 45_000L
private const val ACTIVITY_SUMMARY_REFRESH_INTERVAL_MS = 45_000L
private const val IN_APP_NOTIFICATION_DURATION_MS = 4_000L

private enum class ProfileSurface {
    None,
    Hub,
    MediaAlbums
}
