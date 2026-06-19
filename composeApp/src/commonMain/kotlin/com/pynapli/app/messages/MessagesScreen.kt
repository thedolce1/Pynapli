package com.pynapli.app.messages

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pynapli.app.design.PynapliHeaderActionButton
import com.pynapli.app.design.PynapliHeaderAvatar
import com.pynapli.app.design.PynapliScreenHeader
import com.pynapli.shared.network.dto.ConversationDto
import kotlinx.coroutines.launch

@Composable
fun MessagesScreen(
    viewerUserId: String?,
    onThreadSelected: (ConversationDto) -> Unit,
    onProfileIconClick: () -> Unit = {},
    modifier: Modifier = Modifier,
    stateHolder: MessagesStateHolder = rememberMessagesStateHolder(),
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
        onRefresh = { scope.launch { stateHolder.load() } },
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
private fun rememberMessagesStateHolder(): MessagesStateHolder =
    remember {
        MessagesStateHolder(
            repository = SampleMessagesRepository(),
        )
    }

@Composable
private fun MessagesScreenContent(
    state: MessagesUiState,
    viewerUserId: String?,
    onThreadSelected: (ConversationDto) -> Unit,
    onProfileIconClick: () -> Unit,
    onRefresh: () -> Unit,
    onAcceptPartnerInvite: (String) -> Unit,
    onDeclinePartnerInvite: (String) -> Unit,
    modifier: Modifier = Modifier,
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
            onRefresh = onRefresh,
        )

        when {
            state.isLoading && state.conversations.isEmpty() -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
            }

            state.errorMessage != null && state.conversations.isEmpty() -> {
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .padding(horizontal = 28.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = state.errorMessage,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            state.conversations.isEmpty() -> {
                EmptyMessagesState(modifier = Modifier.fillMaxSize())
            }

            else -> {
                // New matches (swipe conversations with no messages yet) ride a Tinder-style rail
                // at the top; they are kept out of the conversation list until someone says hi.
                val conversationGroups =
                    remember(state.conversations) {
                        state.conversations.partition { conversation -> conversation.isNewMatch() }
                    }
                val newMatches = conversationGroups.first
                val listConversations = conversationGroups.second
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
                    items(
                        items = listConversations,
                        key = { conversation -> conversation.conversationId },
                        contentType = { "conversation-row" },
                    ) { conversation ->
                        ConversationRow(
                            conversation = conversation,
                            viewerUserId = viewerUserId,
                            isMutatingInvite = conversation.conversationId in state.mutatingPartnerInviteConversationIds,
                            onClick = { onThreadSelected(conversation) },
                            onAcceptPartnerInvite = { onAcceptPartnerInvite(conversation.conversationId) },
                            onDeclinePartnerInvite = { onDeclinePartnerInvite(conversation.conversationId) },
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f))
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

@Composable
private fun MessagesTopArea(
    conversationCount: Int,
    unreadCount: Int,
    onProfileIconClick: () -> Unit,
    onRefresh: () -> Unit,
) {
    PynapliScreenHeader(
        leading = {
            PynapliHeaderAvatar(
                onClick = onProfileIconClick,
                online = true,
            )
        },
        title = "Messages",
        subtitle = "$conversationCount conversations · $unreadCount unread",
        actions = {
            PynapliHeaderActionButton(
                icon = Icons.Filled.Refresh,
                contentDescription = "Refresh messages",
                onClick = onRefresh,
            )
        },
    )
}

internal object MessagesTestTags {
    const val LIST = "messages-list"

    fun row(conversationId: String): String = "messages-row-$conversationId"

    fun acceptInvite(conversationId: String): String = "messages-invite-accept-$conversationId"

    fun declineInvite(conversationId: String): String = "messages-invite-decline-$conversationId"
}
