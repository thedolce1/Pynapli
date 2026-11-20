package com.pynapli.app.conversation

import com.pynapli.shared.network.dto.ConversationDto
import com.pynapli.shared.network.dto.ConversationParticipantStatus
import com.pynapli.shared.network.dto.PartnerChatInviteDto
import com.pynapli.shared.network.dto.PartnerChatInviteStatus

/**
 * Every open partner invite the viewer is party to. Several can be open at once (each side inviting
 * their own partner); a server that predates the list sends only the single `partnerInvite`.
 */
internal fun ConversationDto.openPartnerInvites(): List<PartnerChatInviteDto> = partnerInvites.ifEmpty { listOfNotNull(partnerInvite) }

/**
 * The invite the viewer can answer now, mirroring the server's rule: their own as the invited
 * partner while it waits for them, otherwise (active participants only, never an invited person) the
 * first invite by someone else still waiting for a third-party approval.
 */
internal fun ConversationDto.actionablePartnerInvite(viewerUserId: String?): PartnerChatInviteDto? {
    viewerUserId ?: return null
    val invites = openPartnerInvites()
    val own = invites.firstOrNull { it.invitedPartnerUserId == viewerUserId }
    if (own != null) return own.takeIf { it.partnerStatus == PartnerChatInviteStatus.PENDING }
    val viewerActive =
        participants.any { it.userId == viewerUserId && it.status == ConversationParticipantStatus.ACTIVE }
    if (!viewerActive) return null
    return invites.firstOrNull { invite ->
        invite.inviterUserId != viewerUserId && invite.thirdPartyStatus == PartnerChatInviteStatus.PENDING
    }
}

/** The invited partner's name as the conversation shows it (they are an invited participant). */
internal fun ConversationDto.invitedPartnerName(invite: PartnerChatInviteDto): String? = participants.firstOrNull { it.userId == invite.invitedPartnerUserId }?.displayName
