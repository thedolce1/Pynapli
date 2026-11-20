package com.pynapli.server.messaging

import com.pynapli.server.contactblocks.ContactBlockSql
import java.sql.Connection
import java.util.UUID

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
    // "Hide from people you know": the viewer's token; messages from anyone hiding from them don't count.
    viewerContactToken: String = ContactBlockSql.NO_VIEWER_TOKEN,
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
          AND ${ContactBlockSql.notHiddenFromViewer("m.sender_user_id")}
        """.trimIndent(),
    ).use { statement ->
        statement.setObject(1, userId)
        statement.setObject(2, conversationId)
        statement.setObject(3, userId)
        statement.setString(4, viewerContactToken)
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
