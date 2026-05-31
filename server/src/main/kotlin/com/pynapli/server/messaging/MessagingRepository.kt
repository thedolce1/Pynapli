package com.pynapli.server.messaging

import java.time.Instant
import java.util.UUID

interface MessagingRepository {
    suspend fun listConversations(
        userId: UUID,
        limit: Int,
    ): List<ConversationRecord>

    suspend fun getOrCreateDirectConversation(
        viewerUserId: UUID,
        targetUserId: UUID,
        origin: String,
        conversationIdFactory: () -> UUID = UUID::randomUUID,
    ): ConversationRecord?

    suspend fun getConversationDetail(
        userId: UUID,
        conversationId: UUID,
        limit: Int,
        before: MessagePageCursor? = null,
    ): ConversationDetailRecord?

    suspend fun markConversationRead(
        userId: UUID,
        conversationId: UUID,
    ): ConversationRecord?

    suspend fun sendMessage(
        userId: UUID,
        conversationId: UUID,
        body: String,
        moderationStatus: String = "visible",
        bodyFingerprint: String? = null,
        safetyReason: String? = null,
        messageIdFactory: () -> UUID = UUID::randomUUID,
    ): MessageRecord?

    suspend fun deleteOwnMessage(
        userId: UUID,
        messageId: UUID,
    ): MessageRecord?

    suspend fun hasSentMessageInConversation(
        userId: UUID,
        conversationId: UUID,
    ): Boolean

    suspend fun countRecentMessagesByFingerprint(
        userId: UUID,
        bodyFingerprint: String,
        since: Instant,
    ): Int

    suspend fun getActiveLinkedPartnerUserId(userId: UUID): UUID?

    suspend fun inviteLinkedPartner(
        inviterUserId: UUID,
        conversationId: UUID,
        partnerUserId: UUID,
    ): PartnerInviteResult

    suspend fun acceptPartnerInvite(
        userId: UUID,
        conversationId: UUID,
    ): ConversationDetailRecord?

    suspend fun declinePartnerInvite(
        userId: UUID,
        conversationId: UUID,
    ): Boolean

    suspend fun leaveConversation(
        userId: UUID,
        conversationId: UUID,
    ): Boolean
}

data class ConversationRecord(
    val conversationId: UUID,
    val kind: String,
    val origin: String,
    val participants: List<ConversationParticipantRecord>,
    val lastMessage: MessageRecord?,
    val unreadCount: Int = 0,
    val updatedAt: Instant,
    val partnerInvite: PartnerChatInviteRecord? = null,
)

data class ConversationParticipantRecord(
    val userId: UUID,
    val displayName: String,
    val online: Boolean = false,
    val status: String = "active",
    val invitedByUserId: UUID? = null,
)

data class ConversationDetailRecord(
    val conversation: ConversationRecord,
    val messages: List<MessageRecord>,
    val nextCursor: MessagePageCursor? = null,
)

data class MessagePageRecord(
    val messages: List<MessageRecord>,
    val nextCursor: MessagePageCursor?,
)

data class MessagePageCursor(
    val createdAt: Instant,
    val messageId: UUID,
)

data class MessageRecord(
    val messageId: UUID,
    val conversationId: UUID,
    val senderUserId: UUID,
    val body: String,
    val createdAt: Instant,
    val status: String,
    val deliveryStatus: String,
    val deletedAt: Instant? = null,
)

data class PartnerChatInviteRecord(
    val inviteId: UUID,
    val threadId: UUID,
    val inviterUserId: UUID,
    val invitedPartnerUserId: UUID,
    val thirdPartyStatus: String,
    val partnerStatus: String,
    val createdAt: Instant,
)

sealed class PartnerInviteResult {
    data class Invited(
        val conversation: ConversationRecord,
    ) : PartnerInviteResult()

    data object ConversationNotFound : PartnerInviteResult()

    data object AlreadyInConversation : PartnerInviteResult()

    data object AlreadyInvited : PartnerInviteResult()

    data object CooldownActive : PartnerInviteResult()
}
