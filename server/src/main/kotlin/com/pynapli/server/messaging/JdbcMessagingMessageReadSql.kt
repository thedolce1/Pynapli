package com.pynapli.server.messaging

import java.sql.Connection
import java.sql.Timestamp
import java.util.UUID

internal fun Connection.loadLastMessage(
    userId: UUID,
    conversationId: UUID,
    effectiveExpirySeconds: Int?,
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
               message_type,
               $MESSAGE_MEDIA_SELECT_COLUMNS
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
          AND $MESSAGE_EXPIRY_FILTER
        ORDER BY created_at DESC, id DESC
        LIMIT 1
        """.trimIndent(),
    ).use { statement ->
        statement.setObject(1, userId) // MESSAGE_MEDIA_SELECT_COLUMNS media_revealed (viewer)
        statement.setObject(2, userId) // delivery_status CASE (viewer)
        statement.setObject(3, conversationId)
        statement.setObject(4, userId)
        statement.setObject(5, userId)
        statement.setObject(6, effectiveExpirySeconds, java.sql.Types.INTEGER)
        statement.setObject(7, effectiveExpirySeconds, java.sql.Types.INTEGER)
        statement.executeQuery().use { rs ->
            if (rs.next()) rs.enrichMessageMedia(rs.toMessageRecord(deliveryStatus = rs.getString("delivery_status"))) else null
        }
    }

/** Loads a single message enriched with media facts for [userId], gated to an active participant. */
internal fun Connection.loadMessageForViewer(
    userId: UUID,
    messageId: UUID,
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
               message_type,
               $MESSAGE_MEDIA_SELECT_COLUMNS
               CASE
                   WHEN deleted_at IS NOT NULL THEN 'deleted'
                   WHEN moderation_status = 'held' THEN 'held_for_review'
                   WHEN sender_user_id <> ? THEN 'received'
                   ELSE 'sent'
               END AS delivery_status
        FROM messages
        WHERE id = ?
          AND deleted_at IS NULL
          AND EXISTS (
              SELECT 1 FROM conversation_participants cp
              WHERE cp.conversation_id = messages.conversation_id
                AND cp.user_id = ?
                AND cp.left_at IS NULL
                AND cp.invitation_status = 'active'
          )
        """.trimIndent(),
    ).use { statement ->
        statement.setObject(1, userId) // MESSAGE_MEDIA_SELECT_COLUMNS media_revealed (viewer)
        statement.setObject(2, userId) // delivery_status CASE (viewer)
        statement.setObject(3, messageId)
        statement.setObject(4, userId) // participant gate
        statement.executeQuery().use { rs ->
            if (rs.next()) rs.enrichMessageMedia(rs.toMessageRecord(deliveryStatus = rs.getString("delivery_status"))) else null
        }
    }

internal fun Connection.loadMessages(
    userId: UUID,
    conversationId: UUID,
    limit: Int,
    before: MessagePageCursor?,
    effectiveExpirySeconds: Int?,
): MessagePageRecord {
    val pageSize = limit.coerceIn(1, 100)
    val query = buildMessagePageQuery(before)

    val newestFirst =
        prepareStatement(query).use { statement ->
            var index = 1
            statement.setObject(index++, userId) // MESSAGE_MEDIA_SELECT_COLUMNS media_revealed (viewer)
            statement.setObject(index++, userId) // delivery_status CASE (viewer)
            statement.setObject(index++, conversationId)
            statement.setObject(index++, userId)
            statement.setObject(index++, userId)
            statement.setObject(index++, effectiveExpirySeconds, java.sql.Types.INTEGER)
            statement.setObject(index++, effectiveExpirySeconds, java.sql.Types.INTEGER)
            if (before != null) {
                val beforeTimestamp = Timestamp.from(before.createdAt)
                statement.setTimestamp(index++, beforeTimestamp)
                statement.setTimestamp(index++, beforeTimestamp)
                statement.setObject(index++, before.messageId)
            }
            statement.setInt(index, pageSize + 1)
            statement.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(rs.enrichMessageMedia(rs.toMessageRecord(deliveryStatus = rs.getString("delivery_status"))))
                    }
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

private fun buildMessagePageQuery(before: MessagePageCursor?): String =
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
                   message_type,
                   $MESSAGE_MEDIA_SELECT_COLUMNS
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
              AND $MESSAGE_EXPIRY_FILTER
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
