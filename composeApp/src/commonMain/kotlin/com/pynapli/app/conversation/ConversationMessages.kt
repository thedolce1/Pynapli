package com.pynapli.app.conversation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Lock
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.pynapli.app.design.PynapliColors
import com.pynapli.shared.network.dto.MessageDto
import com.pynapli.shared.network.dto.MessageType

@Composable
internal fun MessageList(
    messages: List<MessageDto>,
    viewerUserId: String?,
    senderDisplayNames: Map<String, String>,
    hasOlderMessages: Boolean,
    isLoadingOlder: Boolean,
    isDeletingMessageIds: Set<String>,
    revealingMessageIds: Set<String>,
    onLoadOlder: () -> Unit,
    onDeleteMessage: (String) -> Unit,
    onRevealMedia: (String) -> Unit,
    modifier: Modifier = Modifier,
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
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (hasOlderMessages) {
            TextButton(
                onClick = onLoadOlder,
                enabled = !isLoadingOlder,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            ) {
                if (isLoadingOlder) {
                    CircularProgressIndicator(
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(18.dp),
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
                modifier = Modifier.padding(vertical = 18.dp),
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
                if (message.messageType == MessageType.SYSTEM) {
                    SystemNotice(text = message.body)
                } else {
                    val mine = message.senderUserId == viewerUserId
                    MessageBubble(
                        message = message,
                        mine = mine,
                        senderDisplayName = senderDisplayNames[message.senderUserId],
                        showDeliveryStatus = mine && message.messageId == lastOwnMessageId,
                        isDeleting = message.messageId in isDeletingMessageIds,
                        isRevealing = message.messageId in revealingMessageIds,
                        onDeleteMessage = onDeleteMessage,
                        onRevealMedia = onRevealMedia,
                    )
                }
            }
        }
    }
}

@Composable
private fun MessageMedia(
    message: MessageDto,
    isRevealing: Boolean,
    onRevealMedia: (String) -> Unit,
) {
    val url = message.mediaUrl
    Box(
        modifier =
            Modifier
                .widthIn(max = 240.dp)
                .size(width = 240.dp, height = 240.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .then(if (url == null) Modifier.clickable(enabled = !isRevealing) { onRevealMedia(message.messageId) } else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        if (url != null) {
            AsyncImage(
                model = url,
                contentDescription = "Photo",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (isRevealing) {
                    CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
                } else {
                    Icon(
                        imageVector = Icons.Filled.Lock,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(26.dp),
                    )
                }
                Text(
                    text = if (isRevealing) "Loading…" else "Tap to reveal photo",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

@Composable
private fun SystemNotice(text: String) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(
            text = text,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelMedium,
            modifier =
                Modifier
                    .clip(RoundedCornerShape(100.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun DayDivider(label: String) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(
            text = label.uppercase(),
            color = PynapliColors.TextTertiary,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
        )
    }
}

/**
 * Read-receipt glyph on the viewer's own latest message: a single check once delivered,
 * a double check tinted with the info accent once the recipient has read it (handoff style,
 * replacing the previous "Sent" / "Read" text label). Message payloads keep moderation `status`
 * separate from this delivery `deliveryStatus` (sent / received / read).
 */
@Composable
private fun DeliveryReceipt(deliveryStatus: String) {
    val read = deliveryStatus == "read"
    Icon(
        imageVector = if (read) Icons.Filled.DoneAll else Icons.Filled.Check,
        contentDescription = if (read) "Read" else "Sent",
        tint = if (read) PynapliColors.Info else PynapliColors.TextTertiary,
        modifier = Modifier.size(15.dp),
    )
}

@Composable
private fun MessageBubble(
    message: MessageDto,
    mine: Boolean,
    senderDisplayName: String?,
    showDeliveryStatus: Boolean,
    isDeleting: Boolean,
    isRevealing: Boolean,
    onDeleteMessage: (String) -> Unit,
    onRevealMedia: (String) -> Unit,
) {
    val deleted = message.deletedAt != null
    val timeLabel = message.createdAt.messageTimeLabel()
    val hasMedia = message.mediaAssetId != null && !deleted
    val bubbleText =
        if (deleted) {
            "${if (mine) "You" else senderDisplayName ?: "Someone"} deleted a message"
        } else {
            message.body
        }
    val bubbleColor =
        when {
            deleted -> PynapliColors.SurfacePressed
            mine -> PynapliColors.Accent
            else -> PynapliColors.SurfaceRaised
        }
    val textColor =
        when {
            deleted -> PynapliColors.TextTertiary
            mine -> PynapliColors.TextInverse
            else -> PynapliColors.TextPrimary
        }
    // Soft 16dp bubble with a small "tail" corner on the sender's side (handoff chat style).
    val bubbleShape =
        if (mine) {
            RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomEnd = 4.dp, bottomStart = 16.dp)
        } else {
            RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomEnd = 16.dp, bottomStart = 4.dp)
        }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth(0.86f),
            horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (hasMedia) {
                MessageMedia(
                    message = message,
                    isRevealing = isRevealing,
                    onRevealMedia = onRevealMedia,
                )
            }
            // Skip the empty caption bubble for photo-only messages.
            if (!hasMedia || bubbleText.isNotEmpty()) {
                Text(
                    text = bubbleText,
                    color = textColor,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier =
                        Modifier
                            .widthIn(max = 312.dp)
                            .clip(bubbleShape)
                            .background(bubbleColor)
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
            Row(
                modifier = Modifier.padding(top = 2.dp, end = if (mine) 2.dp else 0.dp, start = if (mine) 0.dp else 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (timeLabel.isNotEmpty()) {
                    Text(
                        text = timeLabel,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                if (mine && showDeliveryStatus && !deleted) {
                    DeliveryReceipt(deliveryStatus = message.deliveryStatus)
                }
                if (mine && !deleted) {
                    IconButton(
                        onClick = { onDeleteMessage(message.messageId) },
                        enabled = !isDeleting,
                        modifier =
                            Modifier
                                .size(32.dp)
                                .testTag(ConversationTestTags.deleteMessage(message.messageId)),
                    ) {
                        if (isDeleting) {
                            CircularProgressIndicator(
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(14.dp),
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Filled.Delete,
                                contentDescription = "Delete message",
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}
