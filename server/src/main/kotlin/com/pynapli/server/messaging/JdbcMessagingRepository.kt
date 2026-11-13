package com.pynapli.server.messaging

import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

class JdbcMessagingRepository(
    private val dataSource: DataSource,
) : MessagingRepository {
    override suspend fun listConversations(
        userId: UUID,
        limit: Int,
        after: ConversationPageCursor?,
    ): List<ConversationRecord> = dataSource.listMessagingConversations(userId, limit, after)

    override suspend fun getOrCreateDirectConversation(
        viewerUserId: UUID,
        targetUserId: UUID,
        origin: String,
        conversationIdFactory: () -> UUID,
    ): ConversationRecord? =
        dataSource.getOrCreateDirectMessagingConversation(
            viewerUserId = viewerUserId,
            targetUserId = targetUserId,
            origin = origin,
            conversationIdFactory = conversationIdFactory,
        )

    override suspend fun getConversationDetail(
        userId: UUID,
        conversationId: UUID,
        limit: Int,
        before: MessagePageCursor?,
    ): ConversationDetailRecord? =
        dataSource.getMessagingConversationDetail(
            userId = userId,
            conversationId = conversationId,
            limit = limit,
            before = before,
        )

    override suspend fun markConversationRead(
        userId: UUID,
        conversationId: UUID,
    ): ConversationRecord? = dataSource.markMessagingConversationRead(userId, conversationId)

    override suspend fun sendMessage(
        userId: UUID,
        conversationId: UUID,
        body: String,
        moderationStatus: String,
        bodyFingerprint: String?,
        safetyReason: String?,
        clientIdempotencyKey: String?,
        messageIdFactory: () -> UUID,
    ): MessageRecord? =
        dataSource.sendMessagingTextMessage(
            userId = userId,
            conversationId = conversationId,
            body = body,
            moderationStatus = moderationStatus,
            bodyFingerprint = bodyFingerprint,
            safetyReason = safetyReason,
            clientIdempotencyKey = clientIdempotencyKey,
            messageIdFactory = messageIdFactory,
        )

    override suspend fun deleteOwnMessage(
        userId: UUID,
        messageId: UUID,
    ): MessageRecord? = dataSource.deleteOwnMessagingMessage(userId, messageId)

    override suspend fun countRecentMessagesByFingerprint(
        userId: UUID,
        bodyFingerprint: String,
        since: Instant,
    ): Int = dataSource.countRecentMessagingMessagesByFingerprint(userId, bodyFingerprint, since)

    override suspend fun hasSentMessageInConversation(
        userId: UUID,
        conversationId: UUID,
    ): Boolean = dataSource.hasSentMessagingMessageInConversation(userId, conversationId)

    override suspend fun getActiveLinkedPartnerUserId(userId: UUID): UUID? = dataSource.getActiveLinkedPartnerUserId(userId)

    override suspend fun inviteLinkedPartner(
        inviterUserId: UUID,
        conversationId: UUID,
        partnerUserId: UUID,
    ): PartnerInviteResult =
        dataSource.inviteLinkedMessagingPartner(
            inviterUserId = inviterUserId,
            conversationId = conversationId,
            partnerUserId = partnerUserId,
        )

    override suspend fun acceptPartnerInvite(
        userId: UUID,
        conversationId: UUID,
    ): ConversationDetailRecord? = dataSource.acceptMessagingPartnerInvite(userId, conversationId)

    override suspend fun declinePartnerInvite(
        userId: UUID,
        conversationId: UUID,
    ): Boolean = dataSource.declineMessagingPartnerInvite(userId, conversationId)

    override suspend fun leaveConversation(
        userId: UUID,
        conversationId: UUID,
    ): Boolean = dataSource.leaveMessagingConversation(userId, conversationId)

    override suspend fun sendMediaMessage(
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
        dataSource.sendMessagingMediaMessage(
            userId = userId,
            conversationId = conversationId,
            mediaAssetId = mediaAssetId,
            contentType = contentType,
            pixelWidth = pixelWidth,
            pixelHeight = pixelHeight,
            body = body,
            clientIdempotencyKey = clientIdempotencyKey,
            messageIdFactory = messageIdFactory,
        )

    override suspend fun recordMediaReveal(
        userId: UUID,
        messageId: UUID,
    ): MessageRecord? = dataSource.recordMessagingMediaReveal(userId, messageId)

    override suspend fun setConversationExpiry(
        userId: UUID,
        conversationId: UUID,
        expirySeconds: Int?,
        systemMessageBody: String,
        messageIdFactory: () -> UUID,
    ): SetConversationExpiryResult? =
        dataSource.setMessagingConversationExpiry(
            userId = userId,
            conversationId = conversationId,
            expirySeconds = expirySeconds,
            systemMessageBody = systemMessageBody,
            messageIdFactory = messageIdFactory,
        )
}
