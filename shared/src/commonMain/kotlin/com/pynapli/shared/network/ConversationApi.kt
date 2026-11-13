package com.pynapli.shared.network

import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.network.dto.ConversationDetailDto
import com.pynapli.shared.network.dto.ConversationDto
import com.pynapli.shared.network.dto.ConversationListResponseDto
import com.pynapli.shared.network.dto.ConversationPartnerInviteResponseDto
import com.pynapli.shared.network.dto.CreateDirectConversationRequestDto
import com.pynapli.shared.network.dto.CreateMessageMediaUploadRequestDto
import com.pynapli.shared.network.dto.MessageDto
import com.pynapli.shared.network.dto.MessageMediaUploadSlotDto
import com.pynapli.shared.network.dto.SendMediaMessageRequestDto
import com.pynapli.shared.network.dto.SendMessageRequestDto
import com.pynapli.shared.network.dto.SendTypingIndicatorRequestDto
import com.pynapli.shared.network.dto.SetConversationExpiryRequestDto

/**
 * Conversation / messaging slice of the API surface, split out of [PynapliApiClient]
 * (which composes the per-feature interfaces). Signatures are unchanged.
 */
interface ConversationApi {
    suspend fun createDirectConversation(request: CreateDirectConversationRequestDto): AppResult<ConversationDetailDto>

    suspend fun listConversations(): AppResult<ConversationListResponseDto>

    /** The inbox page after [cursor] (a previous page's `nextCursor`). */
    suspend fun listConversationsAfter(cursor: String): AppResult<ConversationListResponseDto> =
        AppResult.Failure(
            com.pynapli.shared.core.result
                .AppError(code = "NOT_SUPPORTED", message = "Not available."),
        )

    suspend fun getConversationDetail(
        conversationId: String,
        before: String? = null,
        limit: Int? = null,
    ): AppResult<ConversationDetailDto>

    suspend fun markConversationRead(conversationId: String): AppResult<ConversationDto>

    suspend fun sendMessage(
        conversationId: String,
        request: SendMessageRequestDto,
    ): AppResult<MessageDto>

    suspend fun sendTypingIndicator(
        conversationId: String,
        request: SendTypingIndicatorRequestDto,
    ): AppResult<Unit>

    suspend fun setConversationExpiry(
        conversationId: String,
        request: SetConversationExpiryRequestDto,
    ): AppResult<ConversationDto>

    suspend fun createMessageMediaUploadSlot(request: CreateMessageMediaUploadRequestDto): AppResult<MessageMediaUploadSlotDto>

    suspend fun sendMediaMessage(
        conversationId: String,
        request: SendMediaMessageRequestDto,
    ): AppResult<MessageDto>

    suspend fun revealMessageMedia(messageId: String): AppResult<MessageDto>

    suspend fun deleteMessage(messageId: String): AppResult<MessageDto>

    suspend fun inviteLinkedPartnerToConversation(conversationId: String): AppResult<ConversationDto>

    suspend fun acceptConversationPartnerInvite(conversationId: String): AppResult<ConversationDetailDto>

    suspend fun declineConversationPartnerInvite(conversationId: String): AppResult<ConversationPartnerInviteResponseDto>

    suspend fun leaveConversation(conversationId: String): AppResult<ConversationPartnerInviteResponseDto>
}
