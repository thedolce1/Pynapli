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
import com.pynapli.app.design.PynapliSocialTopRail
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
                LazyColumn(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .testTag(MessagesTestTags.LIST),
                ) {
                    items(
                        items = state.conversations,
                        key = { conversation -> conversation.conversationId },
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
    Column(
        modifier = Modifier.fillMaxWidth(),
    ) {
        PynapliSocialTopRail(
            onProfileClick = onProfileIconClick,
            onActionClick = onRefresh,
            actionContentDescription = "Refresh messages",
        )
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = "Messages",
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "$conversationCount conversations - $unreadCount unread",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

internal object MessagesTestTags {
    const val LIST = "messages-list"

    fun row(conversationId: String): String = "messages-row-$conversationId"

    fun acceptInvite(conversationId: String): String = "messages-invite-accept-$conversationId"

    fun declineInvite(conversationId: String): String = "messages-invite-decline-$conversationId"
}
