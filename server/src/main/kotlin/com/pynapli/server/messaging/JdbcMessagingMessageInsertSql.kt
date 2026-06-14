package com.pynapli.server.messaging

import java.sql.Connection
import java.util.UUID

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
                        MESSAGE_DELIVERY_HELD_FOR_REVIEW
                    } else {
                        MESSAGE_DELIVERY_SENT
                    },
            )
        }
    }

/**
 * Returns the existing message a sender already created in this conversation under
 * [clientIdempotencyKey], if any. Delivery status mirrors the stored moderation status so a replay
 * reflects whether the original was held for review.
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
                            rs.getTimestamp("deleted_at") != null -> MESSAGE_DELIVERY_DELETED
                            moderationStatus == MessageModerationStatus.HELD -> MESSAGE_DELIVERY_HELD_FOR_REVIEW
                            else -> MESSAGE_DELIVERY_SENT
                        },
                )
            }
        }
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
            rs.toMessageRecord(deliveryStatus = MESSAGE_DELIVERY_SENT)
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
            rs.toMessageRecord(deliveryStatus = MESSAGE_DELIVERY_SENT).copy(
                mediaContentType = contentType,
                mediaWidth = pixelWidth,
                mediaHeight = pixelHeight,
                mediaRevealedByViewer = true,
            )
        }
    }
