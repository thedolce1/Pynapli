package com.pynapli.app.conversation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pynapli.shared.network.dto.ConversationParticipantStatus
import com.pynapli.shared.network.dto.PartnerChatInviteStatus

@Composable
internal fun ConversationPartnerActions(
    state: ConversationUiState,
    linkedPartnerUserId: String?,
    onInvitePartner: () -> Unit,
    onAcceptInvite: () -> Unit,
    onDeclineInvite: () -> Unit,
    onLeaveConversation: () -> Unit,
    showLeaveAction: Boolean,
    showPartnerStatusFeedback: Boolean
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
    val showLeave = showLeaveAction &&
        conversation.kind == "group" &&
        !state.isViewerInvited &&
        !state.isInvitingPartner
    val partnerFeedback = state.partnerInviteFeedback.takeIf { showPartnerStatusFeedback }

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

        showInvitePartner || showLeave || partnerFeedback != null || state.isInvitingPartner || state.isLeaving -> {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    partnerFeedback?.let { message ->
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
