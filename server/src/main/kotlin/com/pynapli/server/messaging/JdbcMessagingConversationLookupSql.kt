package com.pynapli.server.messaging

import com.pynapli.server.contactblocks.ContactBlockSql
import java.sql.Connection
import java.time.Instant
import java.util.UUID

internal fun Connection.listConversationIds(
    userId: UUID,
    limit: Int,
    after: ConversationPageCursor? = null,
    // "Hide from people you know": a conversation started by someone hiding from the viewer is never
    // listed for them (such a conversation stays direct: only the viewer could approve a third person).
    viewerContactToken: String = ContactBlockSql.NO_VIEWER_TOKEN,
): List<UUID> =
    prepareStatement(
        """
        SELECT c.id
        FROM conversations c
        JOIN conversation_participants viewer_cp
          ON viewer_cp.conversation_id = c.id
         AND viewer_cp.user_id = ?
         AND viewer_cp.left_at IS NULL
         AND viewer_cp.invitation_status IN ('active', 'invited')
        WHERE (?::timestamptz IS NULL OR (c.updated_at, c.id) < (?::timestamptz, ?::uuid))
          AND (
           c.created_by_user_id = ?
           OR viewer_cp.invitation_status = 'invited'
           OR $MATCHED_DIRECT_CONVERSATION_CLAUSE
           OR EXISTS (
               SELECT 1
               FROM messages m
               WHERE m.conversation_id = c.id
                 AND (
                     (m.deleted_at IS NOT NULL AND m.moderation_status = 'visible')
                     OR (
                         m.deleted_at IS NULL
                         AND (
                             m.moderation_status = 'visible'
                             OR (m.moderation_status = 'held' AND m.sender_user_id = ?)
                         )
                     )
                 )
           )
          )
          AND ${ContactBlockSql.notHiddenFromViewer("c.created_by_user_id")}
        ORDER BY c.updated_at DESC, c.id DESC
        LIMIT ?
        """.trimIndent(),
    ).use { statement ->
        val afterTime = after?.updatedAt?.let { java.sql.Timestamp.from(it) }
        statement.setObject(1, userId)
        statement.setTimestamp(2, afterTime)
        statement.setTimestamp(3, afterTime)
        statement.setObject(4, after?.conversationId)
        statement.setObject(5, userId)
        statement.setObject(6, userId)
        statement.setString(7, viewerContactToken) // hidden-from rule
        statement.setInt(8, limit.coerceIn(1, 101))
        statement.executeQuery().use { rs ->
            buildList {
                while (rs.next()) add(rs.getObject("id", UUID::class.java))
            }
        }
    }

internal fun Connection.findDirectConversationId(
    userA: UUID,
    userB: UUID,
): UUID? =
    prepareStatement(
        """
        SELECT conversation_id
        FROM direct_conversations
        WHERE LEAST(user_a_id, user_b_id) = LEAST(?::uuid, ?::uuid)
          AND GREATEST(user_a_id, user_b_id) = GREATEST(?::uuid, ?::uuid)
        """.trimIndent(),
    ).use { statement ->
        statement.setObject(1, userA)
        statement.setObject(2, userB)
        statement.setObject(3, userA)
        statement.setObject(4, userB)
        statement.executeQuery().use { rs ->
            if (rs.next()) rs.getObject("conversation_id", UUID::class.java) else null
        }
    }

internal fun Connection.insertDirectConversation(
    conversationId: UUID,
    viewerUserId: UUID,
    targetUserId: UUID,
    origin: String,
) {
    prepareStatement(
        """
        INSERT INTO conversations (id, kind, origin, created_by_user_id)
        VALUES (?, 'direct', ?, ?)
        """.trimIndent(),
    ).use { statement ->
        statement.setObject(1, conversationId)
        statement.setString(2, origin)
        statement.setObject(3, viewerUserId)
        statement.executeUpdate()
    }

    prepareStatement(
        """
        INSERT INTO direct_conversations (conversation_id, user_a_id, user_b_id)
        VALUES (?, LEAST(?::uuid, ?::uuid), GREATEST(?::uuid, ?::uuid))
        """.trimIndent(),
    ).use { statement ->
        statement.setObject(1, conversationId)
        statement.setObject(2, viewerUserId)
        statement.setObject(3, targetUserId)
        statement.setObject(4, viewerUserId)
        statement.setObject(5, targetUserId)
        statement.executeUpdate()
    }

    prepareStatement(
        """
        INSERT INTO conversation_participants (conversation_id, user_id)
        VALUES (?, ?), (?, ?)
        """.trimIndent(),
    ).use { statement ->
        statement.setObject(1, conversationId)
        statement.setObject(2, viewerUserId)
        statement.setObject(3, conversationId)
        statement.setObject(4, targetUserId)
        statement.executeUpdate()
    }
}

