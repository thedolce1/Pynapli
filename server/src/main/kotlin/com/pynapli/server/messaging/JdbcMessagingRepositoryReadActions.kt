package com.pynapli.server.messaging

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

internal suspend fun DataSource.listMessagingConversations(
    userId: UUID,
    limit: Int,
    after: ConversationPageCursor? = null,
): List<ConversationRecord> =
    withContext(Dispatchers.IO) {
        connection.use { connection ->
            connection.listConversationIds(userId, limit, after).mapNotNull { conversationId ->
                connection.loadConversation(userId, conversationId)
            }
        }
    }

internal suspend fun DataSource.getMessagingConversationDetail(
    userId: UUID,
    conversationId: UUID,
    limit: Int,
    before: MessagePageCursor?,
): ConversationDetailRecord? =
    withContext(Dispatchers.IO) {
        connection.use { connection ->
            val conversation = connection.loadConversation(userId, conversationId) ?: return@withContext null
            val page = connection.loadMessages(userId, conversationId, limit, before, conversation.effectiveExpirySeconds)
            ConversationDetailRecord(
                conversation = conversation,
                messages = page.messages,
                nextCursor = page.nextCursor,
            )
        }
    }

internal suspend fun DataSource.markMessagingConversationRead(
    userId: UUID,
    conversationId: UUID,
): ConversationRecord? =
    withContext(Dispatchers.IO) {
        connection.use { connection ->
            val updatedRows = connection.markRead(userId, conversationId)
            if (updatedRows == 0) return@withContext null
            connection.loadConversation(userId, conversationId)
        }
    }

internal suspend fun DataSource.countRecentMessagingMessagesByFingerprint(
    userId: UUID,
    bodyFingerprint: String,
    since: Instant,
): Int =
    withContext(Dispatchers.IO) {
        connection.use { connection ->
            connection.countRecentMessagesByFingerprint(userId, bodyFingerprint, since)
        }
    }

internal suspend fun DataSource.hasSentMessagingMessageInConversation(
    userId: UUID,
    conversationId: UUID,
): Boolean =
    withContext(Dispatchers.IO) {
        connection.use { connection ->
            connection.hasSentMessageInConversation(userId, conversationId)
        }
    }

internal suspend fun DataSource.getActiveLinkedPartnerUserId(userId: UUID): UUID? =
    withContext(Dispatchers.IO) {
        connection.use { connection ->
            connection.loadActiveLinkedPartnerUserId(userId)
        }
    }
