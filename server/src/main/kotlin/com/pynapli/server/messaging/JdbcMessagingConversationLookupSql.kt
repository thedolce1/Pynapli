package com.pynapli.server.messaging

import java.sql.Connection
import java.time.Instant
import java.util.UUID

internal fun Connection.listConversationIds(
    userId: UUID,
    limit: Int,
    after: ConversationPageCursor? = null,
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
        statement.setInt(7, limit.coerceIn(1, 101))
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
    return ConversationRecord(
        conversationId = conversationId,
        kind = kind ?: return null,
        origin = origin ?: return null,
        participants = participants,
        lastMessage = loadLastMessage(userId, conversationId, effectiveExpirySeconds),
        unreadCount = loadUnreadCount(userId, conversationId),
        updatedAt = updatedAt ?: return null,
        partnerInvite = loadOpenPartnerInvite(conversationId, forUpdate = false),
        viewerExpirySeconds = participants.firstOrNull { it.userId == userId }?.messageExpirySeconds,
        effectiveExpirySeconds = effectiveExpirySeconds,
    )
}

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
           COALESCE(NULLIF(p.display_name, ''), 'Pynapli User') AS display_name,
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
