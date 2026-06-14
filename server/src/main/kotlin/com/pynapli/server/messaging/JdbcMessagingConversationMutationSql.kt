package com.pynapli.server.messaging

import java.sql.Connection
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

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

/** Sets the caller's own disappearing-messages timer; returns rows updated (0 = not active). */
internal fun Connection.setParticipantExpiry(
    userId: UUID,
    conversationId: UUID,
    expirySeconds: Int?,
): Int =
    prepareStatement(
        """
        UPDATE conversation_participants
        SET message_expiry_seconds = ?
        WHERE conversation_id = ?
          AND user_id = ?
          AND left_at IS NULL
          AND invitation_status = 'active'
        """.trimIndent(),
    ).use { statement ->
        statement.setObject(1, expirySeconds, java.sql.Types.INTEGER)
        statement.setObject(2, conversationId)
        statement.setObject(3, userId)
        statement.executeUpdate()
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

internal fun Connection.markConversationGroup(conversationId: UUID) {
    prepareStatement(
        """
        UPDATE conversations
        SET kind = 'group',
            updated_at = now()
        WHERE id = ?
        """.trimIndent(),
    ).use { statement ->
        statement.setObject(1, conversationId)
        statement.executeUpdate()
    }
}

internal fun Connection.touchConversation(conversationId: UUID) {
    prepareStatement(
        """
        UPDATE conversations
        SET updated_at = now()
        WHERE id = ?
        """.trimIndent(),
    ).use { statement ->
        statement.setObject(1, conversationId)
        statement.executeUpdate()
    }
}
