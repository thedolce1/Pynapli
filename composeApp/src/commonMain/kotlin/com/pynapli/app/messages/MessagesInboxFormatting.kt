package com.pynapli.app.messages

import com.pynapli.shared.network.dto.ConversationDto
import com.pynapli.shared.network.dto.ConversationParticipantStatus
import com.pynapli.shared.network.dto.MessageDto
import com.pynapli.shared.network.dto.PartnerChatInviteStatus

internal fun String.compactTimeLabel(): String =
    if (length >= 16 && this[10] == 'T') substring(11, 16) else ""

internal data class PartnerInviteUiState(
    val label: String,
    val preview: String,
    val showActions: Boolean
)

internal fun ConversationDto.partnerInviteUiState(viewerUserId: String?): PartnerInviteUiState? {
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

internal fun String.originLabel(): String = when (this) {
    "swipe" -> "Match"
    "intro_note" -> "Intro"
    else -> "Grid"
}

internal fun MessageDto.previewText(
    conversation: ConversationDto,
    viewerUserId: String?
): String =
    if (deletedAt == null) {
        body
    } else {
        val senderName = conversation.participants.firstOrNull { participant ->
            participant.userId == senderUserId
        }?.displayName
        "${if (senderUserId == viewerUserId) "You" else senderName ?: "Someone"} deleted a message"
    }
