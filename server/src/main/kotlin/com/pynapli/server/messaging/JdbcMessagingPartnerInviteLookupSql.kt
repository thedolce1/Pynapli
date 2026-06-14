package com.pynapli.server.messaging

import java.sql.Connection
import java.util.UUID

internal fun Connection.participantStatus(
    conversationId: UUID,
    userId: UUID,
): ParticipantStatus =
    prepareStatement(
        """
        SELECT invitation_status, left_at
        FROM conversation_participants
        WHERE conversation_id = ?
          AND user_id = ?
        """.trimIndent(),
    ).use { statement ->
        statement.setObject(1, conversationId)
        statement.setObject(2, userId)
        statement.executeQuery().use { rs ->
            if (!rs.next()) {
                ParticipantStatus.None
            } else {
                val status = rs.getString("invitation_status")
                val leftAt = rs.getTimestamp("left_at")
                when {
                    leftAt != null -> ParticipantStatus.Inactive
                    status == "active" -> ParticipantStatus.Active
                    status == "invited" -> ParticipantStatus.Invited
                    else -> ParticipantStatus.Inactive
                }
            }
        }
    }

internal fun Connection.upsertInvitedParticipant(
    conversationId: UUID,
    partnerUserId: UUID,
    inviterUserId: UUID,
) {
    prepareStatement(
        """
        INSERT INTO conversation_participants (
            conversation_id,
            user_id,
            role,
            joined_at,
            left_at,
            last_read_at,
            invitation_status,
            invited_by_user_id,
            invited_at,
            invitation_responded_at
        )
        VALUES (?, ?, 'member', now(), NULL, now(), 'invited', ?, now(), NULL)
        ON CONFLICT (conversation_id, user_id) DO UPDATE
        SET invitation_status = 'invited',
            invited_by_user_id = EXCLUDED.invited_by_user_id,
            invited_at = now(),
            invitation_responded_at = NULL,
            left_at = NULL,
            last_read_at = now()
        WHERE conversation_participants.invitation_status IN ('declined', 'left')
           OR conversation_participants.left_at IS NOT NULL
        """.trimIndent(),
    ).use { statement ->
        statement.setObject(1, conversationId)
        statement.setObject(2, partnerUserId)
        statement.setObject(3, inviterUserId)
        statement.executeUpdate()
    }
}

internal fun Connection.partnerInviteCooldownActive(
    conversationId: UUID,
    partnerUserId: UUID,
): Boolean =
    prepareStatement(
        """
        SELECT EXISTS (
            SELECT 1
            FROM partner_chat_invites
            WHERE thread_id = ?
              AND invited_partner_user_id = ?
              AND third_party_status = ?
              AND resolved_at > now() - interval '24 hours'
        )
        """.trimIndent(),
    ).use { statement ->
        statement.setObject(1, conversationId)
        statement.setObject(2, partnerUserId)
        statement.setString(3, PARTNER_INVITE_DECLINED)
        statement.executeQuery().use { rs ->
            rs.next()
            rs.getBoolean(1)
        }
    }

internal fun Connection.insertPartnerChatInvite(
    conversationId: UUID,
    inviterUserId: UUID,
    partnerUserId: UUID,
): Boolean =
    prepareStatement(
        """
        INSERT INTO partner_chat_invites (
            id,
            thread_id,
            inviter_user_id,
            invited_partner_user_id,
            third_party_status,
            partner_status
        )
        VALUES (?, ?, ?, ?, ?, ?)
        ON CONFLICT DO NOTHING
        """.trimIndent(),
    ).use { statement ->
        statement.setObject(1, UUID.randomUUID())
        statement.setObject(2, conversationId)
        statement.setObject(3, inviterUserId)
        statement.setObject(4, partnerUserId)
        statement.setString(5, PARTNER_INVITE_PENDING)
        statement.setString(6, PARTNER_INVITE_PENDING)
        statement.executeUpdate() > 0
    }

internal fun Connection.loadOpenPartnerInvite(
    conversationId: UUID,
    forUpdate: Boolean,
): PartnerChatInviteRecord? {
    val query =
        buildString {
            append(
                """
                SELECT id,
                       thread_id,
                       inviter_user_id,
                       invited_partner_user_id,
                       third_party_status,
                       partner_status,
                       created_at
                FROM partner_chat_invites
                WHERE thread_id = ?
                  AND resolved_at IS NULL
                ORDER BY created_at DESC, id DESC
                LIMIT 1
                """.trimIndent(),
            )
            if (forUpdate) append("\nFOR UPDATE")
        }
    return prepareStatement(query).use { statement ->
        statement.setObject(1, conversationId)
        statement.executeQuery().use { rs ->
            if (rs.next()) rs.toPartnerChatInviteRecord() else null
        }
    }
}

internal fun Connection.partnerInviteUserRole(
    userId: UUID,
    invite: PartnerChatInviteRecord,
): PartnerInviteUserRole =
    when {
        userId == invite.invitedPartnerUserId -> PartnerInviteUserRole.InvitedPartner
        userId != invite.inviterUserId && isActiveParticipant(userId, invite.threadId) ->
            PartnerInviteUserRole.ThirdPartyApprover
        else -> PartnerInviteUserRole.None
    }
