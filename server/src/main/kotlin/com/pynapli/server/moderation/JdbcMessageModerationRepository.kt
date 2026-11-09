package com.pynapli.server.moderation

import com.pynapli.server.db.inTransaction
import com.pynapli.server.db.withConnection
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

class JdbcMessageModerationRepository(
    private val dataSource: DataSource,
) : MessageModerationRepository {
    override suspend fun listOpenMessageReviews(
        limit: Int,
        actorId: String,
        reason: String,
    ): List<MessageReviewRecord> =
        dataSource.withConnection { connection ->
            connection.inTransaction {
                connection.insertAdminAuditLog(
                    actorId = actorId,
                    action = "message_review_queue.list",
                    targetType = "message_review_queue",
                    targetId = null,
                    reason = reason,
                    metadataJson = """{"limit":$limit}""",
                )
                val items = connection.selectOpenMessageReviews(limit)
                connection.commit()
                items
            }
        }

    override suspend fun releaseHeldMessage(
        messageId: UUID,
        actorId: String,
        reason: String,
    ): MessageReviewActionRecord? =
        dataSource.withConnection { connection ->
            connection.reviewHeldMessage(
                messageId = messageId,
                actorId = actorId,
                reason = reason,
                messageStatus = "visible",
                queueStatus = "released",
                auditAction = "message_review.release",
            )
        }

    override suspend fun removeHeldMessage(
        messageId: UUID,
        actorId: String,
        reason: String,
    ): MessageReviewActionRecord? =
        dataSource.withConnection { connection ->
            connection.reviewHeldMessage(
                messageId = messageId,
                actorId = actorId,
                reason = reason,
                messageStatus = "removed",
                queueStatus = "removed",
                auditAction = "message_review.remove",
            )
        }

    private fun Connection.selectOpenMessageReviews(limit: Int): List<MessageReviewRecord> =
        prepareStatement(
            """
            SELECT q.id,
                   q.message_id,
                   q.sender_user_id,
                   q.conversation_id,
                   q.reason,
                   q.status,
                   q.created_at,
                   q.updated_at,
                   m.body
            FROM message_review_queue q
            JOIN messages m
              ON m.id = q.message_id
             AND m.moderation_status = 'held'
             AND m.deleted_at IS NULL
            WHERE q.status = 'open'
            ORDER BY q.created_at ASC, q.id ASC
            LIMIT ?
            """.trimIndent(),
        ).use { statement ->
            statement.setInt(1, limit)
            statement.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) add(rs.toMessageReviewRecord())
                }
            }
        }

    private fun Connection.reviewHeldMessage(
        messageId: UUID,
        actorId: String,
        reason: String,
        messageStatus: String,
        queueStatus: String,
        auditAction: String,
    ): MessageReviewActionRecord? {
        inTransaction {
            val queued =
                lockOpenQueuedMessage(messageId) ?: run {
                    rollback()
                    return null
                }
            val reviewedAt =
                updateMessageStatus(messageId, messageStatus) ?: run {
                    rollback()
                    return null
                }
            updateQueueStatus(messageId, queueStatus, reviewedAt)
            if (queueStatus == "released") {
                touchConversation(queued.conversationId)
            }
            insertAdminAuditLog(
                actorId = actorId,
                action = auditAction,
                targetType = "message",
                targetId = messageId,
                reason = reason,
                metadataJson =
                    """
                    {
                      "conversationId":"${queued.conversationId}",
                      "senderUserId":"${queued.senderUserId}",
                      "messageStatus":"$messageStatus",
                      "queueStatus":"$queueStatus"
                    }
                    """.trimIndent(),
            )
            commit()
            return MessageReviewActionRecord(
                messageId = messageId,
                conversationId = queued.conversationId,
                senderUserId = queued.senderUserId,
                messageStatus = messageStatus,
                queueStatus = queueStatus,
                reviewedAt = reviewedAt,
            )
        }
    }

    private fun Connection.lockOpenQueuedMessage(messageId: UUID): QueuedMessage? =
        prepareStatement(
            """
            SELECT q.conversation_id,
                   q.sender_user_id
            FROM message_review_queue q
            JOIN messages m
              ON m.id = q.message_id
             AND m.moderation_status = 'held'
             AND m.deleted_at IS NULL
            WHERE q.message_id = ?
              AND q.status = 'open'
            FOR UPDATE OF q, m
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, messageId)
            statement.executeQuery().use { rs ->
                if (rs.next()) {
                    QueuedMessage(
                        conversationId = rs.getObject("conversation_id", UUID::class.java),
                        senderUserId = rs.getObject("sender_user_id", UUID::class.java),
                    )
                } else {
                    null
                }
            }
        }

    private fun Connection.updateMessageStatus(
        messageId: UUID,
        messageStatus: String,
    ): Instant? =
        prepareStatement(
            """
            UPDATE messages
            SET moderation_status = ?,
                reviewed_at = now()
            WHERE id = ?
              AND moderation_status = 'held'
              AND deleted_at IS NULL
            RETURNING reviewed_at
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, messageStatus)
            statement.setObject(2, messageId)
            statement.executeQuery().use { rs ->
                if (rs.next()) rs.getTimestamp("reviewed_at").toInstant() else null
            }
        }

    private fun Connection.updateQueueStatus(
        messageId: UUID,
        queueStatus: String,
        reviewedAt: Instant,
    ) {
        prepareStatement(
            """
            UPDATE message_review_queue
            SET status = ?,
                reviewed_at = ?,
                updated_at = now()
            WHERE message_id = ?
              AND status = 'open'
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, queueStatus)
            statement.setTimestamp(2, Timestamp.from(reviewedAt))
            statement.setObject(3, messageId)
            statement.executeUpdate()
        }
    }

    private fun Connection.touchConversation(conversationId: UUID) {
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

    private fun ResultSet.toMessageReviewRecord(): MessageReviewRecord =
        MessageReviewRecord(
            queueId = getObject("id", UUID::class.java),
            messageId = getObject("message_id", UUID::class.java),
            conversationId = getObject("conversation_id", UUID::class.java),
            senderUserId = getObject("sender_user_id", UUID::class.java),
            body = getString("body"),
            safetyReason = getString("reason"),
            queueStatus = getString("status"),
            createdAt = getTimestamp("created_at").toInstant(),
            updatedAt = getTimestamp("updated_at").toInstant(),
        )

    private data class QueuedMessage(
        val conversationId: UUID,
        val senderUserId: UUID,
    )
}
