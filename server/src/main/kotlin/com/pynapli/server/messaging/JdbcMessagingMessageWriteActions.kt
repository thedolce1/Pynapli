package com.pynapli.server.messaging

import com.pynapli.server.db.inTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.SQLException
import java.util.UUID
import javax.sql.DataSource

internal suspend fun DataSource.sendMessagingTextMessage(
    userId: UUID,
    conversationId: UUID,
    body: String,
    moderationStatus: String,
    bodyFingerprint: String?,
    safetyReason: String?,
    clientIdempotencyKey: String?,
    messageIdFactory: () -> UUID,
): MessageRecord? =
    withContext(Dispatchers.IO) {
        connection.use { connection ->
            connection.autoCommit = false
            try {
                if (!connection.isActiveParticipant(userId, conversationId)) {
                    connection.rollback()
                    return@withContext null
                }

                if (clientIdempotencyKey != null) {
                    val existing = connection.findMessageByIdempotencyKey(userId, conversationId, clientIdempotencyKey)
                    if (existing != null) {
                        connection.rollback()
                        return@withContext existing
                    }
                }

                val message =
                    try {
                        connection.insertMessage(
                            messageId = messageIdFactory(),
                            userId = userId,
                            conversationId = conversationId,
                            body = body,
                            moderationStatus = moderationStatus,
                            bodyFingerprint = bodyFingerprint,
                            safetyReason = safetyReason,
                            clientIdempotencyKey = clientIdempotencyKey,
                        )
                    } catch (error: SQLException) {
                        if (
                            clientIdempotencyKey != null &&
                            error.sqlState == JdbcMessagingRepositoryDefaults.UniqueViolationSqlState
                        ) {
                            connection.rollback()
                            return@withContext this@sendMessagingTextMessage.connection.use { lookup ->
                                lookup.findMessageByIdempotencyKey(userId, conversationId, clientIdempotencyKey)
                            }
                        }
                        throw error
                    }

                if (moderationStatus == MessageModerationStatus.HELD && safetyReason != null) {
                    connection.insertMessageReviewQueue(
                        messageId = message.messageId,
                        senderUserId = userId,
                        conversationId = conversationId,
                        reason = safetyReason,
                    )
                }

                // A visible message ends a match's new-matches rail item for both people, in this
                // transaction; a held one waits for release (JdbcMessageModerationRepository).
                if (moderationStatus == MessageModerationStatus.VISIBLE) connection.consumeMatchInInbox(conversationId)
                connection.touchConversationAt(conversationId, message.createdAt)
                connection.markSenderReadAt(userId, conversationId, message.createdAt)

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

internal suspend fun DataSource.deleteOwnMessagingMessage(
    userId: UUID,
    messageId: UUID,
): MessageRecord? =
    withContext(Dispatchers.IO) {
        connection.use { connection ->
            connection.autoCommit = false
            try {
                val message = connection.deleteOwnVisibleMessage(userId, messageId)

                if (message == null) {
                    connection.rollback()
                    return@withContext null
                }

                connection.touchConversation(message.conversationId)
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

internal suspend fun DataSource.sendMessagingMediaMessage(
    userId: UUID,
    conversationId: UUID,
    mediaAssetId: UUID,
    contentType: String?,
    pixelWidth: Int?,
    pixelHeight: Int?,
    body: String,
    clientIdempotencyKey: String?,
    messageIdFactory: () -> UUID,
): MessageRecord? =
    withContext(Dispatchers.IO) {
        connection.use { connection ->
            if (!connection.isActiveParticipant(userId, conversationId)) return@withContext null
            clientIdempotencyKey?.let { key ->
                connection.findMessageByIdempotencyKey(userId, conversationId, key)?.let { return@withContext it }
            }
            connection.inTransaction {
                val message =
                    insertMediaMessage(
                        messageId = messageIdFactory(),
                        userId = userId,
                        conversationId = conversationId,
                        mediaAssetId = mediaAssetId,
                        contentType = contentType,
                        pixelWidth = pixelWidth,
                        pixelHeight = pixelHeight,
                        body = body,
                        clientIdempotencyKey = clientIdempotencyKey,
                    )
                // Ends a match's new-matches rail item for both people, with the message.
                consumeMatchInInbox(conversationId)
                touchConversationAt(conversationId, message.createdAt)
                message
            }
        }
    }

internal suspend fun DataSource.recordMessagingMediaReveal(
    userId: UUID,
    messageId: UUID,
): MessageRecord? =
    withContext(Dispatchers.IO) {
        connection.use { connection ->
            val message = connection.loadMessageForViewer(userId, messageId) ?: return@withContext null
            if (message.mediaAssetId == null) return@withContext null
            connection.insertMessageMediaReveal(userId, messageId)
            connection.loadMessageForViewer(userId, messageId)
        }
    }

internal suspend fun DataSource.setMessagingConversationExpiry(
    userId: UUID,
    conversationId: UUID,
    expirySeconds: Int?,
    systemMessageBody: String,
    messageIdFactory: () -> UUID,
): SetConversationExpiryResult? =
    withContext(Dispatchers.IO) {
        connection.use { connection ->
            connection.inTransaction {
                val updated = connection.setParticipantExpiry(userId, conversationId, expirySeconds)
                if (updated == 0) {
                    connection.rollback()
                    return@withContext null
                }
                val systemMessage = connection.insertSystemMessage(messageIdFactory(), userId, conversationId, systemMessageBody)
                // A system notice is a visible message too: it ends a match's rail item for both.
                connection.consumeMatchInInbox(conversationId)
                connection.touchConversation(conversationId)
                val conversation =
                    connection.loadConversation(userId, conversationId) ?: run {
                        connection.rollback()
                        return@withContext null
                    }
                connection.commit()
                SetConversationExpiryResult(conversation = conversation, systemMessage = systemMessage)
            }
        }
    }
