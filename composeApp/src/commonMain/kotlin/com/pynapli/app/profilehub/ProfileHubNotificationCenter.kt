package com.pynapli.app.profilehub

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.People
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pynapli.app.conversation.titleFor
import com.pynapli.app.design.PynapliShape
import com.pynapli.app.messages.MessagesUiState
import com.pynapli.app.profilelink.ProfileLinkUi
import com.pynapli.app.profilelink.ProfileLinksUiState
import com.pynapli.shared.network.dto.ConversationDto

@Composable
internal fun NotificationCenterContent(
    profileLinksState: ProfileLinksUiState,
    messagesState: MessagesUiState,
    partnerChatInvites: List<ConversationDto>,
    viewerUserId: String?,
    onBack: () -> Unit,
    onAcceptProfileLinkInvite: (String) -> Unit,
    onDeclineProfileLinkInvite: (String) -> Unit,
    onAcceptPartnerChatInvite: (String) -> Unit,
    onDeclinePartnerChatInvite: (String) -> Unit,
    onClearProfileLinkMessages: () -> Unit,
    onClearMessagesError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isEmpty = profileLinksState.incomingInvites.isEmpty() && partnerChatInvites.isEmpty()

    LazyColumn(
        modifier =
            modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = ProfileSurfaceBottomPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            ProfileHubTopBar(
                title = "Notifications",
                onBack = onBack,
            )
        }

        profileLinksState.errorMessage?.let { message ->
            item {
                ProfileHubMessageBand(
                    message = message,
                    isError = true,
                    onDismiss = onClearProfileLinkMessages,
                )
            }
        }

        messagesState.errorMessage?.let { message ->
            item {
                ProfileHubMessageBand(
                    message = message,
                    isError = true,
                    onDismiss = onClearMessagesError,
                )
            }
        }

        if (isEmpty) {
            item {
                EmptyActionCenter()
            }
        }

        if (profileLinksState.incomingInvites.isNotEmpty()) {
            item {
                ProfileHubSection("Profile link invites") {
                    profileLinksState.incomingInvites.forEach { invite ->
                        ProfileLinkInviteRow(
                            invite = invite,
                            isMutating = invite.linkId in profileLinksState.mutatingLinkIds,
                            onAccept = { onAcceptProfileLinkInvite(invite.linkId) },
                            onDecline = { onDeclineProfileLinkInvite(invite.linkId) },
                        )
                    }
                }
            }
        }

        if (partnerChatInvites.isNotEmpty()) {
            item {
                ProfileHubSection("Partner chat invites") {
                    partnerChatInvites.forEach { conversation ->
                        PartnerChatInviteRow(
                            conversation = conversation,
                            viewerUserId = viewerUserId,
                            isMutating = conversation.conversationId in messagesState.mutatingPartnerInviteConversationIds,
                            onAccept = { onAcceptPartnerChatInvite(conversation.conversationId) },
                            onDecline = { onDeclinePartnerChatInvite(conversation.conversationId) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyActionCenter() {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(PynapliShape.CardShape)
                .background(MaterialTheme.colorScheme.surface)
                .border(1.dp, MaterialTheme.colorScheme.outline, PynapliShape.CardShape)
                .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = "No action items",
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = "Profile link and partner chat invites will appear here.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun ProfileLinkInviteRow(
    invite: ProfileLinkUi,
    isMutating: Boolean,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
) {
    ActionCenterRow(
        icon = Icons.Filled.People,
        title = "Profile link invite",
        subtitle = "${invite.displayLabel} wants to link profiles.",
        isMutating = isMutating,
        acceptLabel = "Accept",
        declineLabel = "Decline",
        onAccept = onAccept,
        onDecline = onDecline,
    )
}

@Composable
private fun PartnerChatInviteRow(
    conversation: ConversationDto,
    viewerUserId: String?,
    isMutating: Boolean,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
) {
    ActionCenterRow(
        icon = Icons.Filled.ChatBubble,
        title = "Partner chat invite",
        subtitle = "Join ${conversation.titleFor(viewerUserId)} when you are ready.",
        isMutating = isMutating,
        acceptLabel = "Join",
        declineLabel = "Pass",
        onAccept = onAccept,
        onDecline = onDecline,
    )
}

@Composable
private fun ActionCenterRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    isMutating: Boolean,
    acceptLabel: String,
    declineLabel: String,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier =
                    Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    text = title,
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (isMutating) {
                CircularProgressIndicator(
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(18.dp),
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                onClick = onDecline,
                enabled = !isMutating,
            ) {
                Text(declineLabel)
            }
            TextButton(
                onClick = onAccept,
                enabled = !isMutating,
            ) {
                Text(acceptLabel)
            }
        }
    }
}
