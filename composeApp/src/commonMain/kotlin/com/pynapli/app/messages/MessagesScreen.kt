package com.pynapli.app.messages

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.pynapli.app.design.PynapliErrorState
import com.pynapli.app.design.PynapliHeaderActionButton
import com.pynapli.app.design.PynapliHeaderAvatar
import com.pynapli.app.design.PynapliListSkeleton
import com.pynapli.app.design.PynapliMotion
import com.pynapli.app.design.PynapliScreenHeader
import com.pynapli.app.design.icons.Icons
import com.pynapli.app.design.icons.filled.Refresh
import com.pynapli.app.design.pynapliEntrance
import com.pynapli.shared.network.dto.ConversationDto
import kotlinx.coroutines.launch

@Composable
fun MessagesScreen(
    viewerUserId: String?,
    onThreadSelected: (ConversationDto) -> Unit,
    onProfileIconClick: () -> Unit = {},
    profileImageUrl: String? = null,
    modifier: Modifier = Modifier,
    stateHolder: MessagesStateHolder,
) {
    val state by stateHolder.state.collectAsState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(stateHolder) {
        stateHolder.load()
    }

    MessagesScreenContent(
        state = state,
        viewerUserId = viewerUserId,
        onThreadSelected = onThreadSelected,
        onProfileIconClick = onProfileIconClick,
        profileImageUrl = profileImageUrl,
        onRefresh = { scope.launch { stateHolder.load() } },
        onLoadMore = { scope.launch { stateHolder.loadMore() } },
        onAcceptPartnerInvite = { conversationId ->
            scope.launch { stateHolder.acceptPartnerInvite(conversationId) }
        },
        onDeclinePartnerInvite = { conversationId ->
            scope.launch { stateHolder.declinePartnerInvite(conversationId, viewerUserId) }
        },
        modifier = modifier,
    )
}

@Composable
private fun MessagesScreenContent(
    state: MessagesUiState,
    viewerUserId: String?,
    onThreadSelected: (ConversationDto) -> Unit,
    onProfileIconClick: () -> Unit,
    profileImageUrl: String?,
    onRefresh: () -> Unit,
    onAcceptPartnerInvite: (String) -> Unit,
    onDeclinePartnerInvite: (String) -> Unit,
    modifier: Modifier = Modifier,
    onLoadMore: () -> Unit = {},
) {
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
    ) {
        MessagesTopArea(
            conversationCount = state.conversations.size,
            unreadCount = state.unreadCount,
            onProfileIconClick = onProfileIconClick,
            profileImageUrl = profileImageUrl,
            onRefresh = onRefresh,
        )

        val phase =
            when {
                state.isLoading && state.conversations.isEmpty() -> MessagesPhase.Loading
                state.errorMessage != null && state.conversations.isEmpty() -> MessagesPhase.Error
                state.conversations.isEmpty() -> MessagesPhase.Empty
                else -> MessagesPhase.Content
            }
        Crossfade(
            targetState = phase,
            animationSpec = PynapliMotion.standardTween(),
            label = "messagesPhase",
        ) { current ->
            when (current) {
                MessagesPhase.Loading -> PynapliListSkeleton(modifier = Modifier.fillMaxSize())

                MessagesPhase.Error ->
                    PynapliErrorState(
                        title = "Couldn't load messages",
                        message = state.errorMessage,
                        onRetry = onRefresh,
                        modifier = Modifier.fillMaxSize(),
                    )

                MessagesPhase.Empty -> EmptyMessagesState(modifier = Modifier.fillMaxSize())

                MessagesPhase.Content -> {
                    // New matches (swipe conversations with no messages yet) ride a Tinder-style rail
                    // at the top; they are kept out of the conversation list until someone says hi.
                    val conversationGroups =
                        remember(state.conversations) {
                            state.conversations.partition { conversation -> conversation.isNewMatch() }
                        }
                    val newMatches = conversationGroups.first
                    val listConversations = conversationGroups.second
                    var revealed by remember { mutableStateOf(false) }
                    LaunchedEffect(Unit) { revealed = true }
                    LazyColumn(
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .testTag(MessagesTestTags.LIST),
                    ) {
                        if (newMatches.isNotEmpty()) {
                            item(
                                key = "new-matches-rail",
                                contentType = "new-matches-rail",
                            ) {
                                MessagesNewMatchesRail(
                                    matches = newMatches,
                                    viewerUserId = viewerUserId,
                                    onMatchSelected = onThreadSelected,
                                    modifier = Modifier.padding(bottom = 6.dp),
                                )
                                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f))
                            }
                        }
                        itemsIndexed(
                            items = listConversations,
                            key = { _, conversation -> conversation.conversationId },
                            contentType = { _, _ -> "conversation-row" },
                        ) { index, conversation ->
                            ConversationRow(
                                conversation = conversation,
                                viewerUserId = viewerUserId,
                                isMutatingInvite = conversation.conversationId in state.mutatingPartnerInviteConversationIds,
                                onClick = { onThreadSelected(conversation) },
                                onAcceptPartnerInvite = { onAcceptPartnerInvite(conversation.conversationId) },
                                onDeclinePartnerInvite = { onDeclinePartnerInvite(conversation.conversationId) },
                                modifier = Modifier.pynapliEntrance(visible = revealed, index = index),
                            )
                            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f))
                        }
                        // Older threads: this footer loads the next page as soon as it scrolls into view.
                        if (state.nextCursor != null) {
                            item(key = "load-more", contentType = "load-more") {
                                LaunchedEffect(state.nextCursor) { onLoadMore() }
                                Box(
                                    modifier =
                                        Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 20.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    CircularProgressIndicator(
                                        color = MaterialTheme.colorScheme.primary,
                                        strokeWidth = 2.dp,
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                            }
                        }
                        if (listConversations.isEmpty() && newMatches.isNotEmpty()) {
                            item(
                                key = "new-matches-only-hint",
                                contentType = "new-matches-only-hint",
                            ) {
                                Text(
                                    text = "Say hi to your new matches to start a conversation.",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier =
                                        Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 28.dp, vertical = 24.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MessagesTopArea(
    conversationCount: Int,
    unreadCount: Int,
    onProfileIconClick: () -> Unit,
    profileImageUrl: String?,
    onRefresh: () -> Unit,
) {
    // Pluralized, and the unread clause is dropped at zero so the subtitle stays clean ("inbox zero").
    val conversationsPart = if (conversationCount == 1) "1 conversation" else "$conversationCount conversations"
    val subtitleText =
        if (unreadCount > 0) "$conversationsPart · $unreadCount unread" else conversationsPart
    PynapliScreenHeader(
        leading = {
            PynapliHeaderAvatar(
                onClick = onProfileIconClick,
                imageUrl = profileImageUrl,
                online = true,
            )
        },
        title = "Messages",
        subtitle = subtitleText,
        actions = {
            PynapliHeaderActionButton(
                icon = Icons.Filled.Refresh,
                contentDescription = "Refresh messages",
                onClick = onRefresh,
            )
        },
    )
}

private enum class MessagesPhase { Loading, Error, Empty, Content }

internal object MessagesTestTags {
    const val LIST = "messages-list"

    fun row(conversationId: String): String = "messages-row-$conversationId"

    fun acceptInvite(conversationId: String): String = "messages-invite-accept-$conversationId"

    fun declineInvite(conversationId: String): String = "messages-invite-decline-$conversationId"
}
