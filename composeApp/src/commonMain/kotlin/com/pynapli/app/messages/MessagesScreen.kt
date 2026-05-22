package com.pynapli.app.messages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pynapli.app.conversation.titleFor
import com.pynapli.shared.network.dto.ConversationDto
import com.pynapli.shared.network.dto.ConversationParticipantStatus
import com.pynapli.shared.network.dto.PartnerChatInviteStatus
import kotlinx.coroutines.launch

@Composable
fun MessagesScreen(
    viewerUserId: String?,
    onThreadSelected: (ConversationDto) -> Unit,
    modifier: Modifier = Modifier,
    stateHolder: MessagesStateHolder = rememberMessagesStateHolder()
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
        onRefresh = { scope.launch { stateHolder.load() } },
        onAcceptPartnerInvite = { conversationId ->
            scope.launch { stateHolder.acceptPartnerInvite(conversationId) }
        },
        onDeclinePartnerInvite = { conversationId ->
            scope.launch { stateHolder.declinePartnerInvite(conversationId, viewerUserId) }
        },
        modifier = modifier
    )
}

@Composable
private fun rememberMessagesStateHolder(): MessagesStateHolder {
    return remember {
        MessagesStateHolder(
            repository = SampleMessagesRepository()
        )
    }
}

@Composable
private fun MessagesScreenContent(
    state: MessagesUiState,
    viewerUserId: String?,
    onThreadSelected: (ConversationDto) -> Unit,
    onRefresh: () -> Unit,
    onAcceptPartnerInvite: (String) -> Unit,
    onDeclinePartnerInvite: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        MessagesHeader(
            loading = state.isLoading,
            onRefresh = onRefresh
        )

        when {
            state.isLoading && state.conversations.isEmpty() -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
            }

            state.errorMessage != null && state.conversations.isEmpty() -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 28.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = state.errorMessage,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            state.conversations.isEmpty() -> {
                EmptyMessagesState(modifier = Modifier.fillMaxSize())
            }

            else -> {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag(MessagesTestTags.LIST)
                ) {
                    items(
                        items = state.conversations,
                        key = { conversation -> conversation.conversationId }
                    ) { conversation ->
                        ConversationRow(
                            conversation = conversation,
                            viewerUserId = viewerUserId,
                            isMutatingInvite = conversation.conversationId in state.mutatingPartnerInviteConversationIds,
                            onClick = { onThreadSelected(conversation) },
                            onAcceptPartnerInvite = { onAcceptPartnerInvite(conversation.conversationId) },
                            onDeclinePartnerInvite = { onDeclinePartnerInvite(conversation.conversationId) }
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f))
                    }
                }
            }
        }
    }
}

@Composable
private fun MessagesHeader(
    loading: Boolean,
    onRefresh: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "Messages",
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f)
        )
        IconButton(
            onClick = onRefresh,
            enabled = !loading,
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Icon(
                imageVector = Icons.Filled.Refresh,
                contentDescription = "Refresh messages",
                tint = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun ConversationRow(
    conversation: ConversationDto,
    viewerUserId: String?,
    isMutatingInvite: Boolean,
    onAcceptPartnerInvite: () -> Unit,
    onDeclinePartnerInvite: () -> Unit,
    onClick: () -> Unit
) {
    val title = conversation.titleFor(viewerUserId)
    val otherParticipant = conversation.participants.firstOrNull {
        viewerUserId == null || it.userId != viewerUserId
    }
    val viewerParticipant = conversation.participants.firstOrNull { participant ->
        viewerUserId != null && participant.userId == viewerUserId
    }
    val isInvite = viewerParticipant?.status == ConversationParticipantStatus.INVITED
    val inviteUi = conversation.partnerInviteUiState(viewerUserId)
    val lastMessage = conversation.lastMessage
    val preview = when {
        inviteUi != null -> inviteUi.preview
        isInvite -> "Chat invite"
        lastMessage != null -> lastMessage.body
        else -> "No messages yet"
    }
    val unread = conversation.unreadCount > 0

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .testTag(MessagesTestTags.row(conversation.conversationId))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        ThreadAvatar(online = otherParticipant?.online == true)
        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = title,
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (unread) FontWeight.Bold else FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (unread) {
                    UnreadBadge(count = conversation.unreadCount)
                }
                Text(
                    text = conversation.updatedAt.compactTimeLabel(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1
                )
            }
            Row(
                modifier = Modifier.padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                ConversationOriginPill(label = inviteUi?.label ?: conversation.origin.originLabel())
                if (conversation.kind == "group") {
                    ConversationOriginPill(label = "Group", muted = true)
                }
            }
            Text(
                text = preview,
                color = if (unread) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (unread) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp)
            )
            if (inviteUi?.showActions == true) {
                InviteActionRow(
                    isMutating = isMutatingInvite,
                    onAcceptPartnerInvite = onAcceptPartnerInvite,
                    onDeclinePartnerInvite = onDeclinePartnerInvite,
                    conversationId = conversation.conversationId
                )
            }
        }
    }
}

