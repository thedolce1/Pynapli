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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.pynapli.app.design.PynapliColors
import com.pynapli.app.design.PynapliShape
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
    // Lazy so a long thread composes only the visible bubbles. Keyed rows also keep the reading
    // position when "Older messages" prepends a page.
    val listState = rememberLazyListState()
    val rows = remember(messages) { messages.toThreadRows() }
    val lastOwnMessageId =
        remember(messages, viewerUserId) {
            messages.lastOrNull { message -> message.senderUserId == viewerUserId }?.messageId
        }
    val lastMessageId = messages.lastOrNull()?.messageId

    LaunchedEffect(lastMessageId) {
        if (lastMessageId != null) {
            // rows is current here; layoutInfo can still describe the previous frame.
            listState.scrollToItem(rows.lastIndex + if (hasOlderMessages) 1 else 0)
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (hasOlderMessages) {
            item(key = OLDER_MESSAGES_KEY, contentType = OLDER_MESSAGES_KEY) {
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    TextButton(
                        onClick = onLoadOlder,
                        enabled = !isLoadingOlder,
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
            }
        }

        if (messages.isEmpty()) {
            item(key = EMPTY_THREAD_KEY, contentType = EMPTY_THREAD_KEY) {
                Text(
                    text = "No messages yet.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 18.dp),
                )
            }
        } else {
            items(
                items = rows,
                key = { row -> row.key },
                contentType = { row -> row.contentType },
            ) { row ->
                when (row) {
                    is ThreadRow.Day -> DayDivider(label = row.label)
                    is ThreadRow.Message -> {
                        val message = row.message
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
    }
}

private const val OLDER_MESSAGES_KEY = "older-messages"
private const val EMPTY_THREAD_KEY = "empty-thread"

/** One row of the thread: a day divider, or a message (bubble or system notice). */
internal sealed interface ThreadRow {
    val key: String
    val contentType: String

    /** Keyed by the first message of the day, so the key stays unique even for out-of-order input. */
    data class Day(
        val firstMessageId: String,
        val label: String,
    ) : ThreadRow {
        override val key: String get() = "day-$firstMessageId"
        override val contentType: String get() = "day"
    }

    data class Message(
        val message: MessageDto,
    ) : ThreadRow {
        override val key: String get() = "message-${message.messageId}"
        override val contentType: String get() = if (message.messageType == MessageType.SYSTEM) "system" else "bubble"
    }
}

/** Interleaves a day divider before the first message of each calendar day. */
internal fun List<MessageDto>.toThreadRows(): List<ThreadRow> {
    val rows = ArrayList<ThreadRow>(size + 4)
    var lastDateKey: String? = null
    forEach { message ->
        val dateKey = message.createdAt.messageDateKey()
        if (dateKey != null && dateKey != lastDateKey) {
            rows += ThreadRow.Day(firstMessageId = message.messageId, label = dateKey.messageDateLabel())
            lastDateKey = dateKey
        }
        rows += ThreadRow.Message(message)
    }
    return rows
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
                .clip(PynapliShape.CardShape)
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
                    .clip(PynapliShape.PillShape)
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
    // Sheet-radius bubble with a small "tail" corner on the sender's side (handoff chat style).
    // Both radii come from the shape scale so bubbles track it if the scale ever moves.
    val bubbleShape =
        if (mine) {
            RoundedCornerShape(
                topStart = PynapliShape.Sheet,
                topEnd = PynapliShape.Sheet,
                bottomEnd = PynapliShape.Small,
                bottomStart = PynapliShape.Sheet,
            )
        } else {
            RoundedCornerShape(
                topStart = PynapliShape.Sheet,
                topEnd = PynapliShape.Sheet,
                bottomEnd = PynapliShape.Sheet,
                bottomStart = PynapliShape.Small,
            )
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
