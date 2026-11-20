package com.pynapli.app.conversation

import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.network.MediaByteUploader
import com.pynapli.shared.network.PynapliApiClient
import com.pynapli.shared.network.dto.ConversationDetailDto
import com.pynapli.shared.network.dto.ConversationDto
import com.pynapli.shared.network.dto.ConversationPartnerInviteResponseDto
import com.pynapli.shared.network.dto.CreateDirectConversationRequestDto
import com.pynapli.shared.network.dto.CreateMessageMediaUploadRequestDto
import com.pynapli.shared.network.dto.MessageDto
import com.pynapli.shared.network.dto.SendMediaMessageRequestDto
import com.pynapli.shared.network.dto.SendMessageRequestDto
import com.pynapli.shared.network.dto.SendTypingIndicatorRequestDto
import com.pynapli.shared.network.dto.SetConversationExpiryRequestDto

class ApiConversationRepository(
    private val apiClient: PynapliApiClient,
    private val mediaByteUploader: MediaByteUploader,
) : ConversationRepository {
    override suspend fun createDirectConversation(
        targetUserId: String,
        origin: String,
    ): AppResult<ConversationDetailDto> =
        apiClient.createDirectConversation(
            CreateDirectConversationRequestDto(
                targetUserId = targetUserId,
                origin = origin,
            ),
        )

    override suspend fun getConversationDetail(
        conversationId: String,
        before: String?,
    ): AppResult<ConversationDetailDto> = apiClient.getConversationDetail(conversationId, before = before)

    override suspend fun markConversationRead(conversationId: String): AppResult<ConversationDto> = apiClient.markConversationRead(conversationId)

    override suspend fun sendMessage(
        conversationId: String,
        body: String,
        clientIdempotencyKey: String?,
    ): AppResult<MessageDto> =
        apiClient.sendMessage(
            conversationId,
            SendMessageRequestDto(body = body, clientIdempotencyKey = clientIdempotencyKey),
        )

    override suspend fun sendTypingIndicator(
        conversationId: String,
        isTyping: Boolean,
    ): AppResult<Unit> = apiClient.sendTypingIndicator(conversationId, SendTypingIndicatorRequestDto(isTyping = isTyping))

    override suspend fun setConversationExpiry(
        conversationId: String,
        expirySeconds: Int?,
    ): AppResult<ConversationDto> = apiClient.setConversationExpiry(conversationId, SetConversationExpiryRequestDto(expirySeconds = expirySeconds))

    override suspend fun sendPhoto(
        conversationId: String,
        contentType: String,
        byteSize: Long,
        pixelWidth: Int?,
        pixelHeight: Int?,
        bytes: ByteArray,
    ): AppResult<MessageDto> {
        val slot =
            when (
                val result =
                    apiClient.createMessageMediaUploadSlot(
                        CreateMessageMediaUploadRequestDto(
                            contentType = contentType,
                            byteSize = byteSize,
                            pixelWidth = pixelWidth,
                            pixelHeight = pixelHeight,
                        ),
                    )
            ) {
                is AppResult.Success -> result.value
                is AppResult.Failure -> return result
            }

        when (val result = mediaByteUploader.upload(slot.uploadUrl, slot.uploadHeaders, bytes)) {
            is AppResult.Success -> Unit
            is AppResult.Failure -> return result
        }

        return apiClient.sendMediaMessage(
            conversationId,
            SendMediaMessageRequestDto(mediaAssetId = slot.mediaAssetId),
        )
    }

    override suspend fun revealMedia(messageId: String): AppResult<MessageDto> = apiClient.revealMessageMedia(messageId)

    override suspend fun deleteMessage(messageId: String): AppResult<MessageDto> = apiClient.deleteMessage(messageId)

    override suspend fun inviteLinkedPartner(conversationId: String): AppResult<ConversationDto> = apiClient.inviteLinkedPartnerToConversation(conversationId)

    override suspend fun acceptPartnerInvite(
        conversationId: String,
        inviteId: String?,
    ): AppResult<ConversationDetailDto> = apiClient.acceptConversationPartnerInvite(conversationId, inviteId)

    override suspend fun declinePartnerInvite(
        conversationId: String,
        inviteId: String?,
    ): AppResult<ConversationPartnerInviteResponseDto> = apiClient.declineConversationPartnerInvite(conversationId, inviteId)

    override suspend fun leaveConversation(conversationId: String): AppResult<ConversationPartnerInviteResponseDto> = apiClient.leaveConversation(conversationId)

    override suspend fun metInPersonOffers(conversationId: String): AppResult<List<com.pynapli.shared.network.dto.MetInPersonOfferDto>> =
        when (val result = apiClient.getMetInPersonOffers(conversationId)) {
            is AppResult.Success -> AppResult.Success(result.value.offers)
            is AppResult.Failure -> result
        }

    override suspend fun confirmMetInPerson(
        conversationId: String,
        subjectUserId: String,
        includePartner: Boolean,
    ): AppResult<com.pynapli.shared.network.dto.MetInPersonOfferDto> =
        apiClient.confirmMetInPerson(
            conversationId,
            com.pynapli.shared.network.dto.ConfirmMetInPersonRequestDto(subjectUserId = subjectUserId, includePartner = includePartner),
        )

    override suspend fun withdrawMetInPerson(
        conversationId: String,
        subjectUserId: String,
    ): AppResult<com.pynapli.shared.network.dto.MetInPersonOfferDto> =
        apiClient.withdrawMetInPerson(
            conversationId,
            com.pynapli.shared.network.dto.WithdrawMetInPersonRequestDto(subjectUserId = subjectUserId),
        )

    override suspend fun reportMessage(
        reportedUserId: String,
        messageId: String,
        category: String,
    ): AppResult<Unit> =
        apiClient
            .submitReport(
                com.pynapli.shared.network.dto.CreateReportRequestDto(
                    reportedUserId = reportedUserId,
                    targetType = com.pynapli.shared.network.dto.ReportTargetType.MESSAGE,
                    targetId = messageId,
                    category = category,
                ),
            ).let { result ->
                when (result) {
                    is AppResult.Success -> AppResult.Success(Unit)
                    is AppResult.Failure -> result
                }
            }
}
