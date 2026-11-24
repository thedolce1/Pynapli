package com.pynapli.server.messaging

import java.sql.Connection
import java.util.UUID

internal fun Connection.acceptOpenPartnerInvite(
    conversationId: UUID,
    userId: UUID,
    invite: PartnerChatInviteRecord,
    role: PartnerInviteUserRole,
) {
    val partnerAccepted =
        invite.partnerStatus == PARTNER_INVITE_ACCEPTED ||
            role == PartnerInviteUserRole.InvitedPartner
    val thirdPartyAccepted =
        invite.thirdPartyStatus == PARTNER_INVITE_ACCEPTED ||
            role == PartnerInviteUserRole.ThirdPartyApprover
    val fullyAccepted = partnerAccepted && thirdPartyAccepted
    // Activating the invitee must not put blocked people together: re-check against everyone present
    // (active participants and other open invitees), whoever's accept completes it. The generic
    // refusal; the caller's transaction rolls back.
    if (fullyAccepted && inviteeBlockedInConversation(conversationId, invite.invitedPartnerUserId)) {
        throw MessagingException("MESSAGING_NOT_ALLOWED")
    }

    prepareStatement(
        """
        UPDATE partner_chat_invites
        SET partner_status = CASE WHEN ? THEN ? ELSE partner_status END,
            third_party_status = CASE WHEN ? THEN ? ELSE third_party_status END,
            resolved_at = CASE WHEN ? THEN now() ELSE resolved_at END
        WHERE id = ?
          AND resolved_at IS NULL
        """.trimIndent(),
    ).use { statement ->
        statement.setBoolean(1, role == PartnerInviteUserRole.InvitedPartner)
        statement.setString(2, PARTNER_INVITE_ACCEPTED)
        statement.setBoolean(3, role == PartnerInviteUserRole.ThirdPartyApprover)
        statement.setString(4, PARTNER_INVITE_ACCEPTED)
        statement.setBoolean(5, fullyAccepted)
        statement.setObject(6, invite.inviteId)
        statement.executeUpdate()
    }

    if (fullyAccepted) {
        activateInvitedPartner(conversationId, invite.invitedPartnerUserId)
        // Someone new is in: anyone else who had said yes must say yes again, knowing who is there.
        resetWaitingPartnerAcceptances(conversationId)
    } else if (role == PartnerInviteUserRole.InvitedPartner) {
        recordInvitedPartnerResponse(conversationId, userId)
    }

    markConversationGroup(conversationId, causedByUserId = invite.invitedPartnerUserId)
}

internal fun Connection.declineOpenPartnerInvite(
    conversationId: UUID,
    invite: PartnerChatInviteRecord,
    role: PartnerInviteUserRole,
) {
    prepareStatement(
        """
        UPDATE partner_chat_invites
        SET partner_status = CASE WHEN ? THEN ? ELSE partner_status END,
            third_party_status = CASE WHEN ? THEN ? ELSE third_party_status END,
            resolved_at = now()
        WHERE id = ?
          AND resolved_at IS NULL
        """.trimIndent(),
    ).use { statement ->
        statement.setBoolean(1, role == PartnerInviteUserRole.InvitedPartner)
        statement.setString(2, PARTNER_INVITE_DECLINED)
        statement.setBoolean(3, role == PartnerInviteUserRole.ThirdPartyApprover)
        statement.setString(4, PARTNER_INVITE_DECLINED)
        statement.setObject(5, invite.inviteId)
        statement.executeUpdate()
    }

    prepareStatement(
        """
        UPDATE conversation_participants
        SET invitation_status = 'declined',
            invitation_responded_at = now(),
            left_at = now()
        WHERE conversation_id = ?
          AND user_id = ?
          AND invitation_status = 'invited'
          AND left_at IS NULL
        """.trimIndent(),
    ).use { statement ->
        statement.setObject(1, conversationId)
        statement.setObject(2, invite.invitedPartnerUserId)
        statement.executeUpdate()
    }
    touchConversation(conversationId, causedByUserId = invite.invitedPartnerUserId)
}

private fun Connection.activateInvitedPartner(
    conversationId: UUID,
    partnerUserId: UUID,
) {
    prepareStatement(
        """
        UPDATE conversation_participants
        SET invitation_status = 'active',
            joined_at = now(),
            last_read_at = now(),
            invitation_responded_at = now(),
            left_at = NULL
        WHERE conversation_id = ?
          AND user_id = ?
          AND invitation_status = 'invited'
          AND left_at IS NULL
        """.trimIndent(),
    ).use { statement ->
        statement.setObject(1, conversationId)
        statement.setObject(2, partnerUserId)
        statement.executeUpdate()
    }
}

private fun Connection.recordInvitedPartnerResponse(
    conversationId: UUID,
    userId: UUID,
) {
    prepareStatement(
        """
        UPDATE conversation_participants
        SET invitation_responded_at = now()
        WHERE conversation_id = ?
          AND user_id = ?
          AND invitation_status = 'invited'
          AND left_at IS NULL
        """.trimIndent(),
    ).use { statement ->
        statement.setObject(1, conversationId)
        statement.setObject(2, userId)
        statement.executeUpdate()
    }
}
