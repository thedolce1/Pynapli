package com.pynapli.server.messaging

import java.sql.Connection
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

internal fun Connection.listConversationIds(
    userId: UUID,
    limit: Int,
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
        WHERE c.created_by_user_id = ?
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
        ORDER BY c.updated_at DESC, c.id DESC
        LIMIT ?
        """.trimIndent(),
    ).use { statement ->
        statement.setObject(1, userId)
        statement.setObject(2, userId)
        statement.setObject(3, userId)
        statement.setInt(4, limit.coerceIn(1, 100))
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

internal fun Connection.loadLastMessage(
    userId: UUID,
    conversationId: UUID,
): MessageRecord? =
    prepareStatement(
        """
        SELECT id,
               conversation_id,
               sender_user_id,
               CASE WHEN deleted_at IS NULL THEN body ELSE '' END AS body,
               created_at,
               moderation_status,
               deleted_at,
               CASE
                   WHEN deleted_at IS NOT NULL THEN 'deleted'
                   WHEN moderation_status = 'held' THEN 'held_for_review'
                   WHEN sender_user_id <> ? THEN 'received'
                   WHEN NOT EXISTS (
                       SELECT 1
                       FROM conversation_participants cp
                       WHERE cp.conversation_id = messages.conversation_id
                         AND cp.user_id <> messages.sender_user_id
                         AND cp.left_at IS NULL
                         AND cp.invitation_status = 'active'
                         AND cp.last_read_at < messages.created_at
                   ) THEN 'read'
                   ELSE 'sent'
               END AS delivery_status
        FROM messages
        WHERE conversation_id = ?
          AND (
              (
                  deleted_at IS NOT NULL
                  AND (
                      moderation_status = 'visible'
                      OR (moderation_status = 'held' AND sender_user_id = ?)
                  )
              )
              OR (
                  deleted_at IS NULL
                  AND (
                      moderation_status = 'visible'
                      OR (moderation_status = 'held' AND sender_user_id = ?)
                  )
              )
          )
        ORDER BY created_at DESC, id DESC
        LIMIT 1
        """.trimIndent(),
    ).use { statement ->
        statement.setObject(1, userId)
        statement.setObject(2, conversationId)
        statement.setObject(3, userId)
        statement.setObject(4, userId)
        statement.executeQuery().use { rs ->
            if (rs.next()) rs.toMessageRecord(deliveryStatus = rs.getString("delivery_status")) else null
        }
    }

internal fun Connection.loadMessages(
    userId: UUID,
    conversationId: UUID,
    limit: Int,
    before: MessagePageCursor?,
): MessagePageRecord {
    val pageSize = limit.coerceIn(1, 100)
    val query =
        buildString {
            append(
                """
                SELECT id,
                       conversation_id,
                       sender_user_id,
                       CASE WHEN deleted_at IS NULL THEN body ELSE '' END AS body,
                       created_at,
                       moderation_status,
                       deleted_at,
                       CASE
                           WHEN deleted_at IS NOT NULL THEN 'deleted'
                           WHEN moderation_status = 'held' THEN 'held_for_review'
                           WHEN sender_user_id <> ? THEN 'received'
                           WHEN NOT EXISTS (
                               SELECT 1
                               FROM conversation_participants cp
                               WHERE cp.conversation_id = messages.conversation_id
                                 AND cp.user_id <> messages.sender_user_id
                                 AND cp.left_at IS NULL
                                 AND cp.invitation_status = 'active'
                                 AND cp.last_read_at < messages.created_at
                           ) THEN 'read'
                           ELSE 'sent'
                       END AS delivery_status
                FROM messages
                WHERE conversation_id = ?
                  AND (
                      (
                          deleted_at IS NOT NULL
                          AND (
                              moderation_status = 'visible'
                              OR (moderation_status = 'held' AND sender_user_id = ?)
                          )
                      )
                      OR (
                          deleted_at IS NULL
                          AND (
                              moderation_status = 'visible'
                              OR (moderation_status = 'held' AND sender_user_id = ?)
                          )
                      )
                  )
                """.trimIndent(),
            )
            append('\n')
            if (before != null) {
                append(
                    """
                    AND (
                        created_at < ?
                        OR (created_at = ? AND id < ?)
                    )
                    """.trimIndent(),
                )
                append('\n')
            }
            append(
                """
                ORDER BY created_at DESC, id DESC
                LIMIT ?
                """.trimIndent(),
            )
        }

    val newestFirst =
        prepareStatement(query).use { statement ->
            var index = 1
            statement.setObject(index++, userId)
            statement.setObject(index++, conversationId)
            statement.setObject(index++, userId)
            statement.setObject(index++, userId)
            if (before != null) {
                val beforeTimestamp = Timestamp.from(before.createdAt)
                statement.setTimestamp(index++, beforeTimestamp)
                statement.setTimestamp(index++, beforeTimestamp)
                statement.setObject(index++, before.messageId)
            }
            statement.setInt(index, pageSize + 1)
            statement.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) add(rs.toMessageRecord(deliveryStatus = rs.getString("delivery_status")))
                }
            }
        }

    val pageMessages = newestFirst.take(pageSize)
    return MessagePageRecord(
        messages = pageMessages.asReversed(),
        nextCursor =
            if (newestFirst.size > pageSize) {
                pageMessages.lastOrNull()?.toCursor()
            } else {
                null
            },
    )
}

internal fun Connection.isActiveParticipant(
    userId: UUID,
    conversationId: UUID,
): Boolean =
    prepareStatement(
        """
        SELECT EXISTS (
            SELECT 1
            FROM conversation_participants
            WHERE conversation_id = ?
              AND user_id = ?
              AND left_at IS NULL
              AND invitation_status = 'active'
        )
        """.trimIndent(),
    ).use { statement ->
        statement.setObject(1, conversationId)
        statement.setObject(2, userId)
        statement.executeQuery().use { rs ->
            rs.next()
            rs.getBoolean(1)
        }
    }

internal fun Connection.markRead(
    userId: UUID,
    conversationId: UUID,
): Int =
    prepareStatement(
        """
        UPDATE conversation_participants
        SET last_read_at = COALESCE(
            (
                SELECT MAX(created_at)
                FROM messages
                WHERE conversation_id = ?
                  AND deleted_at IS NULL
                  AND moderation_status = 'visible'
            ),
            now()
        )
        WHERE conversation_id = ?
          AND user_id = ?
          AND left_at IS NULL
          AND invitation_status IN ('active', 'invited')
        """.trimIndent(),
    ).use { statement ->
        statement.setObject(1, conversationId)
        statement.setObject(2, conversationId)
        statement.setObject(3, userId)
        statement.executeUpdate()
    }

internal fun Connection.loadUnreadCount(
    userId: UUID,
    conversationId: UUID,
): Int =
    prepareStatement(
        """
        SELECT COUNT(*) AS unread_count
        FROM messages m
        JOIN conversation_participants cp
          ON cp.conversation_id = m.conversation_id
         AND cp.user_id = ?
         AND cp.left_at IS NULL
         AND cp.invitation_status IN ('active', 'invited')
        WHERE m.conversation_id = ?
          AND m.sender_user_id <> ?
          AND m.deleted_at IS NULL
          AND m.moderation_status = 'visible'
          AND m.created_at > cp.last_read_at
        """.trimIndent(),
    ).use { statement ->
        statement.setObject(1, userId)
        statement.setObject(2, conversationId)
        statement.setObject(3, userId)
        statement.executeQuery().use { rs ->
            rs.next()
            rs.getInt("unread_count")
        }
    }

internal fun Connection.insertMessageReviewQueue(
    messageId: UUID,
    senderUserId: UUID,
    conversationId: UUID,
    reason: String,
) {
    prepareStatement(
        """
        INSERT INTO message_review_queue (
            id,
            message_id,
            sender_user_id,
            conversation_id,
            reason
        )
        VALUES (?, ?, ?, ?, ?)
        ON CONFLICT (message_id) DO NOTHING
        """.trimIndent(),
    ).use { statement ->
        statement.setObject(1, UUID.randomUUID())
        statement.setObject(2, messageId)
        statement.setObject(3, senderUserId)
        statement.setObject(4, conversationId)
        statement.setString(5, reason)
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
                    ),
                )
            }
        }
    }

    return ConversationRecord(
        conversationId = conversationId,
        kind = kind ?: return null,
        origin = origin ?: return null,
        participants = participants,
        lastMessage = loadLastMessage(userId, conversationId),
        unreadCount = loadUnreadCount(userId, conversationId),
        updatedAt = updatedAt ?: return null,
        partnerInvite = loadOpenPartnerInvite(conversationId, forUpdate = false),
    )
}

private val CONVERSATION_QUERY =
    """
    SELECT c.kind,
           c.origin,
           c.updated_at,
           cp.user_id AS participant_user_id,
           cp.invitation_status,
           cp.invited_by_user_id,
           COALESCE(NULLIF(p.display_name, ''), 'Pynapli User') AS display_name
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
    WHERE c.id = ?
    ORDER BY cp.joined_at ASC, cp.user_id ASC
    """.trimIndent()
