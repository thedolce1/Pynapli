package com.pynapli.app.conversation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pynapli.app.design.handCursor
import com.pynapli.app.design.icons.Icons
import com.pynapli.app.design.icons.automirrored.filled.Logout
import com.pynapli.app.design.icons.filled.PersonAdd
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
    showPartnerStatusFeedback: Boolean,
) {
    val conversation = state.detail?.conversation ?: return
    val viewerUserId = state.viewerUserId
    // Several invites can be open at once (each side bringing their own partner): each is shown and
    // answered on its own.
    val invites = conversation.openPartnerInvites()
    val viewerIsActive =
        viewerUserId != null &&
            conversation.participants.any { it.userId == viewerUserId && it.status == ConversationParticipantStatus.ACTIVE }
    val ownInvite = invites.firstOrNull { invite -> viewerUserId != null && invite.invitedPartnerUserId == viewerUserId }
    val partnerAcceptedWaiting =
        ownInvite != null &&
            ownInvite.partnerStatus == PartnerChatInviteStatus.ACCEPTED &&
            ownInvite.thirdPartyStatus == PartnerChatInviteStatus.PENDING
    // An invite by someone else that waits for the viewer's approval (never offered to an invited person).
    val approvalInvite = state.actionablePartnerInvite?.takeIf { it.invitedPartnerUserId != viewerUserId }
    val thirdPartyApprovalNeeded = approvalInvite != null
    val waitingForPartner =
        viewerIsActive &&
            invites.any { invite ->
                invite.inviterUserId != viewerUserId &&
                    invite.thirdPartyStatus == PartnerChatInviteStatus.ACCEPTED &&
                    invite.partnerStatus == PartnerChatInviteStatus.PENDING
            }
    val inviterWaiting = invites.any { invite -> viewerUserId != null && invite.inviterUserId == viewerUserId }
    val partnerAlreadyInThread =
        linkedPartnerUserId != null &&
            conversation.participants.any { participant ->
                participant.userId == linkedPartnerUserId &&
                    participant.status in visibleConversationParticipantStatuses
            }
    // The server's "Bring your partner" offer replaces the small Invite partner button when present;
    // without it (no offer, or an older server) the chrome is exactly as before.
    val offer = state.partnerOfferUi()
    val showInvitePartner =
        linkedPartnerUserId != null &&
            !partnerAlreadyInThread &&
            !state.isViewerInvited &&
            !state.isLeaving &&
            offer?.bringPartnerName == null
    val showLeave =
        showLeaveAction &&
            conversation.kind == "group" &&
            !state.isViewerInvited &&
            !state.isInvitingPartner
    val partnerFeedback = state.partnerInviteFeedback.takeIf { showPartnerStatusFeedback }

    when {
        partnerAcceptedWaiting -> {
            PartnerInviteStatusNotice(
                title = "Invite accepted",
                body = "Waiting for the chat to approve your join.",
            )
        }

        state.isViewerInvited -> {
            PartnerInvitePrompt(
                title = "You were invited to join this chat.",
                body = null,
                isResponding = state.isRespondingToInvite,
                errorMessage = state.errorMessage,
                onAcceptInvite = onAcceptInvite,
                onDeclineInvite = onDeclineInvite,
            )
        }

        thirdPartyApprovalNeeded -> {
            PartnerInvitePrompt(
                title = "Partner invite request",
                body =
                    "Approve before ${approvalInvite?.let { conversation.invitedPartnerName(it) } ?: "the partner"} " +
                        "can join this chat.",
                isResponding = state.isRespondingToInvite,
                errorMessage = state.errorMessage,
                onAcceptInvite = onAcceptInvite,
                onDeclineInvite = onDeclineInvite,
            )
        }

        waitingForPartner -> {
            PartnerInviteStatusNotice(
                title = "Invite approved",
                body = "Waiting for the partner to accept.",
            )
        }

        inviterWaiting -> {
            PartnerInviteStatusNotice(
                title = "Partner invite pending",
                body = "The partner and chat must both approve before they can join.",
            )
        }

        offer != null || showInvitePartner || showLeave || partnerFeedback != null ||
            state.isInvitingPartner || state.isLeaving -> {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                offer?.participantPartnerNotes?.forEach { note ->
                    Text(
                        text = note,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                offer?.bringPartnerName?.let { partnerName ->
                    PartnerInviteStatusNotice(
                        title = "Bring $partnerName into this chat?",
                        body = "$partnerName and everyone here each need to accept. Nobody joins without saying yes.",
                        action = {
                            Button(
                                onClick = onInvitePartner,
                                enabled = !state.isInvitingPartner,
                                modifier = Modifier.handCursor(enabled = !state.isInvitingPartner),
                            ) {
                                if (state.isInvitingPartner) {
                                    CircularProgressIndicator(
                                        strokeWidth = 2.dp,
                                        modifier = Modifier.size(16.dp),
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.Filled.PersonAdd,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                                Text(
                                    text = "Invite $partnerName",
                                    modifier = Modifier.padding(start = 6.dp),
                                )
                            }
                        },
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    partnerFeedback?.let { message ->
                        Text(
                            text = message,
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                    } ?: Box(modifier = Modifier.weight(1f))

                    if (showInvitePartner) {
                        TextButton(
                            onClick = onInvitePartner,
                            enabled = !state.isInvitingPartner,
                            modifier = Modifier.handCursor(enabled = !state.isInvitingPartner),
                        ) {
                            if (state.isInvitingPartner) {
                                CircularProgressIndicator(
                                    strokeWidth = 2.dp,
                                    modifier = Modifier.size(16.dp),
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Filled.PersonAdd,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                            Text(
                                text = "Invite partner",
                                modifier = Modifier.padding(start = 6.dp),
                            )
                        }
                    }

                    if (showLeave) {
                        TextButton(
                            onClick = onLeaveConversation,
                            enabled = !state.isLeaving,
                            modifier = Modifier.handCursor(enabled = !state.isLeaving),
                        ) {
                            if (state.isLeaving) {
                                CircularProgressIndicator(
                                    strokeWidth = 2.dp,
                                    modifier = Modifier.size(16.dp),
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.Logout,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                            Text(
                                text = "Leave",
                                modifier = Modifier.padding(start = 6.dp),
                            )
                        }
                    }
                }
                if (state.errorMessage != null && state.draft.isBlank()) {
                    Text(
                        text = state.errorMessage,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}
