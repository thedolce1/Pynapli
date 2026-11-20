package com.pynapli.app.conversation

import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.network.dto.ConversationDetailDto
import com.pynapli.shared.network.dto.ConversationDto
import com.pynapli.shared.network.dto.ConversationPartnerInviteResponseDto
import com.pynapli.shared.network.dto.MessageDto
import com.pynapli.shared.network.dto.MetInPersonOfferDto

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

    suspend fun acceptPartnerInvite(
        conversationId: String,
        inviteId: String? = null,
    ): AppResult<ConversationDetailDto>

    suspend fun declinePartnerInvite(
        conversationId: String,
        inviteId: String? = null,
    ): AppResult<ConversationPartnerInviteResponseDto>

    suspend fun leaveConversation(conversationId: String): AppResult<ConversationPartnerInviteResponseDto>

    /** Reports one message; the server keeps its text for moderators. */
    suspend fun reportMessage(
        reportedUserId: String,
        messageId: String,
        category: String,
    ): AppResult<Unit> =
        AppResult.Failure(
            com.pynapli.shared.core.result
                .AppError(code = "NOT_SUPPORTED", message = "Not available."),
        )

    /** "We've met in person": the server answers with the viewer's own state for that person. */
    suspend fun confirmMetInPerson(
        conversationId: String,
        subjectUserId: String,
        includePartner: Boolean,
    ): AppResult<MetInPersonOfferDto> =
        AppResult.Failure(
            com.pynapli.shared.core.result
                .AppError(code = "NOT_SUPPORTED", message = "Not available."),
        )

    suspend fun withdrawMetInPerson(
        conversationId: String,
        subjectUserId: String,
    ): AppResult<MetInPersonOfferDto> =
        AppResult.Failure(
            com.pynapli.shared.core.result
                .AppError(code = "NOT_SUPPORTED", message = "Not available."),
        )
}