@Composable
private fun ConversationOriginPill(
    label: String,
    muted: Boolean = false
) {
    Surface(
        shape = CircleShape,
        color = if (muted) {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.62f)
        } else {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
        }
    ) {
        Text(
            text = label,
            color = if (muted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}

@Composable
private fun InviteActionRow(
    isMutating: Boolean,
    onAcceptPartnerInvite: () -> Unit,
    onDeclinePartnerInvite: () -> Unit,
    conversationId: String
) {
    Row(
        modifier = Modifier.padding(top = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedButton(
            onClick = onDeclinePartnerInvite,
            enabled = !isMutating,
            modifier = Modifier.testTag(MessagesTestTags.declineInvite(conversationId))
        ) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = null,
                modifier = Modifier.size(16.dp)
            )
            Text(text = "Decline", modifier = Modifier.padding(start = 6.dp))
        }
        Button(
            onClick = onAcceptPartnerInvite,
            enabled = !isMutating,
            modifier = Modifier.testTag(MessagesTestTags.acceptInvite(conversationId))
        ) {
            if (isMutating) {
                CircularProgressIndicator(
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(16.dp)
                )
            } else {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
            }
            Text(text = "Accept", modifier = Modifier.padding(start = 6.dp))
        }
    }
}

@Composable
private fun UnreadBadge(count: Int) {
    Box(
        modifier = Modifier
            .size(22.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = if (count > 9) "9+" else count.toString(),
            color = MaterialTheme.colorScheme.onPrimary,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
    }
}

@Composable
private fun ThreadAvatar(online: Boolean) {
    Box(
        modifier = Modifier.size(52.dp)
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(CircleShape)
                .background(
                    Brush.linearGradient(
                        listOf(Color(0xFF2B2622), Color(0xFF6D5D4F))
                    )
                )
                .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.Person,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(24.dp)
            )
        }
        if (online) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF13E58A))
                    .border(1.dp, Color.Black, CircleShape)
            )
        }
    }
}

@Composable
private fun EmptyMessagesState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(horizontal = 28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(58.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.ChatBubble,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp)
            )
        }
        Text(
            text = "No messages yet.",
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 14.dp)
        )
        Text(
            text = "Start from the grid.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

private fun String.compactTimeLabel(): String =
    if (length >= 16 && this[10] == 'T') substring(11, 16) else ""

private data class PartnerInviteUiState(
    val label: String,
    val preview: String,
    val showActions: Boolean
)

private fun ConversationDto.partnerInviteUiState(viewerUserId: String?): PartnerInviteUiState? {
    val invite = partnerInvite ?: return null
    if (viewerUserId == null) return null
    val viewerParticipant = participants.firstOrNull { participant -> participant.userId == viewerUserId }
    val viewerIsInvitedPartner = viewerUserId == invite.invitedPartnerUserId
    val viewerCanApproveAsThirdParty = viewerParticipant?.status == ConversationParticipantStatus.ACTIVE &&
        viewerUserId != invite.inviterUserId &&
        viewerUserId != invite.invitedPartnerUserId

    return when {
        viewerIsInvitedPartner && invite.partnerStatus == PartnerChatInviteStatus.PENDING -> PartnerInviteUiState(
            label = "Invite",
            preview = "You were invited to join this chat",
            showActions = true
        )

        viewerIsInvitedPartner && invite.partnerStatus == PartnerChatInviteStatus.ACCEPTED -> PartnerInviteUiState(
            label = "Waiting",
            preview = "Waiting for chat approval",
            showActions = false
        )

        viewerCanApproveAsThirdParty && invite.thirdPartyStatus == PartnerChatInviteStatus.PENDING -> PartnerInviteUiState(
            label = "Approval",
            preview = "Partner invite needs your approval",
            showActions = true
        )

        viewerCanApproveAsThirdParty && invite.thirdPartyStatus == PartnerChatInviteStatus.ACCEPTED -> PartnerInviteUiState(
            label = "Waiting",
            preview = "Waiting for partner response",
            showActions = false
        )

        viewerUserId == invite.inviterUserId -> PartnerInviteUiState(
            label = "Pending",
            preview = "Partner invite pending",
            showActions = false
        )

        else -> PartnerInviteUiState(
            label = "Invite",
            preview = "Partner invite pending",
            showActions = false
        )
    }
}

private fun String.originLabel(): String = when (this) {
    "swipe" -> "Match"
    "intro_note" -> "Intro"
    else -> "Grid"
}

internal object MessagesTestTags {
    const val LIST = "messages-list"
    fun row(conversationId: String): String = "messages-row-$conversationId"
    fun acceptInvite(conversationId: String): String = "messages-invite-accept-$conversationId"
    fun declineInvite(conversationId: String): String = "messages-invite-decline-$conversationId"
}
