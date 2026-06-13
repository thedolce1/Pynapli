package com.pynapli.shared.network

import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.network.dto.ConversationDetailDto
import com.pynapli.shared.network.dto.ConversationDto
import com.pynapli.shared.network.dto.ConversationListResponseDto
import com.pynapli.shared.network.dto.ConversationPartnerInviteResponseDto
import com.pynapli.shared.network.dto.CreateDirectConversationRequestDto
import com.pynapli.shared.network.dto.MessageDto
import com.pynapli.shared.network.dto.SendMessageRequestDto
import com.pynapli.shared.network.dto.SendTypingIndicatorRequestDto
import com.pynapli.shared.network.dto.SetConversationExpiryRequestDto
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType

/** Ktor implementation of [ConversationApi], composed into `KtorPynapliApiClient` via delegation. */
internal class KtorConversationApi(
    private val support: KtorApiSupport,
) : ConversationApi {
    override suspend fun createDirectConversation(
        request: CreateDirectConversationRequestDto,
    ): AppResult<ConversationDetailDto> =
        support.authenticated { token ->
            support.httpClient
                .post(support.endpoint("/v1/messages/threads")) {
                    bearerAuth(token)
                    contentType(ContentType.Application.Json)
                    setBody(request)
                }.toAppResult(support.json)
        }

    override suspend fun listConversations(): AppResult<ConversationListResponseDto> =
        support.authenticated { token ->
            support.httpClient
                .get(support.endpoint("/v1/messages/threads")) {
                    bearerAuth(token)
                }.toAppResult(support.json)
        }

    override suspend fun getConversationDetail(
        conversationId: String,
        before: String?,
        limit: Int?,
    ): AppResult<ConversationDetailDto> =
        support.authenticated { token ->
            support.httpClient
                .get(support.endpoint("/v1/messages/threads/$conversationId")) {
                    bearerAuth(token)
                    before?.let { parameter("before", it) }
                    limit?.let { parameter("limit", it) }
                }.toAppResult(support.json)
        }

    override suspend fun markConversationRead(conversationId: String): AppResult<ConversationDto> =
        support.authenticated { token ->
            support.httpClient
                .post(support.endpoint("/v1/messages/threads/$conversationId/read")) {
                    bearerAuth(token)
                }.toAppResult(support.json)
        }

    override suspend fun sendMessage(
        conversationId: String,
        request: SendMessageRequestDto,
    ): AppResult<MessageDto> =
        support.authenticated { token ->
            support.httpClient
                .post(support.endpoint("/v1/messages/threads/$conversationId/messages")) {
                    bearerAuth(token)
                    contentType(ContentType.Application.Json)
                    setBody(request)
                }.toAppResult(support.json)
        }

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
    ): AppResult<ConversationDto> =
        support.authenticated { token ->
            support.httpClient
                .put(support.endpoint("/v1/messages/threads/$conversationId/expiry")) {
                    bearerAuth(token)
                    contentType(ContentType.Application.Json)
                    setBody(request)
                }.toAppResult(support.json)
        }

    override suspend fun deleteMessage(messageId: String): AppResult<MessageDto> =
        support.authenticated { token ->
            support.httpClient
                .post(support.endpoint("/v1/messages/$messageId/delete")) {
                    bearerAuth(token)
                }.toAppResult(support.json)
        }

    override suspend fun inviteLinkedPartnerToConversation(conversationId: String): AppResult<ConversationDto> =
        support.authenticated { token ->
            support.httpClient
                .post(support.endpoint("/v1/messages/threads/$conversationId/partner-invite")) {
                    bearerAuth(token)
                }.toAppResult(support.json)
        }

    override suspend fun acceptConversationPartnerInvite(conversationId: String): AppResult<ConversationDetailDto> =
        support.authenticated { token ->
            support.httpClient
                .post(support.endpoint("/v1/messages/threads/$conversationId/partner-invite/accept")) {
                    bearerAuth(token)
                }.toAppResult(support.json)
        }

    override suspend fun declineConversationPartnerInvite(
        conversationId: String,
    ): AppResult<ConversationPartnerInviteResponseDto> =
        support.authenticated { token ->
            support.httpClient
                .post(support.endpoint("/v1/messages/threads/$conversationId/partner-invite/decline")) {
                    bearerAuth(token)
                }.toAppResult(support.json)
        }

    override suspend fun leaveConversation(conversationId: String): AppResult<ConversationPartnerInviteResponseDto> =
        support.authenticated { token ->
            support.httpClient
                .post(support.endpoint("/v1/messages/threads/$conversationId/leave")) {
                    bearerAuth(token)
                }.toAppResult(support.json)
        }
}
