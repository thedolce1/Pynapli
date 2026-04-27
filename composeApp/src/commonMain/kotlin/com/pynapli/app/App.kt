package com.pynapli.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.pynapli.app.activity.ActivityScreen
import com.pynapli.app.activity.ActivityStateHolder
import com.pynapli.app.auth.AuthScreen
import com.pynapli.app.auth.AuthStateHolder
import com.pynapli.app.auth.DevLoginCredentials
import com.pynapli.app.conversation.ConversationSheet
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun PynapliApp(
    authStateHolder: AuthStateHolder,
    gridStateHolder: GridStateHolder,
    profileDetailStateHolder: ProfileDetailStateHolder,
    conversationStateHolder: ConversationStateHolder,
    messagesStateHolder: MessagesStateHolder,
    activityStateHolder: ActivityStateHolder,
    locationStateHolder: LocationStateHolder,
    mediaAlbumsStateHolder: MediaAlbumsStateHolder,
    onUseCurrentLocation: () -> Unit,
    onPickMediaForAlbum: (MediaAlbumKindUi) -> Unit = {},
    devLoginCredentials: DevLoginCredentials? = null
) {
    PynapliTheme {
        val authState by authStateHolder.state.collectAsState()
        val locationState by locationStateHolder.state.collectAsState()
        val conversationState by conversationStateHolder.state.collectAsState()
        val messagesState by messagesStateHolder.state.collectAsState()
        val mediaAlbumsState by mediaAlbumsStateHolder.state.collectAsState()
        val scope = rememberCoroutineScope()
        var selectedTab by remember { mutableStateOf(PynapliTab.Grid) }
        var showMediaAlbums by remember { mutableStateOf(false) }

        LaunchedEffect(authStateHolder) {
            authStateHolder.restoreSession()
        }

        LaunchedEffect(authState.authenticated) {
            if (!authState.authenticated) {
                showMediaAlbums = false
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

        LaunchedEffect(authState.authenticated, messagesStateHolder) {
            if (authState.authenticated) {
                while (true) {
                    delay(INBOX_REFRESH_INTERVAL_MS)
                    messagesStateHolder.refreshSilently()
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

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            val profileImageUrl = mediaAlbumsState.primaryOwnerProfilePhotoUrl()

            if (showMediaAlbums) {
                MediaAlbumsScreen(
                    stateHolder = mediaAlbumsStateHolder,
                    onBack = { showMediaAlbums = false },
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
                    onUseCurrentLocation = onUseCurrentLocation,
                    onProfileIconClick = { showMediaAlbums = true }
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
                    stateHolder = activityStateHolder
                )

                PynapliTab.Swipe,
                PynapliTab.Store -> GridScreen(
                    modifier = Modifier.weight(1f),
                    stateHolder = gridStateHolder,
                    profileDetailStateHolder = profileDetailStateHolder,
                    conversationStateHolder = conversationStateHolder,
                    viewerUserId = authState.session?.userId,
                    locationState = locationState,
                    profileImageUrl = profileImageUrl,
                    onUseCurrentLocation = onUseCurrentLocation,
                    onProfileIconClick = { showMediaAlbums = true }
                )
            }

            PynapliBottomNavigation(
                selectedTab = selectedTab,
                unreadMessages = messagesState.unreadCount,
                onTabSelected = {
                    showMediaAlbums = false
                    selectedTab = it
                }
            )
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
                }
            )
        }
    }
}

private const val INBOX_REFRESH_INTERVAL_MS = 15_000L
private const val OPEN_CONVERSATION_REFRESH_INTERVAL_MS = 7_500L

@Composable
private fun PynapliBottomNavigation(
    selectedTab: PynapliTab,
    unreadMessages: Int,
    onTabSelected: (PynapliTab) -> Unit
) {
    NavigationBar(
        modifier = Modifier.navigationBarsPadding(),
        containerColor = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 0.dp
    ) {
        PynapliTab.entries.forEach { tab ->
            val enabled = tab == PynapliTab.Grid || tab == PynapliTab.Activity || tab == PynapliTab.Messages
            NavigationBarItem(
                enabled = enabled,
                selected = selectedTab == tab,
                onClick = { if (enabled) onTabSelected(tab) },
                icon = {
                    if (tab == PynapliTab.Messages && unreadMessages > 0) {
                        BadgedBox(
                            badge = {
                                Badge {
                                    Text(text = if (unreadMessages > 9) "9+" else unreadMessages.toString())
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
