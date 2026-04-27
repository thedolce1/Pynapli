package com.pynapli.server.messaging

import java.time.Instant
import java.util.UUID

interface MessagingRepository {
    suspend fun listConversations(userId: UUID, limit: Int): List<ConversationRecord>

    suspend fun getOrCreateDirectConversation(
        viewerUserId: UUID,
        targetUserId: UUID,
        origin: String,
        conversationIdFactory: () -> UUID = UUID::randomUUID
    ): ConversationRecord?

    suspend fun getConversationDetail(
        userId: UUID,
        conversationId: UUID,
        limit: Int,
        before: MessagePageCursor? = null
    ): ConversationDetailRecord?

    suspend fun markConversationRead(userId: UUID, conversationId: UUID): ConversationRecord?

    suspend fun sendMessage(
        userId: UUID,
        conversationId: UUID,
        body: String,
        messageIdFactory: () -> UUID = UUID::randomUUID
    ): MessageRecord?
}

data class ConversationRecord(
    val conversationId: UUID,
    val kind: String,
    val origin: String,
    val participants: List<ConversationParticipantRecord>,
    val lastMessage: MessageRecord?,
    val unreadCount: Int = 0,
    val updatedAt: Instant
)

data class ConversationParticipantRecord(
    val userId: UUID,
    val displayName: String,
    val online: Boolean = false
)

data class ConversationDetailRecord(
    val conversation: ConversationRecord,
    val messages: List<MessageRecord>,
    val nextCursor: MessagePageCursor? = null
)

data class MessagePageRecord(
    val messages: List<MessageRecord>,
    val nextCursor: MessagePageCursor?
)

data class MessagePageCursor(
    val createdAt: Instant,
    val messageId: UUID
)

data class MessageRecord(
    val messageId: UUID,
    val conversationId: UUID,
    val senderUserId: UUID,
    val body: String,
    val createdAt: Instant,
    val status: String,
    val deliveryStatus: String
)
