package com.pynapli.app.conversation

import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.network.dto.ConversationDetailDto
import com.pynapli.shared.network.dto.ConversationDto
import com.pynapli.shared.network.dto.ConversationPartnerInviteResponseDto
import com.pynapli.shared.network.dto.MessageDto

interface ConversationRepository {
    suspend fun createDirectConversation(
        targetUserId: String,
        origin: String = "grid",
    ): AppResult<ConversationDetailDto>

    suspend fun getConversationDetail(
        conversationId: String,
        before: String? = null,
    ): AppResult<ConversationDetailDto>

    suspend fun markConversationRead(conversationId: String): AppResult<ConversationDto>

    suspend fun sendMessage(
        conversationId: String,
        body: String,
        clientIdempotencyKey: String? = null,
    ): AppResult<MessageDto>

    suspend fun sendTypingIndicator(
        conversationId: String,
        isTyping: Boolean,
    ): AppResult<Unit>

    suspend fun setConversationExpiry(
        conversationId: String,
        expirySeconds: Int?,
    ): AppResult<ConversationDto>

    /** Uploads a chat photo and sends it as a media message in one call. */
    suspend fun sendPhoto(
        conversationId: String,
        contentType: String,
        byteSize: Long,
        pixelWidth: Int?,
        pixelHeight: Int?,
        bytes: ByteArray,
    ): AppResult<MessageDto>

    suspend fun revealMedia(messageId: String): AppResult<MessageDto>

    suspend fun deleteMessage(messageId: String): AppResult<MessageDto>

    suspend fun inviteLinkedPartner(conversationId: String): AppResult<ConversationDto>

    suspend fun acceptPartnerInvite(conversationId: String): AppResult<ConversationDetailDto>

    suspend fun declinePartnerInvite(conversationId: String): AppResult<ConversationPartnerInviteResponseDto>

    suspend fun leaveConversation(conversationId: String): AppResult<ConversationPartnerInviteResponseDto>
}
