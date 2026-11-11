package com.pynapli.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.pynapli.app.activity.ActivityStateHolder
import com.pynapli.app.appsettings.AppSettingsStateHolder
import com.pynapli.app.auth.AuthStateHolder
import com.pynapli.app.conversation.ConversationStateHolder
import com.pynapli.app.media.MediaAlbumsStateHolder
import com.pynapli.app.messages.MessagesStateHolder
import com.pynapli.app.profilehub.ProfileHubStateHolder
import com.pynapli.app.profilelink.ProfileLinksStateHolder
import kotlinx.coroutines.delay

/**
 * All of [PynapliApp]'s background side-effects in one place (extracted from the App.kt shell):
 * session restore, per-surface loads on auth, deep-link routing, the inbox/activity/open-conversation
 * refresh loops, and the in-app notification derivation + auto-dismiss. Renders no UI, so it is a
 * pure behavior-preserving move — the [LaunchedEffect] keys are unchanged. The shell keeps ownership
 * of navigation/notification state and passes setters; the two "snapshot" vars used only to diff
 * unread/activity deltas live here.
 */
@Composable
internal fun PynapliAppEffects(
    authStateHolder: AuthStateHolder,
    appSettingsStateHolder: AppSettingsStateHolder,
    messagesStateHolder: MessagesStateHolder,
    activityStateHolder: ActivityStateHolder,
    conversationStateHolder: ConversationStateHolder,
    mediaAlbumsStateHolder: MediaAlbumsStateHolder,
    profileHubStateHolder: ProfileHubStateHolder,
    profileLinksStateHolder: ProfileLinksStateHolder,
    deepLinkStateHolder: AppDeepLinkStateHolder?,
    activeNotification: InAppNotificationUi?,
    onNotification: (InAppNotificationUi?) -> Unit,
    onSelectTab: (PynapliTab) -> Unit,
    onClearOverlays: () -> Unit,
    onOpenProfileHub: () -> Unit,
    onOpenPartnerPanel: () -> Unit = onOpenProfileHub,
    onOpenTonight: () -> Unit,
    onOpenPsync: () -> Unit,
) {
    val authState by authStateHolder.state.collectAsState()
    val appSettingsState by appSettingsStateHolder.state.collectAsState()
    val conversationState by conversationStateHolder.state.collectAsState()
    val messagesState by messagesStateHolder.state.collectAsState()
    val activityState by activityStateHolder.state.collectAsState()

    var messageUnreadSnapshot by remember { mutableStateOf<Map<String, Int>?>(null) }
    var activityEventSnapshot by remember { mutableStateOf<Set<String>?>(null) }

    LaunchedEffect(authStateHolder) {
        authStateHolder.restoreSession()
    }

    // Returns to sign-in the moment the session dies mid-use (revoked / account no longer active),
    // instead of stranding the user in an authenticated shell where every request 401s.
    LaunchedEffect(authStateHolder) {
        authStateHolder.observeSessionInvalidations()
    }

    LaunchedEffect(authState.authenticated, appSettingsStateHolder) {
        if (authState.authenticated) {
            appSettingsStateHolder.load()
        }
    }

    LaunchedEffect(authState.authenticated) {
        if (!authState.authenticated) {
            onClearOverlays()
            onNotification(null)
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
            // Every target is a fresh destination, so close whatever overlay was showing. This is
            // also what makes browser back from /tonight or /psync close the overlay.
            onClearOverlays()
            when (command.target) {
                AppDeepLinkStateHolder.TARGET_ACTIVITY -> {
                    onSelectTab(PynapliTab.Activity)
                    val activityTab = command.activityTab
                    if (activityTab != null) {
                        activityStateHolder.selectTab(activityTab)
                    } else {
                        activityStateHolder.load()
                    }
                    activityStateHolder.focusEvent(command.activityEventId)
                }

                AppDeepLinkStateHolder.TARGET_MESSAGES -> {
                    onSelectTab(PynapliTab.Messages)
                    messagesStateHolder.refreshSilently()
                    command.conversationId?.let { conversationId ->
                        conversationStateHolder.openConversationById(
                            conversationId = conversationId,
                            viewerUserId = authState.session?.userId,
                            title = "Conversation",
                        )
                    }
                }

                AppDeepLinkStateHolder.TARGET_GRID -> onSelectTab(PynapliTab.Grid)

                AppDeepLinkStateHolder.TARGET_SWIPE -> onSelectTab(PynapliTab.Swipe)

                AppDeepLinkStateHolder.TARGET_STORE -> onSelectTab(PynapliTab.Store)

                AppDeepLinkStateHolder.TARGET_PROFILE -> onOpenProfileHub()

                AppDeepLinkStateHolder.TARGET_PARTNER -> onOpenPartnerPanel()

                // Each overlay opens over the tab that owns its entry point, so closing it lands
                // where the in-app button would have.
                AppDeepLinkStateHolder.TARGET_TONIGHT -> {
                    onSelectTab(PynapliTab.Grid)
                    onOpenTonight()
                }

                AppDeepLinkStateHolder.TARGET_PSYNC -> {
                    onSelectTab(PynapliTab.Swipe)
                    onOpenPsync()
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
        activityStateHolder,
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
        conversationStateHolder,
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
        authState.session?.userId,
    ) {
        if (!authState.authenticated) return@LaunchedEffect
        val currentUnread =
            messagesState.conversations.associate { conversation ->
                conversation.conversationId to conversation.unreadCount
            }
        val previousUnread = messageUnreadSnapshot
        if (previousUnread == null) {
            messageUnreadSnapshot = currentUnread
            return@LaunchedEffect
        }

        if (appSettingsState.settings.messageNotifications) {
            val updatedConversation =
                messagesState.conversations.firstOrNull { conversation ->
                    conversation.unreadCount > (previousUnread[conversation.conversationId] ?: 0)
                }
            if (updatedConversation != null) {
                onNotification(
                    updatedConversation.toMessageNotification(
                        viewerUserId = authState.session?.userId,
                        settings = appSettingsState.settings,
                    ),
                )
            }
        }
        messageUnreadSnapshot = currentUnread
    }

    LaunchedEffect(
        activityState.items,
        authState.authenticated,
        appSettingsState.settings.activityNotifications,
        appSettingsState.settings.notificationDetail,
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
                onNotification(newEvent.toActivityNotification(appSettingsState.settings))
            }
        }
        activityEventSnapshot = currentIds
    }

    if (activeNotification != null) {
        LaunchedEffect(activeNotification.id) {
            delay(IN_APP_NOTIFICATION_DURATION_MS)
            onNotification(null)
        }
    }
}

private const val INBOX_REFRESH_INTERVAL_MS = 15_000L
private const val OPEN_CONVERSATION_REFRESH_INTERVAL_MS = 7_500L
private const val ACTIVITY_REFRESH_INTERVAL_MS = 45_000L
private const val ACTIVITY_SUMMARY_REFRESH_INTERVAL_MS = 45_000L
private const val IN_APP_NOTIFICATION_DURATION_MS = 4_000L
