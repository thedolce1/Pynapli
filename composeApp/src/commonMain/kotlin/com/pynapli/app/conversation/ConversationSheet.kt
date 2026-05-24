package com.pynapli.app.conversation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pynapli.shared.network.dto.MESSAGE_BODY_MAX_LENGTH
import com.pynapli.shared.network.dto.MessageDto
import com.pynapli.shared.network.dto.ConversationParticipantStatus
import com.pynapli.shared.network.dto.PartnerChatInviteStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationSheet(
    state: ConversationUiState,
    onDismiss: () -> Unit,
    onDraftChanged: (String) -> Unit,
    onSend: () -> Unit,
    onLoadOlder: () -> Unit,
    linkedPartnerUserId: String? = null,
    onInvitePartner: () -> Unit = {},
    onAcceptInvite: () -> Unit = {},
    onDeclineInvite: () -> Unit = {},
    onLeaveConversation: () -> Unit = {},
    onDeleteMessage: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    if (!state.visible) return

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val title = state.conversationTitle()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            ConversationHeader(
                title = title,
                onDismiss = onDismiss
            )

            when {
                state.isOpening -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 180.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    }
                }

                state.errorMessage != null && state.detail == null -> {
                    Text(
                        text = state.errorMessage,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }

                else -> {
                    ConversationPartnerActions(
                        state = state,
                        linkedPartnerUserId = linkedPartnerUserId,
                        onInvitePartner = onInvitePartner,
                        onAcceptInvite = onAcceptInvite,
                        onDeclineInvite = onDeclineInvite,
                        onLeaveConversation = onLeaveConversation
                    )
                    MessageList(
                        messages = state.detail?.messages.orEmpty(),
                        viewerUserId = state.viewerUserId,
                        senderDisplayNames = state.detail?.conversation?.participants
                            ?.associate { participant -> participant.userId to participant.displayName }
                            .orEmpty(),
                        hasOlderMessages = state.detail?.nextCursor != null,
                        isLoadingOlder = state.isLoadingOlder,
                        isDeletingMessageIds = state.isDeletingMessageIds,
                        onLoadOlder = onLoadOlder,
                        onDeleteMessage = onDeleteMessage,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 220.dp, max = 430.dp)
                    )
                    TypingIndicator(participants = state.typingParticipants)
                    if (!state.isViewerInvited) {
                        Composer(
                            draft = state.draft,
                            canSend = state.canSend,
                            isSending = state.isSending,
                            isOverLimit = state.draftOverLimit,
                            remainingCharacters = state.draftRemaining,
                            errorMessage = state.errorMessage,
                            onDraftChanged = onDraftChanged,
                            onSend = onSend
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TypingIndicator(participants: List<TypingParticipantUi>) {
    if (participants.isEmpty()) return
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(ConversationTestTags.typingIndicator),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.58f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                repeat(3) {
                    Box(
                        modifier = Modifier
                            .size(5.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary)
                    )
                }
            }
            Text(
                text = participants.typingLabel(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun ConversationPartnerActions(
    state: ConversationUiState,
    linkedPartnerUserId: String?,
    onInvitePartner: () -> Unit,
    onAcceptInvite: () -> Unit,
    onDeclineInvite: () -> Unit,
    onLeaveConversation: () -> Unit
) {
    val conversation = state.detail?.conversation ?: return
    val viewerUserId = state.viewerUserId
    val pendingInvite = conversation.partnerInvite
    val viewerParticipant = conversation.participants.firstOrNull { participant -> participant.userId == viewerUserId }
    val viewerIsInvitedPartner = viewerUserId != null &&
        pendingInvite?.invitedPartnerUserId == viewerUserId
    val viewerCanApproveAsThirdParty = viewerUserId != null &&
        pendingInvite != null &&
        viewerParticipant?.status == ConversationParticipantStatus.ACTIVE &&
        viewerUserId != pendingInvite.inviterUserId &&
        viewerUserId != pendingInvite.invitedPartnerUserId
    val partnerAcceptedWaiting = pendingInvite?.let { invite ->
        viewerIsInvitedPartner &&
            invite.partnerStatus == PartnerChatInviteStatus.ACCEPTED &&
            invite.thirdPartyStatus == PartnerChatInviteStatus.PENDING
    } == true
    val thirdPartyApprovalNeeded = pendingInvite?.let { invite ->
        viewerCanApproveAsThirdParty &&
            invite.thirdPartyStatus == PartnerChatInviteStatus.PENDING
    } == true
    val waitingForPartner = pendingInvite?.let { invite ->
        viewerCanApproveAsThirdParty &&
            invite.thirdPartyStatus == PartnerChatInviteStatus.ACCEPTED &&
            invite.partnerStatus == PartnerChatInviteStatus.PENDING
    } == true
    val inviterWaiting = viewerUserId != null &&
        pendingInvite != null &&
        viewerUserId == pendingInvite.inviterUserId
    val partnerAlreadyInThread = linkedPartnerUserId != null &&
        conversation.participants.any { participant ->
            participant.userId == linkedPartnerUserId &&
                participant.status in visibleConversationParticipantStatuses
        }
    val showInvitePartner = linkedPartnerUserId != null &&
        !partnerAlreadyInThread &&
        !state.isViewerInvited &&
        !state.isLeaving
    val showLeave = conversation.kind == "group" &&
        !state.isViewerInvited &&
        !state.isInvitingPartner

    when {
        partnerAcceptedWaiting -> {
            PartnerInviteStatusNotice(
                title = "Invite accepted",
                body = "Waiting for the chat to approve your join."
            )
        }

        state.isViewerInvited -> {
            PartnerInvitePrompt(
                title = "You were invited to join this chat.",
                body = null,
                isResponding = state.isRespondingToInvite,
                errorMessage = state.errorMessage,
                onAcceptInvite = onAcceptInvite,
                onDeclineInvite = onDeclineInvite
            )
        }

        thirdPartyApprovalNeeded -> {
            PartnerInvitePrompt(
                title = "Partner invite request",
                body = "Approve before the partner can join this chat.",
                isResponding = state.isRespondingToInvite,
                errorMessage = state.errorMessage,
                onAcceptInvite = onAcceptInvite,
                onDeclineInvite = onDeclineInvite
            )
        }

        waitingForPartner -> {
            PartnerInviteStatusNotice(
                title = "Invite approved",
                body = "Waiting for the partner to accept."
            )
        }

        inviterWaiting -> {
            PartnerInviteStatusNotice(
                title = "Partner invite pending",
                body = "The partner and chat must both approve before they can join."
            )
        }

        showInvitePartner || showLeave || state.partnerInviteFeedback != null || state.isInvitingPartner || state.isLeaving -> {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    state.partnerInviteFeedback?.let { message ->
                        Text(
                            text = message,
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f)
                        )
                    } ?: Box(modifier = Modifier.weight(1f))

                    if (showInvitePartner) {
                        TextButton(
                            onClick = onInvitePartner,
                            enabled = !state.isInvitingPartner
                        ) {
                            if (state.isInvitingPartner) {
                                CircularProgressIndicator(
                                    strokeWidth = 2.dp,
                                    modifier = Modifier.size(16.dp)
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Filled.PersonAdd,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Text(
                                text = "Invite partner",
                                modifier = Modifier.padding(start = 6.dp)
                            )
                        }
                    }

                    if (showLeave) {
                        TextButton(
                            onClick = onLeaveConversation,
                            enabled = !state.isLeaving
                        ) {
                            if (state.isLeaving) {
                                CircularProgressIndicator(
                                    strokeWidth = 2.dp,
                                    modifier = Modifier.size(16.dp)
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.Logout,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Text(
                                text = "Leave",
                                modifier = Modifier.padding(start = 6.dp)
                            )
                        }
                    }
                }
                if (state.errorMessage != null && state.draft.isBlank()) {
                    Text(
                        text = state.errorMessage,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

@Composable
private fun PartnerInvitePrompt(
    title: String,
    body: String?,
    isResponding: Boolean,
    errorMessage: String?,
    onAcceptInvite: () -> Unit,
    onDeclineInvite: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = title,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
            body?.let {
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = onAcceptInvite,
                    enabled = !isResponding
                ) {
                    if (isResponding) {
                        CircularProgressIndicator(
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(16.dp)
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Filled.Check,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Text(
                        text = "Accept",
                        modifier = Modifier.padding(start = 6.dp)
                    )
                }
                OutlinedButton(
                    onClick = onDeclineInvite,
                    enabled = !isResponding
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = "Decline",
                        modifier = Modifier.padding(start = 6.dp)
                    )
                }
            }
            errorMessage?.let { message ->
                Text(
                    text = message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun PartnerInviteStatusNotice(
    title: String,
    body: String
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = title,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = body,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun ConversationHeader(
    title: String,
    onDismiss: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = title,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        IconButton(onClick = onDismiss) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = "Close conversation"
            )
        }
    }
}

@Composable
private fun MessageList(
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
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()),
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
            messages.forEach { message ->
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
private fun MessageBubble(
    message: MessageDto,
    mine: Boolean,
    senderDisplayName: String?,
    showDeliveryStatus: Boolean,
    isDeleting: Boolean,
    onDeleteMessage: (String) -> Unit
) {
    val deleted = message.deletedAt != null
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
            modifier = Modifier.fillMaxWidth(0.82f),
            horizontalAlignment = if (mine) Alignment.End else Alignment.Start
        ) {
            Text(
                text = bubbleText,
                color = textColor,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(bubbleColor)
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            )
            if (mine) {
                Row(
                    modifier = Modifier.padding(top = 2.dp, end = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (showDeliveryStatus) {
                        message.deliveryStatus.deliveryLabel()?.let { label ->
                            Text(
                                text = label,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                    if (!deleted) {
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
}

@Composable
private fun Composer(
    draft: String,
    canSend: Boolean,
    isSending: Boolean,
    isOverLimit: Boolean,
    remainingCharacters: Int,
    errorMessage: String?,
    onDraftChanged: (String) -> Unit,
    onSend: () -> Unit
) {
    val showCharacterCount = draft.length >= MESSAGE_BODY_MAX_LENGTH - 200 || isOverLimit
    val helperText = when {
        errorMessage != null -> errorMessage
        isOverLimit -> "${-remainingCharacters} characters over limit"
        showCharacterCount -> "$remainingCharacters characters left"
        else -> null
    }
    val helperColor = if (errorMessage != null || isOverLimit) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val sendButtonActive = canSend || isSending
    val sendButtonColor = if (sendButtonActive) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val sendContentColor = if (sendButtonActive) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 18.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = onDraftChanged,
                enabled = !isSending,
                isError = errorMessage != null || isOverLimit,
                placeholder = { Text("Message") },
                modifier = Modifier.weight(1f),
                maxLines = 4
            )
            IconButton(
                onClick = onSend,
                enabled = canSend,
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(sendButtonColor)
            ) {
                if (isSending) {
                    CircularProgressIndicator(
                        color = sendContentColor,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(22.dp)
                    )
                } else {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Send message",
                        tint = sendContentColor
                    )
                }
            }
        }
        helperText?.let { text ->
            Text(
                text = text,
                color = helperColor,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(start = 4.dp)
            )
        }
    }
}

private fun ConversationUiState.conversationTitle(): String {
    val otherParticipant = detail?.conversation?.participants?.firstOrNull {
        viewerUserId == null || it.userId != viewerUserId
    }
    return otherParticipant?.displayName ?: titleOverride ?: selectedCard?.displayName ?: "Conversation"
}

private fun String.deliveryLabel(): String? = when (this) {
    "sent" -> "Sent"
    "read" -> "Read"
    else -> null
}

private fun List<TypingParticipantUi>.typingLabel(): String =
    when (size) {
        1 -> "${first().displayName} is typing"
        2 -> "${this[0].displayName} and ${this[1].displayName} are typing"
        else -> "${first().displayName} and ${size - 1} others are typing"
    }

object ConversationTestTags {
    fun deleteMessage(messageId: String): String = "conversation-delete-message-$messageId"
    const val typingIndicator: String = "conversation-typing-indicator"
}
