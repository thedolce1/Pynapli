package com.pynapli.app.conversation

import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.network.dto.ConversationDetailDto
import com.pynapli.shared.network.dto.ConversationDto
import com.pynapli.shared.network.dto.ConversationParticipantDto
import com.pynapli.shared.network.dto.ConversationParticipantStatus
import com.pynapli.shared.network.dto.ConversationPartnerInviteResponseDto
import com.pynapli.shared.network.dto.MessageDto

class SampleConversationRepository : ConversationRepository {
    override suspend fun createDirectConversation(
        targetUserId: String,
        origin: String,
    ): AppResult<ConversationDetailDto> =
        AppResult.Success(
            ConversationDetailDto(
                conversation =
                    ConversationDto(
                        conversationId = "sample-conversation",
                        kind = "direct",
                        origin = origin,
                        participants =
                            listOf(
                                ConversationParticipantDto("viewer", "You"),
                                ConversationParticipantDto(targetUserId, "Maya"),
                            ),
                        unreadCount = 0,
                        updatedAt = "2026-04-24T18:00:00Z",
                    ),
                messages = emptyList(),
            ),
        )

    override suspend fun getConversationDetail(
        conversationId: String,
        before: String?,
    ): AppResult<ConversationDetailDto> =
        AppResult.Success(
            ConversationDetailDto(
                conversation =
                    ConversationDto(
                        conversationId = conversationId,
                        kind = "direct",
                        origin = "grid",
                        participants =
                            listOf(
                                ConversationParticipantDto("viewer", "You"),
                                ConversationParticipantDto("target", "Maya"),
                            ),
                        lastMessage =
                            MessageDto(
                                messageId = "sample-message-1",
                                conversationId = conversationId,
                                senderUserId = "target",
                                body = "See you tonight",
                                createdAt = "2026-04-24T18:00:00Z",
                                status = "visible",
                                deliveryStatus = "received",
                            ),
                        unreadCount = 1,
                        updatedAt = "2026-04-24T18:00:00Z",
                    ),
                messages =
                    listOf(
                        MessageDto(
                            messageId = "sample-message-1",
                            conversationId = conversationId,
                            senderUserId = "target",
                            body = "See you tonight",
                            createdAt = "2026-04-24T18:00:00Z",
                            status = "visible",
                            deliveryStatus = "received",
                        ),
                    ),
                nextCursor = if (before == null) "sample-older-page" else null,
            ),
        )

    override suspend fun markConversationRead(conversationId: String): AppResult<ConversationDto> =
        AppResult.Success(
            ConversationDto(
                conversationId = conversationId,
                kind = "direct",
                origin = "grid",
                participants =
                    listOf(
                        ConversationParticipantDto("viewer", "You"),
                        ConversationParticipantDto("target", "Maya"),
                    ),
                unreadCount = 0,
                updatedAt = "2026-04-24T18:00:00Z",
            ),
        )

    override suspend fun sendMessage(
        conversationId: String,
        body: String,
        clientIdempotencyKey: String?,
    ): AppResult<MessageDto> =
        AppResult.Success(
            MessageDto(
                messageId = "sample-message",
                conversationId = conversationId,
                senderUserId = "viewer",
                body = body,
                createdAt = "2026-04-24T18:00:00Z",
                status = "visible",
                deliveryStatus = "sent",
            ),
        )

    override suspend fun sendTypingIndicator(
        conversationId: String,
        isTyping: Boolean,
    ): AppResult<Unit> = AppResult.Success(Unit)

    override suspend fun deleteMessage(messageId: String): AppResult<MessageDto> =
        AppResult.Success(
            MessageDto(
                messageId = messageId,
                conversationId = "sample-conversation",
                senderUserId = "viewer",
                body = "",
                createdAt = "2026-04-24T18:00:00Z",
                status = "visible",
                deliveryStatus = "deleted",
                deletedAt = "2026-04-24T18:01:00Z",
            ),
        )

    override suspend fun inviteLinkedPartner(conversationId: String): AppResult<ConversationDto> =
        AppResult.Success(
            ConversationDto(
                conversationId = conversationId,
                kind = "group",
                origin = "grid",
                participants =
                    listOf(
                        ConversationParticipantDto("viewer", "You"),
                        ConversationParticipantDto("target", "Maya"),
                        ConversationParticipantDto(
                            userId = "partner",
                            displayName = "Partner",
                            status = ConversationParticipantStatus.INVITED,
                            invitedByUserId = "viewer",
                        ),
                    ),
                unreadCount = 0,
                updatedAt = "2026-04-24T18:00:00Z",
            ),
        )

    override suspend fun acceptPartnerInvite(conversationId: String): AppResult<ConversationDetailDto> = getConversationDetail(conversationId, before = null)

    override suspend fun declinePartnerInvite(conversationId: String): AppResult<ConversationPartnerInviteResponseDto> = AppResult.Success(ConversationPartnerInviteResponseDto(status = ConversationParticipantStatus.DECLINED))

    override suspend fun leaveConversation(conversationId: String): AppResult<ConversationPartnerInviteResponseDto> = AppResult.Success(ConversationPartnerInviteResponseDto(status = ConversationParticipantStatus.LEFT))
}
