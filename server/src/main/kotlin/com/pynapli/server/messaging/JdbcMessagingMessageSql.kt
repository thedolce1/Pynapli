package com.pynapli.server.messaging

import java.sql.Connection
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

private const val DELIVERY_SENT = "sent"
private const val DELIVERY_HELD_FOR_REVIEW = "held_for_review"
private const val DELIVERY_DELETED = "deleted"

internal fun Connection.insertMessage(
    messageId: UUID,
    userId: UUID,
    conversationId: UUID,
    body: String,
    moderationStatus: String,
    bodyFingerprint: String?,
    safetyReason: String?,
): MessageRecord =
    prepareStatement(
        """
        INSERT INTO messages (
            id,
            conversation_id,
            sender_user_id,
            body,
            moderation_status,
            body_fingerprint,
            safety_reason
        )
        VALUES (?, ?, ?, ?, ?, ?, ?)
        RETURNING id, conversation_id, sender_user_id, body, created_at, moderation_status, deleted_at
        """.trimIndent(),
    ).use { statement ->
        statement.setObject(1, messageId)
        statement.setObject(2, conversationId)
        statement.setObject(3, userId)
        statement.setString(4, body)
        statement.setString(5, moderationStatus)
        statement.setString(6, bodyFingerprint)
        statement.setString(7, safetyReason)
        statement.executeQuery().use { rs ->
            rs.next()
            rs.toMessageRecord(
                deliveryStatus =
                    if (moderationStatus == MessageModerationStatus.HELD) {
                        DELIVERY_HELD_FOR_REVIEW
                    } else {
                        DELIVERY_SENT
                    },
            )
        }
    }

internal fun Connection.touchConversationAt(
    conversationId: UUID,
    updatedAt: Instant,
) {
    prepareStatement(
        """
        UPDATE conversations
        SET updated_at = ?
        WHERE id = ?
        """.trimIndent(),
    ).use { statement ->
        statement.setTimestamp(1, Timestamp.from(updatedAt))
        statement.setObject(2, conversationId)
        statement.executeUpdate()
    }
}

internal fun Connection.markSenderReadAt(
    userId: UUID,
    conversationId: UUID,
    readAt: Instant,
) {
    prepareStatement(
        """
        UPDATE conversation_participants
        SET last_read_at = ?
        WHERE conversation_id = ?
          AND user_id = ?
          AND left_at IS NULL
          AND invitation_status = 'active'
        """.trimIndent(),
    ).use { statement ->
        statement.setTimestamp(1, Timestamp.from(readAt))
        statement.setObject(2, conversationId)
        statement.setObject(3, userId)
        statement.executeUpdate()
    }
}

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
                  deleted_at
        """.trimIndent(),
    ).use { statement ->
        statement.setObject(1, messageId)
        statement.setObject(2, userId)
        statement.setObject(3, userId)
        statement.executeQuery().use { rs ->
            if (rs.next()) rs.toMessageRecord(deliveryStatus = DELIVERY_DELETED) else null
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

internal fun Connection.leaveConversationParticipant(
    userId: UUID,
    conversationId: UUID,
): Int =
    prepareStatement(
        """
        UPDATE conversation_participants
        SET invitation_status = 'left',
            invitation_responded_at = COALESCE(invitation_responded_at, now()),
            left_at = now()
        WHERE conversation_id = ?
          AND user_id = ?
          AND left_at IS NULL
          AND invitation_status IN ('active', 'invited')
        """.trimIndent(),
    ).use { statement ->
        statement.setObject(1, conversationId)
        statement.setObject(2, userId)
        statement.executeUpdate()
    }
