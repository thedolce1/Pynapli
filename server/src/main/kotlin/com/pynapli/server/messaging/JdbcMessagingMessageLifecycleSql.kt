package com.pynapli.server.messaging

import java.sql.Connection
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

internal fun Connection.deleteOwnVisibleMessage(
    userId: UUID,
    messageId: UUID,
): MessageRecord? =
    prepareStatement(
        """
        UPDATE messages
        SET deleted_at = now()
        WHERE id = ?
          AND sender_user_id = ?
          AND deleted_at IS NULL
          AND moderation_status = 'visible'
          AND EXISTS (
              SELECT 1
              FROM conversation_participants cp
              WHERE cp.conversation_id = messages.conversation_id
                AND cp.user_id = ?
                AND cp.left_at IS NULL
                AND cp.invitation_status IN ('active', 'invited')
          )
        RETURNING id,
                  conversation_id,
                  sender_user_id,
                  '' AS body,
                  created_at,
                  moderation_status,
                  deleted_at,
                  message_type,
                  media_asset_id
        """.trimIndent(),
    ).use { statement ->
        statement.setObject(1, messageId)
        statement.setObject(2, userId)
        statement.setObject(3, userId)
        statement.executeQuery().use { rs ->
            if (rs.next()) rs.toMessageRecord(deliveryStatus = MESSAGE_DELIVERY_DELETED) else null
        }
    }

/**
 * Records [userId]'s reveal of a media message, but only when the message carries media and the
 * viewer is an active participant (the INSERT...SELECT no-ops otherwise). Idempotent.
 */
internal fun Connection.insertMessageMediaReveal(
    userId: UUID,
    messageId: UUID,
) {
    prepareStatement(
        """
        INSERT INTO message_media_reveals (message_id, user_id)
        SELECT m.id, ?
        FROM messages m
        JOIN conversation_participants cp
          ON cp.conversation_id = m.conversation_id
         AND cp.user_id = ?
         AND cp.left_at IS NULL
         AND cp.invitation_status = 'active'
        WHERE m.id = ?
          AND m.media_asset_id IS NOT NULL
          AND m.deleted_at IS NULL
        ON CONFLICT (message_id, user_id) DO NOTHING
        """.trimIndent(),
    ).use { statement ->
        statement.setObject(1, userId)
        statement.setObject(2, userId)
        statement.setObject(3, messageId)
        statement.executeUpdate()
    }
}

internal fun Connection.countRecentMessagesByFingerprint(
    userId: UUID,
    bodyFingerprint: String,
    since: Instant,
): Int =
    prepareStatement(
        """
        SELECT COUNT(*) AS duplicate_count
        FROM messages
        WHERE sender_user_id = ?
          AND body_fingerprint = ?
          AND created_at >= ?
        """.trimIndent(),
    ).use { statement ->
        statement.setObject(1, userId)
        statement.setString(2, bodyFingerprint)
        statement.setTimestamp(3, Timestamp.from(since))
        statement.executeQuery().use { rs ->
            rs.next()
            rs.getInt("duplicate_count")
        }
    }

internal fun Connection.hasSentMessageInConversation(
    userId: UUID,
    conversationId: UUID,
): Boolean =
    prepareStatement(
        """
        SELECT EXISTS (
            SELECT 1
            FROM messages m
            JOIN conversation_participants cp
              ON cp.conversation_id = m.conversation_id
             AND cp.user_id = ?
             AND cp.left_at IS NULL
             AND cp.invitation_status = 'active'
            WHERE m.conversation_id = ?
              AND m.sender_user_id = ?
        )
        """.trimIndent(),
    ).use { statement ->
        statement.setObject(1, userId)
        statement.setObject(2, conversationId)
        statement.setObject(3, userId)
        statement.executeQuery().use { rs ->
            rs.next()
            rs.getBoolean(1)
        }
    }
