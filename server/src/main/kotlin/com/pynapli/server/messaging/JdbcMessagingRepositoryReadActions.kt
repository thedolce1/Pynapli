package com.pynapli.server.messaging

import com.pynapli.server.contactblocks.ContactBlockSql
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

internal suspend fun DataSource.listMessagingConversations(
    userId: UUID,
    limit: Int,
    after: ConversationPageCursor? = null,
    viewerContactToken: String = ContactBlockSql.NO_VIEWER_TOKEN,
): List<ConversationRecord> =
    withContext(Dispatchers.IO) {
        connection.use { connection ->
            connection.listConversationIds(userId, limit, after, viewerContactToken).mapNotNull { conversationId ->
                connection.loadConversation(userId, conversationId, viewerContactToken)
            }
        }
    }

internal suspend fun DataSource.getMessagingConversationDetail(
    userId: UUID,
    conversationId: UUID,
    limit: Int,
    before: MessagePageCursor?,
    viewerContactToken: String = ContactBlockSql.NO_VIEWER_TOKEN,
): ConversationDetailRecord? =
    withContext(Dispatchers.IO) {
        connection.use { connection ->
            val conversation = connection.loadConversation(userId, conversationId, viewerContactToken) ?: return@withContext null
            val page = connection.loadMessages(userId, conversationId, limit, before, conversation.effectiveExpirySeconds, viewerContactToken)
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
    viewerContactToken: String = ContactBlockSql.NO_VIEWER_TOKEN,
): ConversationRecord? =
    withContext(Dispatchers.IO) {
        connection.use { connection ->
            val updatedRows = connection.markRead(userId, conversationId)
            if (updatedRows == 0) return@withContext null
            // Opening a conversation marks it read: that is the viewer acting on a new match.
            connection.acknowledgeMatchInInbox(userId, conversationId)
            connection.loadConversation(userId, conversationId, viewerContactToken)
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

internal suspend fun DataSource.loadMessagingPartnerOffers(
    viewerUserId: UUID,
    conversation: ConversationRecord,
    viewerContactToken: String = ContactBlockSql.NO_VIEWER_TOKEN,
): ConversationPartnerOffersRecord? =
    withContext(Dispatchers.IO) {
        connection.use { connection -> connection.loadPartnerOffers(viewerUserId, conversation, viewerContactToken) }
    }

internal suspend fun DataSource.isOwnPartnerInviteCoolingDown(
    viewerUserId: UUID,
    conversationId: UUID,
): Boolean =
    withContext(Dispatchers.IO) {
        connection.use { connection -> connection.isOwnPartnerInviteCoolingDown(viewerUserId, conversationId) }
    }

internal suspend fun DataSource.getActiveLinkedPartnerUserId(userId: UUID): UUID? =
    withContext(Dispatchers.IO) {
        connection.use { connection ->
            connection.loadActiveLinkedPartnerUserId(userId)
        }
    }
