package com.pynapli.app.messages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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

@Composable
internal fun ConversationRow(
    conversation: ConversationDto,
    viewerUserId: String?,
    isMutatingInvite: Boolean,
    onAcceptPartnerInvite: () -> Unit,
    onDeclinePartnerInvite: () -> Unit,
    onClick: () -> Unit,
) {
    val title = conversation.titleFor(viewerUserId)
    val otherParticipant =
        conversation.participants.firstOrNull {
            viewerUserId == null || it.userId != viewerUserId
        }
    val viewerParticipant =
        conversation.participants.firstOrNull { participant ->
            viewerUserId != null && participant.userId == viewerUserId
        }
    val isInvite = viewerParticipant?.status == ConversationParticipantStatus.INVITED
    val inviteUi = conversation.partnerInviteUiState(viewerUserId)
    val lastMessage = conversation.lastMessage
    val preview =
        when {
            inviteUi != null -> inviteUi.preview
            isInvite -> "Chat invite"
            lastMessage != null -> lastMessage.previewText(conversation, viewerUserId)
            else -> "No messages yet"
        }
    val unread = conversation.unreadCount > 0

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .testTag(MessagesTestTags.row(conversation.conversationId))
                .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ThreadAvatar(online = otherParticipant?.online == true)
        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = title,
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (unread) FontWeight.Bold else FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (unread) {
                    UnreadBadge(count = conversation.unreadCount)
                }
                Text(
                    text = conversation.updatedAt.compactTimeLabel(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                )
            }
            Row(
                modifier = Modifier.padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
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
                modifier = Modifier.padding(top = 4.dp),
            )
            if (inviteUi?.showActions == true) {
                InviteActionRow(
                    isMutating = isMutatingInvite,
                    onAcceptPartnerInvite = onAcceptPartnerInvite,
                    onDeclinePartnerInvite = onDeclinePartnerInvite,
                    conversationId = conversation.conversationId,
                )
            }
        }
    }
}

@Composable
private fun ConversationOriginPill(
    label: String,
    muted: Boolean = false,
) {
    Surface(
        shape = CircleShape,
        color =
            if (muted) {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.62f)
            } else {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
            },
    ) {
        Text(
            text = label,
            color = if (muted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

@Composable
private fun InviteActionRow(
    isMutating: Boolean,
    onAcceptPartnerInvite: () -> Unit,
    onDeclinePartnerInvite: () -> Unit,
    conversationId: String,
) {
    Row(
        modifier = Modifier.padding(top = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedButton(
            onClick = onDeclinePartnerInvite,
            enabled = !isMutating,
            modifier = Modifier.testTag(MessagesTestTags.declineInvite(conversationId)),
        ) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
            Text(text = "Decline", modifier = Modifier.padding(start = 6.dp))
        }
        Button(
            onClick = onAcceptPartnerInvite,
            enabled = !isMutating,
            modifier = Modifier.testTag(MessagesTestTags.acceptInvite(conversationId)),
        ) {
            if (isMutating) {
                CircularProgressIndicator(
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(16.dp),
                )
            } else {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
            }
            Text(text = "Accept", modifier = Modifier.padding(start = 6.dp))
        }
    }
}

@Composable
private fun UnreadBadge(count: Int) {
    Box(
        modifier =
            Modifier
                .size(22.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (count > 9) "9+" else count.toString(),
            color = MaterialTheme.colorScheme.onPrimary,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}

@Composable
private fun ThreadAvatar(online: Boolean) {
    Box(
        modifier = Modifier.size(46.dp),
    ) {
        Box(
            modifier =
                Modifier
                    .size(46.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.linearGradient(
                            listOf(Color(0xFF202031), Color(0xFF193831)),
                        ),
                    ).border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Person,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(21.dp),
            )
        }
        if (online) {
            Box(
                modifier =
                    Modifier
                        .align(Alignment.BottomEnd)
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF13E58A))
                        .border(1.dp, Color.Black, CircleShape),
            )
        }
    }
}

@Composable
internal fun EmptyMessagesState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(horizontal = 28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier =
                Modifier
                    .size(58.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.ChatBubble,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp),
            )
        }
        Text(
            text = "No messages yet.",
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 14.dp),
        )
        Text(
            text = "Start from the grid.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}
