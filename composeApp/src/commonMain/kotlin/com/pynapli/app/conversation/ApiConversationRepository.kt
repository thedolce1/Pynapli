package com.pynapli.app.conversation

import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.network.PynapliApiClient
import com.pynapli.shared.network.dto.ConversationDetailDto
import com.pynapli.shared.network.dto.ConversationDto
import com.pynapli.shared.network.dto.ConversationPartnerInviteResponseDto
import com.pynapli.shared.network.dto.CreateDirectConversationRequestDto
import com.pynapli.shared.network.dto.MessageDto
import com.pynapli.shared.network.dto.SendMessageRequestDto
import com.pynapli.shared.network.dto.SendTypingIndicatorRequestDto

class ApiConversationRepository(
    private val apiClient: PynapliApiClient
) : ConversationRepository {
    override suspend fun createDirectConversation(
        targetUserId: String,
        origin: String
    ): AppResult<ConversationDetailDto> =
        apiClient.createDirectConversation(
            CreateDirectConversationRequestDto(
                targetUserId = targetUserId,
                origin = origin
            )
        )

    override suspend fun getConversationDetail(
        conversationId: String,
        before: String?
    ): AppResult<ConversationDetailDto> =
        apiClient.getConversationDetail(conversationId, before = before)

    override suspend fun markConversationRead(conversationId: String): AppResult<ConversationDto> =
        apiClient.markConversationRead(conversationId)

    override suspend fun sendMessage(conversationId: String, body: String): AppResult<MessageDto> =
        apiClient.sendMessage(conversationId, SendMessageRequestDto(body = body))

    override suspend fun sendTypingIndicator(conversationId: String, isTyping: Boolean): AppResult<Unit> =
        apiClient.sendTypingIndicator(conversationId, SendTypingIndicatorRequestDto(isTyping = isTyping))

    override suspend fun deleteMessage(messageId: String): AppResult<MessageDto> =
        apiClient.deleteMessage(messageId)

    override suspend fun inviteLinkedPartner(conversationId: String): AppResult<ConversationDto> =
        apiClient.inviteLinkedPartnerToConversation(conversationId)

    override suspend fun acceptPartnerInvite(conversationId: String): AppResult<ConversationDetailDto> =
        apiClient.acceptConversationPartnerInvite(conversationId)

    override suspend fun declinePartnerInvite(conversationId: String): AppResult<ConversationPartnerInviteResponseDto> =
        apiClient.declineConversationPartnerInvite(conversationId)

    override suspend fun leaveConversation(conversationId: String): AppResult<ConversationPartnerInviteResponseDto> =
        apiClient.leaveConversation(conversationId)
}
