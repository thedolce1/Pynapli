package com.pynapli.server.messaging

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

class JdbcMessagingRepository(
    private val dataSource: DataSource
) : MessagingRepository {
    override suspend fun listConversations(userId: UUID, limit: Int): List<ConversationRecord> =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection.listConversationIds(userId, limit).mapNotNull { conversationId ->
                    connection.loadConversation(userId, conversationId)
                }
            }
        }

    override suspend fun getOrCreateDirectConversation(
        viewerUserId: UUID,
        targetUserId: UUID,
        origin: String,
        conversationIdFactory: () -> UUID
    ): ConversationRecord? = withContext(Dispatchers.IO) {
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                if (!connection.targetAllowsConversation(targetUserId, origin)) {
                    connection.rollback()
                    return@withContext null
                }

                val existingId = connection.findDirectConversationId(viewerUserId, targetUserId)
                val conversationId = existingId ?: conversationIdFactory()
                if (existingId == null) {
                    connection.insertDirectConversation(
                        conversationId = conversationId,
                        viewerUserId = viewerUserId,
                        targetUserId = targetUserId,
                        origin = origin
                    )
                }

                connection.commit()
                connection.loadConversation(viewerUserId, conversationId)
            } catch (error: Throwable) {
                connection.rollback()
                throw error
            } finally {
                connection.autoCommit = true
            }
        }
    }

    override suspend fun getConversationDetail(
        userId: UUID,
        conversationId: UUID,
        limit: Int,
        before: MessagePageCursor?
    ): ConversationDetailRecord? = withContext(Dispatchers.IO) {
        dataSource.connection.use { connection ->
            val conversation = connection.loadConversation(userId, conversationId) ?: return@withContext null
            val page = connection.loadMessages(userId, conversationId, limit, before)
            ConversationDetailRecord(
                conversation = conversation,
                messages = page.messages,
                nextCursor = page.nextCursor
            )
        }
    }

    override suspend fun markConversationRead(
        userId: UUID,
        conversationId: UUID
    ): ConversationRecord? = withContext(Dispatchers.IO) {
        dataSource.connection.use { connection ->
            val updatedRows = connection.markRead(userId, conversationId)
            if (updatedRows == 0) return@withContext null
            connection.loadConversation(userId, conversationId)
        }
    }

    override suspend fun sendMessage(
        userId: UUID,
        conversationId: UUID,
        body: String,
        messageIdFactory: () -> UUID
    ): MessageRecord? = withContext(Dispatchers.IO) {
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                if (!connection.isActiveParticipant(userId, conversationId)) {
                    connection.rollback()
                    return@withContext null
                }

                val messageId = messageIdFactory()
                val message = connection.prepareStatement(
                    """
                    INSERT INTO messages (id, conversation_id, sender_user_id, body, moderation_status)
                    VALUES (?, ?, ?, ?, 'visible')
                    RETURNING id, conversation_id, sender_user_id, body, created_at, moderation_status
                    """.trimIndent()
                ).use { statement ->
                    statement.setObject(1, messageId)
                    statement.setObject(2, conversationId)
                    statement.setObject(3, userId)
                    statement.setString(4, body)
                    statement.executeQuery().use { rs ->
                        rs.next()
                        rs.toMessageRecord(deliveryStatus = "sent")
                    }
                }

                connection.prepareStatement(
                    """
                    UPDATE conversations
                    SET updated_at = ?
                    WHERE id = ?
                    """.trimIndent()
                ).use { statement ->
                    statement.setTimestamp(1, Timestamp.from(message.createdAt))
                    statement.setObject(2, conversationId)
                    statement.executeUpdate()
                }

                connection.prepareStatement(
                    """
                    UPDATE conversation_participants
                    SET last_read_at = ?
                    WHERE conversation_id = ?
                      AND user_id = ?
                      AND left_at IS NULL
                    """.trimIndent()
                ).use { statement ->
                    statement.setTimestamp(1, Timestamp.from(message.createdAt))
                    statement.setObject(2, conversationId)
                    statement.setObject(3, userId)
                    statement.executeUpdate()
                }

                connection.commit()
                message
            } catch (error: Throwable) {
                connection.rollback()
                throw error
            } finally {
                connection.autoCommit = true
            }
        }
    }

    private fun Connection.targetAllowsConversation(targetUserId: UUID, origin: String): Boolean {
        val allowGridMessages = prepareStatement(
            """
            SELECT ds.allow_grid_messages
            FROM users u
            JOIN profiles p ON p.user_id = u.id
            JOIN discovery_settings ds ON ds.user_id = u.id
            WHERE u.id = ?
              AND u.status = 'active'
              AND u.deleted_at IS NULL
              AND p.visibility_status = 'visible'
              AND ds.grid_visible = true
              AND ds.incognito = false
            """.trimIndent()
        ).use { statement ->
            statement.setObject(1, targetUserId)
            statement.executeQuery().use { rs ->
                if (!rs.next()) return false
                rs.getBoolean("allow_grid_messages")
            }
        }

        return origin != "grid" || allowGridMessages
    }

    private fun Connection.listConversationIds(userId: UUID, limit: Int): List<UUID> =
        prepareStatement(
            """
            SELECT c.id
            FROM conversations c
            JOIN conversation_participants viewer_cp
              ON viewer_cp.conversation_id = c.id
             AND viewer_cp.user_id = ?
             AND viewer_cp.left_at IS NULL
            ORDER BY c.updated_at DESC, c.id DESC
            LIMIT ?
            """.trimIndent()
        ).use { statement ->
            statement.setObject(1, userId)
            statement.setInt(2, limit.coerceIn(1, 100))
            statement.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) add(rs.getObject("id", UUID::class.java))
                }
            }
        }

    private fun Connection.findDirectConversationId(userA: UUID, userB: UUID): UUID? =
        prepareStatement(
            """
            SELECT conversation_id
            FROM direct_conversations
            WHERE LEAST(user_a_id, user_b_id) = LEAST(?::uuid, ?::uuid)
              AND GREATEST(user_a_id, user_b_id) = GREATEST(?::uuid, ?::uuid)
            """.trimIndent()
        ).use { statement ->
            statement.setObject(1, userA)
            statement.setObject(2, userB)
            statement.setObject(3, userA)
            statement.setObject(4, userB)
            statement.executeQuery().use { rs ->
                if (rs.next()) rs.getObject("conversation_id", UUID::class.java) else null
            }
        }

    private fun Connection.insertDirectConversation(
        conversationId: UUID,
        viewerUserId: UUID,
        targetUserId: UUID,
        origin: String
    ) {
        prepareStatement(
            """
            INSERT INTO conversations (id, kind, origin, created_by_user_id)
            VALUES (?, 'direct', ?, ?)
            """.trimIndent()
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
            """.trimIndent()
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
            """.trimIndent()
        ).use { statement ->
            statement.setObject(1, conversationId)
            statement.setObject(2, viewerUserId)
            statement.setObject(3, conversationId)
            statement.setObject(4, targetUserId)
            statement.executeUpdate()
        }
    }

    private fun Connection.loadConversation(userId: UUID, conversationId: UUID): ConversationRecord? {
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
                            displayName = rs.getString("display_name")
                        )
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
            updatedAt = updatedAt ?: return null
        )
    }

    private fun Connection.loadLastMessage(userId: UUID, conversationId: UUID): MessageRecord? =
        prepareStatement(
            """
            SELECT id,
                   conversation_id,
                   sender_user_id,
                   body,
                   created_at,
                   moderation_status,
                   CASE
                       WHEN sender_user_id <> ? THEN 'received'
                       WHEN NOT EXISTS (
                           SELECT 1
                           FROM conversation_participants cp
                           WHERE cp.conversation_id = messages.conversation_id
                             AND cp.user_id <> messages.sender_user_id
                             AND cp.left_at IS NULL
                             AND cp.last_read_at < messages.created_at
                       ) THEN 'read'
                       ELSE 'sent'
                   END AS delivery_status
            FROM messages
            WHERE conversation_id = ?
              AND deleted_at IS NULL
            ORDER BY created_at DESC, id DESC
            LIMIT 1
            """.trimIndent()
        ).use { statement ->
            statement.setObject(1, userId)
            statement.setObject(2, conversationId)
            statement.executeQuery().use { rs ->
                if (rs.next()) rs.toMessageRecord(deliveryStatus = rs.getString("delivery_status")) else null
            }
        }

    private fun Connection.loadMessages(
        userId: UUID,
        conversationId: UUID,
        limit: Int,
        before: MessagePageCursor?
    ): MessagePageRecord {
        val pageSize = limit.coerceIn(1, 100)
        val query = buildString {
            append(
                """
                SELECT id,
                       conversation_id,
                       sender_user_id,
                       body,
                       created_at,
                       moderation_status,
                       CASE
                           WHEN sender_user_id <> ? THEN 'received'
                           WHEN NOT EXISTS (
                               SELECT 1
                               FROM conversation_participants cp
                               WHERE cp.conversation_id = messages.conversation_id
                                 AND cp.user_id <> messages.sender_user_id
                                 AND cp.left_at IS NULL
                                 AND cp.last_read_at < messages.created_at
                           ) THEN 'read'
                           ELSE 'sent'
                       END AS delivery_status
                FROM messages
                WHERE conversation_id = ?
                  AND deleted_at IS NULL
                """.trimIndent()
            )
            append('\n')
            if (before != null) {
                append(
                    """
                      AND (
                          created_at < ?
                          OR (created_at = ? AND id < ?)
                      )
                    """.trimIndent()
                )
                append('\n')
            }
            append(
                """
                ORDER BY created_at DESC, id DESC
                LIMIT ?
                """.trimIndent()
            )
        }

        val newestFirst = prepareStatement(query).use { statement ->
            var index = 1
            statement.setObject(index++, userId)
            statement.setObject(index++, conversationId)
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
            nextCursor = if (newestFirst.size > pageSize) {
                pageMessages.lastOrNull()?.toCursor()
            } else {
                null
            }
        )
    }

    private fun Connection.isActiveParticipant(userId: UUID, conversationId: UUID): Boolean =
        prepareStatement(
            """
            SELECT EXISTS (
                SELECT 1
                FROM conversation_participants
                WHERE conversation_id = ?
                  AND user_id = ?
                  AND left_at IS NULL
            )
            """.trimIndent()
        ).use { statement ->
            statement.setObject(1, conversationId)
            statement.setObject(2, userId)
            statement.executeQuery().use { rs ->
                rs.next()
                rs.getBoolean(1)
            }
        }

    private fun Connection.markRead(userId: UUID, conversationId: UUID): Int =
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
            """.trimIndent()
        ).use { statement ->
            statement.setObject(1, conversationId)
            statement.setObject(2, conversationId)
            statement.setObject(3, userId)
            statement.executeUpdate()
        }

    private fun Connection.loadUnreadCount(userId: UUID, conversationId: UUID): Int =
        prepareStatement(
            """
            SELECT COUNT(*) AS unread_count
            FROM messages m
            JOIN conversation_participants cp
              ON cp.conversation_id = m.conversation_id
             AND cp.user_id = ?
             AND cp.left_at IS NULL
            WHERE m.conversation_id = ?
              AND m.sender_user_id <> ?
              AND m.deleted_at IS NULL
              AND m.moderation_status = 'visible'
              AND m.created_at > cp.last_read_at
            """.trimIndent()
        ).use { statement ->
            statement.setObject(1, userId)
            statement.setObject(2, conversationId)
            statement.setObject(3, userId)
            statement.executeQuery().use { rs ->
                rs.next()
                rs.getInt("unread_count")
            }
        }

    private fun ResultSet.toMessageRecord(deliveryStatus: String): MessageRecord = MessageRecord(
        messageId = getObject("id", UUID::class.java),
        conversationId = getObject("conversation_id", UUID::class.java),
        senderUserId = getObject("sender_user_id", UUID::class.java),
        body = getString("body"),
        createdAt = getTimestamp("created_at").toInstant(),
        status = getString("moderation_status"),
        deliveryStatus = deliveryStatus
    )

    private fun MessageRecord.toCursor(): MessagePageCursor = MessagePageCursor(
        createdAt = createdAt,
        messageId = messageId
    )

    companion object {
        private val CONVERSATION_QUERY = """
            SELECT c.kind,
                   c.origin,
                   c.updated_at,
                   cp.user_id AS participant_user_id,
                   COALESCE(NULLIF(p.display_name, ''), 'Pynapli User') AS display_name
            FROM conversations c
            JOIN conversation_participants viewer_cp
              ON viewer_cp.conversation_id = c.id
             AND viewer_cp.user_id = ?
             AND viewer_cp.left_at IS NULL
            JOIN conversation_participants cp
              ON cp.conversation_id = c.id
             AND cp.left_at IS NULL
            JOIN users u ON u.id = cp.user_id
            JOIN profiles p ON p.user_id = u.id
            WHERE c.id = ?
            ORDER BY cp.joined_at ASC, cp.user_id ASC
        """.trimIndent()
    }
}