internal fun Connection.loadConversation(
    userId: UUID,
    conversationId: UUID,
    // "Hide from people you know": the viewer's token, so the preview and the unread count leave out
    // messages from anyone hiding from the viewer (participants are stripped in MessagingDtoMapper).
    viewerContactToken: String = ContactBlockSql.NO_VIEWER_TOKEN,
): ConversationRecord? {
    val participants = mutableListOf<ConversationParticipantRecord>()
    var kind: String? = null
    var origin: String? = null
    var updatedAt: Instant? = null

    prepareStatement(CONVERSATION_QUERY).use { statement ->
        statement.setObject(1, userId)
        statement.setObject(2, conversationId)
        statement.executeQuery().use { rs ->
            while (rs.next()) {
                kind = rs.getString("kind")
                origin = rs.getString("origin")
                updatedAt = rs.getTimestamp("updated_at").toInstant()
                participants.add(
                    ConversationParticipantRecord(
                        userId = rs.getObject("participant_user_id", UUID::class.java),
                        displayName = rs.getString("display_name"),
                        status = rs.getString("invitation_status"),
                        invitedByUserId = rs.getObject("invited_by_user_id", UUID::class.java),
                        messageExpirySeconds = rs.getInt("message_expiry_seconds").takeUnless { rs.wasNull() },
                        profilePhotoMediaAssetId =
                            rs.getObject("profile_photo_media_asset_id", UUID::class.java),
                    ),
                )
            }
        }
    }

    val effectiveExpirySeconds = participants.effectiveExpirySeconds()
    // Open invites the viewer is party to: all of them for an active participant (inviter or
    // approver), only their own for an invited person.
    val viewerActive = participants.any { it.userId == userId && it.status == "active" }
    val viewerInvites =
        loadOpenPartnerInvites(conversationId, forUpdate = false).filter { invite ->
            viewerActive || invite.invitedPartnerUserId == userId
        }
    // An invited (not yet active) person sees only who is already in the chat and their own invite:
    // never the other open invitees nor who invited anyone else, which would reveal links others hide.
    // Everyone is shown once they are active (an inviter reveals the link to the chat by inviting).
    val visibleParticipants =
        if (viewerActive) {
            participants
        } else {
            participants
                .filter { it.userId == userId || it.status == "active" }
                .map { if (it.userId == userId) it else it.copy(invitedByUserId = null) }
        }
    return ConversationRecord(
        conversationId = conversationId,
        kind = kind ?: return null,
        origin = origin ?: return null,
        participants = visibleParticipants,
        lastMessage = loadLastMessage(userId, conversationId, effectiveExpirySeconds, viewerContactToken),
        unreadCount = loadUnreadCount(userId, conversationId, viewerContactToken),
        updatedAt = updatedAt ?: return null,
        partnerInvite = viewerInvites.primaryFor(userId),
        partnerInvites = viewerInvites,
        viewerExpirySeconds = participants.firstOrNull { it.userId == userId }?.messageExpirySeconds,
        effectiveExpirySeconds = effectiveExpirySeconds,
        newMatch = loadNewMatch(userId, conversationId),
    )
}

/**
 * The single `partnerInvite` older clients read: with one open invite it is that invite (as before);
 * with several, the one this viewer acts on first (their own as invitee, else one awaiting their
 * approval, else the newest), matching what an accept or decline without an invite id resolves to.
 */
private fun List<PartnerChatInviteRecord>.primaryFor(userId: UUID): PartnerChatInviteRecord? =
    firstOrNull { it.invitedPartnerUserId == userId }
        ?: firstOrNull { it.inviterUserId != userId && it.thirdPartyStatus == PARTNER_INVITE_PENDING }
        ?: firstOrNull { it.inviterUserId != userId }
        ?: firstOrNull()

/** Strictest (smallest) non-null timer across active participants; null when all are off. */
internal fun List<ConversationParticipantRecord>.effectiveExpirySeconds(): Int? =
    asSequence()
        .filter { it.status == "active" }
        .mapNotNull { it.messageExpirySeconds }
        .minOrNull()

private val CONVERSATION_QUERY =
    """
    SELECT c.kind,
           c.origin,
           c.updated_at,
           cp.user_id AS participant_user_id,
           cp.invitation_status,
           cp.invited_by_user_id,
           cp.message_expiry_seconds,
           -- A thread outlives an account: say so instead of showing a blank "Pynapli User".
           CASE
               WHEN u.status = 'deleted' OR u.deleted_at IS NOT NULL THEN 'Deleted member'
               WHEN u.status <> 'active' THEN 'Unavailable member'
               ELSE COALESCE(NULLIF(p.display_name, ''), 'Pynapli User')
           END AS display_name,
           profile_photo.media_asset_id AS profile_photo_media_asset_id
    FROM conversations c
    JOIN conversation_participants viewer_cp
      ON viewer_cp.conversation_id = c.id
     AND viewer_cp.user_id = ?
     AND viewer_cp.left_at IS NULL
     AND viewer_cp.invitation_status IN ('active', 'invited')
    JOIN conversation_participants cp
      ON cp.conversation_id = c.id
     AND cp.left_at IS NULL
     AND cp.invitation_status IN ('active', 'invited')
    JOIN users u ON u.id = cp.user_id
    JOIN profiles p ON p.user_id = u.id
    -- Public primary profile photo: same projection as the grid/profile thumbnail, so a chat
    -- avatar shows exactly the photo that participant already exposes on their card (approved,
    -- visible profile-album image), never a private-album asset.
    LEFT JOIN LATERAL (
        SELECT mai.media_asset_id
        FROM media_albums ma
        JOIN media_album_items mai
          ON mai.album_id = ma.id
         AND mai.removed_at IS NULL
        JOIN media_assets m
          ON m.id = mai.media_asset_id
         AND m.owner_user_id = ma.owner_user_id
         AND m.deleted_at IS NULL
        WHERE ma.owner_user_id = cp.user_id
          AND ma.kind = 'profile'
          AND ma.visibility_status = 'visible'
          AND ma.deleted_at IS NULL
          AND m.media_type = 'image'
          AND m.processing_status = 'ready'
          AND m.moderation_status = 'approved'
        ORDER BY mai.sort_order ASC, mai.added_at DESC, m.id ASC
        LIMIT 1
    ) profile_photo ON true
    WHERE c.id = ?
    ORDER BY cp.joined_at ASC, cp.user_id ASC
    """.trimIndent()
