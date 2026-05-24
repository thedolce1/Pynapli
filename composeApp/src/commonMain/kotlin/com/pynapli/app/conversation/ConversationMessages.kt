package com.pynapli.app.conversation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pynapli.shared.network.dto.MessageDto

@Composable
internal fun MessageList(
    messages: List<MessageDto>,
    viewerUserId: String?,
    senderDisplayNames: Map<String, String>,
    hasOlderMessages: Boolean,
    isLoadingOlder: Boolean,
    isDeletingMessageIds: Set<String>,
    onLoadOlder: () -> Unit,
    onDeleteMessage: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()
    val lastMessageId = messages.lastOrNull()?.messageId

    LaunchedEffect(lastMessageId) {
        if (lastMessageId != null) {
            scrollState.scrollTo(scrollState.maxValue)
        }
    }

    Column(
        modifier = modifier.verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (hasOlderMessages) {
            TextButton(
                onClick = onLoadOlder,
                enabled = !isLoadingOlder,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            ) {
                if (isLoadingOlder) {
                    CircularProgressIndicator(
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(18.dp)
                    )
                } else {
                    Text(text = "Older messages")
                }
            }
        }

        if (messages.isEmpty()) {
            Text(
                text = "No messages yet.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(vertical = 18.dp)
            )
        } else {
            val lastOwnMessageId = messages.lastOrNull { message -> message.senderUserId == viewerUserId }?.messageId
            var lastDateKey: String? = null
            messages.forEach { message ->
                val dateKey = message.createdAt.messageDateKey()
                if (dateKey != null && dateKey != lastDateKey) {
                    DayDivider(label = dateKey.messageDateLabel())
                    lastDateKey = dateKey
                }
                val mine = message.senderUserId == viewerUserId
                MessageBubble(
                    message = message,
                    mine = mine,
                    senderDisplayName = senderDisplayNames[message.senderUserId],
                    showDeliveryStatus = mine && message.messageId == lastOwnMessageId,
                    isDeleting = message.messageId in isDeletingMessageIds,
                    onDeleteMessage = onDeleteMessage
                )
            }
        }
    }
}

@Composable
private fun DayDivider(label: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun MessageBubble(
    message: MessageDto,
    mine: Boolean,
    senderDisplayName: String?,
    showDeliveryStatus: Boolean,
    isDeleting: Boolean,
    onDeleteMessage: (String) -> Unit
) {
    val deleted = message.deletedAt != null
    val timeLabel = message.createdAt.messageTimeLabel()
    val bubbleText = if (deleted) {
        "${if (mine) "You" else senderDisplayName ?: "Someone"} deleted a message"
    } else {
        message.body
    }
    val bubbleColor = when {
        deleted -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.62f)
        mine -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val textColor = when {
        deleted -> MaterialTheme.colorScheme.onSurfaceVariant
        mine -> MaterialTheme.colorScheme.onPrimary
        else -> MaterialTheme.colorScheme.onSurface
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.86f),
            horizontalAlignment = if (mine) Alignment.End else Alignment.Start
        ) {
            Text(
                text = bubbleText,
                color = textColor,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .widthIn(max = 312.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(bubbleColor)
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            )
            Row(
                modifier = Modifier.padding(top = 2.dp, end = if (mine) 2.dp else 0.dp, start = if (mine) 0.dp else 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (timeLabel.isNotEmpty()) {
                    Text(
                        text = timeLabel,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
                if (mine) {
                    if (showDeliveryStatus) {
                        message.deliveryStatus.deliveryLabel()?.let { label ->
                            Text(
                                text = label,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                }
                if (mine && !deleted) {
                    IconButton(
                        onClick = { onDeleteMessage(message.messageId) },
                        enabled = !isDeleting,
                        modifier = Modifier
                            .size(32.dp)
                            .testTag(ConversationTestTags.deleteMessage(message.messageId))
                    ) {
                        if (isDeleting) {
                            CircularProgressIndicator(
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(14.dp)
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Filled.Delete,
                                contentDescription = "Delete message",
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}
