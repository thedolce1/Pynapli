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
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType

/** Ktor implementation of [ConversationApi], composed into `KtorPynapliApiClient` via delegation. */
internal class KtorConversationApi(
    private val support: KtorApiSupport,
) : ConversationApi {
    override suspend fun createDirectConversation(
        request: CreateDirectConversationRequestDto,
    ): AppResult<ConversationDetailDto> = support.authSend(HttpMethod.Post, "/v1/messages/threads", request)

    override suspend fun listConversations(): AppResult<ConversationListResponseDto> = support.authGet("/v1/messages/threads")

    override suspend fun getConversationDetail(
        conversationId: String,
        before: String?,
        limit: Int?,
    ): AppResult<ConversationDetailDto> =
        support.authGet("/v1/messages/threads/$conversationId") {
            before?.let { parameter("before", it) }
            limit?.let { parameter("limit", it) }
        }

    override suspend fun markConversationRead(conversationId: String): AppResult<ConversationDto> = support.authPost("/v1/messages/threads/$conversationId/read")

    override suspend fun sendMessage(
        conversationId: String,
        request: SendMessageRequestDto,
    ): AppResult<MessageDto> = support.authSend(HttpMethod.Post, "/v1/messages/threads/$conversationId/messages", request)

    override suspend fun sendTypingIndicator(
        conversationId: String,
        request: SendTypingIndicatorRequestDto,
    ): AppResult<Unit> =
        support.authenticated { token ->
            val response =
                support.httpClient.post(support.endpoint("/v1/messages/threads/$conversationId/typing")) {
                    bearerAuth(token)
                    contentType(ContentType.Application.Json)
                    setBody(request)
                }
            if (response.status.value in 200..299) {
                AppResult.Success(Unit)
            } else {
                response.toAppResult<Unit>(support.json)
            }
        }

    override suspend fun setConversationExpiry(
        conversationId: String,
        request: SetConversationExpiryRequestDto,
    ): AppResult<ConversationDto> = support.authSend(HttpMethod.Put, "/v1/messages/threads/$conversationId/expiry", request)

    override suspend fun createMessageMediaUploadSlot(
        request: CreateMessageMediaUploadRequestDto,
    ): AppResult<MessageMediaUploadSlotDto> = support.authSend(HttpMethod.Post, "/v1/messages/media/uploads", request)

    override suspend fun sendMediaMessage(
        conversationId: String,
        request: SendMediaMessageRequestDto,
    ): AppResult<MessageDto> = support.authSend(HttpMethod.Post, "/v1/messages/threads/$conversationId/media", request)

    override suspend fun revealMessageMedia(messageId: String): AppResult<MessageDto> = support.authPost("/v1/messages/$messageId/reveal")

    override suspend fun deleteMessage(messageId: String): AppResult<MessageDto> = support.authPost("/v1/messages/$messageId/delete")

    override suspend fun inviteLinkedPartnerToConversation(conversationId: String): AppResult<ConversationDto> = support.authPost("/v1/messages/threads/$conversationId/partner-invite")

    override suspend fun acceptConversationPartnerInvite(conversationId: String): AppResult<ConversationDetailDto> = support.authPost("/v1/messages/threads/$conversationId/partner-invite/accept")

    override suspend fun declineConversationPartnerInvite(
        conversationId: String,
    ): AppResult<ConversationPartnerInviteResponseDto> = support.authPost("/v1/messages/threads/$conversationId/partner-invite/decline")

    override suspend fun leaveConversation(conversationId: String): AppResult<ConversationPartnerInviteResponseDto> = support.authPost("/v1/messages/threads/$conversationId/leave")
}
