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
              AND (third_party_status = ? OR partner_status = ?)
              AND resolved_at > now() - interval '24 hours'
        )
        """.trimIndent(),
    ).use { statement ->
        // A decline by either side (the third party or the invited partner) starts the cooldown.
        statement.setObject(1, conversationId)
        statement.setObject(2, partnerUserId)
        statement.setString(3, PARTNER_INVITE_DECLINED)
        statement.setString(4, PARTNER_INVITE_DECLINED)
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

/** Every open (unresolved) invite in the conversation, newest first. Several may be open at once. */
internal fun Connection.loadOpenPartnerInvites(
    conversationId: UUID,
    forUpdate: Boolean,
): List<PartnerChatInviteRecord> {
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
                """.trimIndent(),
            )
            if (forUpdate) append("\nFOR UPDATE")
        }
    return prepareStatement(query).use { statement ->
        statement.setObject(1, conversationId)
        statement.executeQuery().use { rs ->
            buildList {
                while (rs.next()) add(rs.toPartnerChatInviteRecord())
            }
        }
    }
}

/** What an accept or decline acts on ([resolvePartnerInviteAction]). */
internal sealed interface PartnerInviteAction {
    data class Respond(
        val invite: PartnerChatInviteRecord,
        val role: PartnerInviteUserRole,
    ) : PartnerInviteAction

    /** A repeat of the caller's own answer after their invite was resolved (a lost response). */
    data object OwnInviteAlreadyResolved : PartnerInviteAction
}

/**
 * The open invite [userId] accepts or declines, and in which role, or null when there is none.
 *  - An invitee acts on their own invite only (at most one open per person per thread, by the unique
 *    index); naming any other invite is refused, so an invited person never approves anyone.
 *  - Anyone else approves or declines, as the third party, the invite named by [inviteId] (of this
 *    conversation). The approver must be an active participant who is not that invite's inviter
 *    ([partnerInviteUserRole]); invited partners are not active, so neither approves the other.
 *  - Without an id (older clients): a repeat by someone whose own invite here was resolved after an
 *    invite still open was created is a retry of their own answer (they could not have been shown that
 *    other invite while invited), never an approval; otherwise only when exactly one open invite awaits
 *    this caller's approval is it the one. Anything else is refused.
 * Locks every open invite row of the thread in one order (call inside the accept/decline transaction).
 */
internal fun Connection.resolvePartnerInviteAction(
    userId: UUID,
    conversationId: UUID,
    inviteId: UUID?,
): PartnerInviteAction? {
    val open = loadOpenPartnerInvites(conversationId, forUpdate = true)
    open.firstOrNull { it.invitedPartnerUserId == userId }?.let { own ->
        return if (inviteId == null || inviteId == own.inviteId) {
            PartnerInviteAction.Respond(own, PartnerInviteUserRole.InvitedPartner)
        } else {
            null
        }
    }
    if (inviteId != null) {
        val named = open.firstOrNull { it.inviteId == inviteId } ?: return null
        return PartnerInviteAction
            .Respond(named, PartnerInviteUserRole.ThirdPartyApprover)
            .takeIf { partnerInviteUserRole(userId, named) == PartnerInviteUserRole.ThirdPartyApprover }
    }
    if (ownInviteResolvedSince(userId, conversationId, open.minOfOrNull { it.createdAt })) {
        return PartnerInviteAction.OwnInviteAlreadyResolved
    }
    return open
        .filter { invite ->
            invite.thirdPartyStatus == PARTNER_INVITE_PENDING &&
                partnerInviteUserRole(userId, invite) == PartnerInviteUserRole.ThirdPartyApprover
        }.singleOrNull()
        ?.let { PartnerInviteAction.Respond(it, PartnerInviteUserRole.ThirdPartyApprover) }
}

private fun Connection.ownInviteResolvedSince(
    userId: UUID,
    conversationId: UUID,
    since: java.time.Instant?,
): Boolean {
    since ?: return false
    return prepareStatement(
        """
        SELECT EXISTS (
            SELECT 1 FROM partner_chat_invites
            WHERE thread_id = ?
              AND invited_partner_user_id = ?
              AND resolved_at IS NOT NULL
              AND resolved_at >= ?
        )
        """.trimIndent(),
    ).use { statement ->
        statement.setObject(1, conversationId)
        statement.setObject(2, userId)
        statement.setTimestamp(3, java.sql.Timestamp.from(since))
        statement.executeQuery().use { rs ->
            rs.next()
            rs.getBoolean(1)
        }
    }
}

/**
 * Blocks between [inviteeUserId] and anyone else present in the thread (active participants and the
 * other open invitees), checked inside the transaction that would make the invitee active.
 */
internal fun Connection.inviteeBlockedInConversation(
    conversationId: UUID,
    inviteeUserId: UUID,
): Boolean =
    prepareStatement(
        """
        SELECT EXISTS (
            SELECT 1
            FROM conversation_participants other
            JOIN user_blocks ub
              ON (ub.blocker_user_id = ? AND ub.blocked_user_id = other.user_id)
              OR (ub.blocker_user_id = other.user_id AND ub.blocked_user_id = ?)
            WHERE other.conversation_id = ?
              AND other.user_id <> ?
              AND other.left_at IS NULL
              AND other.invitation_status IN ('active', 'invited')
        )
        """.trimIndent(),
    ).use { statement ->
        statement.setObject(1, inviteeUserId)
        statement.setObject(2, inviteeUserId)
        statement.setObject(3, conversationId)
        statement.setObject(4, inviteeUserId)
        statement.executeQuery().use { rs ->
            rs.next()
            rs.getBoolean(1)
        }
    }

/**
 * The people an invitee would join changed (someone became active, or an active person left): every
 * invitee who had already said yes must say yes again, knowing who is there now. Same transaction as
 * the change.
 */
internal fun Connection.resetWaitingPartnerAcceptances(conversationId: UUID) {
    prepareStatement(
        """
        UPDATE partner_chat_invites
        SET partner_status = ?
        WHERE thread_id = ?
          AND resolved_at IS NULL
          AND partner_status = ?
        """.trimIndent(),
    ).use { statement ->
        statement.setString(1, PARTNER_INVITE_PENDING)
        statement.setObject(2, conversationId)
        statement.setString(3, PARTNER_INVITE_ACCEPTED)
        statement.executeUpdate()
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
