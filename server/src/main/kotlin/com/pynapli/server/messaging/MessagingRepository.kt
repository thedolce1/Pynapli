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
        clientIdempotencyKey: String? = null,
        messageIdFactory: () -> UUID = UUID::randomUUID,
    ): MessageRecord?

    suspend fun deleteOwnMessage(
        userId: UUID,
        messageId: UUID,
    ): MessageRecord?

    /** Creates a media message ([mediaAssetId] attached, [body] optional caption). */
    suspend fun sendMediaMessage(
        userId: UUID,
        conversationId: UUID,
        mediaAssetId: UUID,
        contentType: String?,
        pixelWidth: Int?,
        pixelHeight: Int?,
        body: String,
        clientIdempotencyKey: String? = null,
        messageIdFactory: () -> UUID = UUID::randomUUID,
    ): MessageRecord?

    /**
     * Records [userId]'s reveal of a media message (idempotent) and returns the message loaded for
     * that viewer (mediaRevealedByViewer = true), or null when the message has no media or the
     * viewer is not an active participant.
     */
    suspend fun recordMediaReveal(
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

    /**
     * Sets the caller's disappearing-messages timer ([expirySeconds] null = off) and records a
     * system message describing the change, in one transaction. Returns the refreshed detail
     * (with the new effective timer) and the system message, or null if the caller is not an
     * active participant.
     */
    suspend fun setConversationExpiry(
        userId: UUID,
        conversationId: UUID,
        expirySeconds: Int?,
        systemMessageBody: String,
        messageIdFactory: () -> UUID = UUID::randomUUID,
    ): SetConversationExpiryResult?
}

data class SetConversationExpiryResult(
    val conversation: ConversationRecord,
    val systemMessage: MessageRecord,
)

data class ConversationRecord(
    val conversationId: UUID,
    val kind: String,
    val origin: String,
    val participants: List<ConversationParticipantRecord>,
    val lastMessage: MessageRecord?,
    val unreadCount: Int = 0,
    val updatedAt: Instant,
    val partnerInvite: PartnerChatInviteRecord? = null,
    // The viewer's own disappearing-messages timer (null = off).
    val viewerExpirySeconds: Int? = null,
    // Strictest non-null timer across active participants — what messages actually expire on.
    val effectiveExpirySeconds: Int? = null,
)

data class ConversationParticipantRecord(
    val userId: UUID,
    val displayName: String,
    val online: Boolean = false,
    val status: String = "active",
    val invitedByUserId: UUID? = null,
    val messageExpirySeconds: Int? = null,
    // Public primary profile-photo asset (resolved to a URL in the DTO mapper); null when none.
    val profilePhotoMediaAssetId: UUID? = null,
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
    val messageType: String = "user",
    // Per-recipient media reveal: a chat photo attached to this message.
    val mediaAssetId: UUID? = null,
    val mediaContentType: String? = null,
    val mediaWidth: Int? = null,
    val mediaHeight: Int? = null,
    // Whether the viewer the record was loaded for has revealed the media (sender counts as revealed).
    val mediaRevealedByViewer: Boolean = false,
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
