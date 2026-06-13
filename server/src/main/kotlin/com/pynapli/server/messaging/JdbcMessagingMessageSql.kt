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
    clientIdempotencyKey: String? = null,
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
            safety_reason,
            client_idempotency_key
        )
        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        RETURNING id, conversation_id, sender_user_id, body, created_at, moderation_status, deleted_at, message_type, media_asset_id
        """.trimIndent(),
    ).use { statement ->
        statement.setObject(1, messageId)
        statement.setObject(2, conversationId)
        statement.setObject(3, userId)
        statement.setString(4, body)
        statement.setString(5, moderationStatus)
        statement.setString(6, bodyFingerprint)
        statement.setString(7, safetyReason)
        statement.setString(8, clientIdempotencyKey)
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

/**
 * Returns the existing message a sender already created in this conversation under
 * [clientIdempotencyKey], if any — the idempotent-replay lookup. Delivery status mirrors the stored
 * moderation status so a replay reflects whether the original was held for review.
 */
internal fun Connection.findMessageByIdempotencyKey(
    userId: UUID,
    conversationId: UUID,
    clientIdempotencyKey: String,
): MessageRecord? =
    prepareStatement(
        """
        SELECT id, conversation_id, sender_user_id, body, created_at, moderation_status, deleted_at, message_type, media_asset_id
        FROM messages
        WHERE conversation_id = ?
          AND sender_user_id = ?
          AND client_idempotency_key = ?
        """.trimIndent(),
    ).use { statement ->
        statement.setObject(1, conversationId)
        statement.setObject(2, userId)
        statement.setString(3, clientIdempotencyKey)
        statement.executeQuery().use { rs ->
            if (!rs.next()) {
                null
            } else {
                val moderationStatus = rs.getString("moderation_status")
                rs.toMessageRecord(
                    deliveryStatus =
                        when {
                            rs.getTimestamp("deleted_at") != null -> DELIVERY_DELETED
                            moderationStatus == MessageModerationStatus.HELD -> DELIVERY_HELD_FOR_REVIEW
                            else -> DELIVERY_SENT
                        },
                )
            }
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
                  deleted_at,
                  message_type,
                  media_asset_id
        """.trimIndent(),
    ).use { statement ->
        statement.setObject(1, messageId)
        statement.setObject(2, userId)
        statement.setObject(3, userId)
        statement.executeQuery().use { rs ->
            if (rs.next()) rs.toMessageRecord(deliveryStatus = DELIVERY_DELETED) else null
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

/** Records a centered system notice (authored by [userId], type='system') describing a change. */
internal fun Connection.insertSystemMessage(
    messageId: UUID,
    userId: UUID,
    conversationId: UUID,
    body: String,
): MessageRecord =
    prepareStatement(
        """
        INSERT INTO messages (id, conversation_id, sender_user_id, body, moderation_status, message_type)
        VALUES (?, ?, ?, ?, 'visible', 'system')
        RETURNING id, conversation_id, sender_user_id, body, created_at, moderation_status, deleted_at, message_type, media_asset_id
        """.trimIndent(),
    ).use { statement ->
        statement.setObject(1, messageId)
        statement.setObject(2, conversationId)
        statement.setObject(3, userId)
        statement.setString(4, body)
        statement.executeQuery().use { rs ->
            rs.next()
            rs.toMessageRecord(deliveryStatus = DELIVERY_SENT)
        }
    }

/**
 * Inserts a media message (one chat photo + optional caption). Returns the record with media facts
 * + mediaRevealedByViewer=true, since the sender always sees their own photo.
 */
internal fun Connection.insertMediaMessage(
    messageId: UUID,
    userId: UUID,
    conversationId: UUID,
    mediaAssetId: UUID,
    contentType: String?,
    pixelWidth: Int?,
    pixelHeight: Int?,
    body: String,
    clientIdempotencyKey: String?,
): MessageRecord =
    prepareStatement(
        """
        INSERT INTO messages (
            id, conversation_id, sender_user_id, body, moderation_status, media_asset_id, client_idempotency_key
        )
        VALUES (?, ?, ?, ?, 'visible', ?, ?)
        RETURNING id, conversation_id, sender_user_id, body, created_at, moderation_status, deleted_at, message_type, media_asset_id
        """.trimIndent(),
    ).use { statement ->
        statement.setObject(1, messageId)
        statement.setObject(2, conversationId)
        statement.setObject(3, userId)
        statement.setString(4, body)
        statement.setObject(5, mediaAssetId)
        statement.setString(6, clientIdempotencyKey)
        statement.executeQuery().use { rs ->
            rs.next()
            rs.toMessageRecord(deliveryStatus = DELIVERY_SENT).copy(
                mediaContentType = contentType,
                mediaWidth = pixelWidth,
                mediaHeight = pixelHeight,
                mediaRevealedByViewer = true,
            )
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
