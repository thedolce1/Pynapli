package com.pynapli.shared.network

import com.pynapli.shared.core.config.PynapliConfig
import com.pynapli.shared.core.result.AppError
import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.network.dto.ActivityFeedResponseDto
import com.pynapli.shared.network.dto.ApiErrorDto
import com.pynapli.shared.network.dto.AuthSessionDto
import com.pynapli.shared.network.dto.CompleteMediaUploadRequestDto
import com.pynapli.shared.network.dto.ConversationDetailDto
import com.pynapli.shared.network.dto.ConversationDto
import com.pynapli.shared.network.dto.ConversationListResponseDto
import com.pynapli.shared.network.dto.CreateDirectConversationRequestDto
import com.pynapli.shared.network.dto.CreateMediaUploadRequestDto
import com.pynapli.shared.network.dto.EmailLoginRequestDto
import com.pynapli.shared.network.dto.EmailSignupRequestDto
import com.pynapli.shared.network.dto.GridQueryRequestDto
import com.pynapli.shared.network.dto.GridQueryResponseDto
import com.pynapli.shared.network.dto.LogoutResponseDto
import com.pynapli.shared.network.dto.MediaAlbumGrantDto
import com.pynapli.shared.network.dto.MediaAlbumGrantsResponseDto
import com.pynapli.shared.network.dto.MediaAlbumItemsResponseDto
import com.pynapli.shared.network.dto.MediaAlbumItemDto
import com.pynapli.shared.network.dto.MediaUploadSlotDto
import com.pynapli.shared.network.dto.MessageDto
import com.pynapli.shared.network.dto.PresenceHeartbeatResponseDto
import com.pynapli.shared.network.dto.PublicProfileDto
import com.pynapli.shared.network.dto.RefreshSessionRequestDto
import com.pynapli.shared.network.dto.RequestAlbumAccessRequestDto
import com.pynapli.shared.network.dto.RequestAlbumAccessResponseDto
import com.pynapli.shared.network.dto.RespondAlbumAccessRequestDto
import com.pynapli.shared.network.dto.RespondAlbumAccessResponseDto
import com.pynapli.shared.network.dto.SendMessageRequestDto
import com.pynapli.shared.network.dto.SendFlirtRequestDto
import com.pynapli.shared.network.dto.SendFlirtResponseDto
import com.pynapli.shared.network.dto.CurrentLocationDto
import com.pynapli.shared.network.dto.UpdateCurrentLocationRequestDto
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

interface PynapliApiClient {
    suspend fun emailSignup(request: EmailSignupRequestDto): AppResult<AuthSessionDto>

    suspend fun emailLogin(request: EmailLoginRequestDto): AppResult<AuthSessionDto>

    suspend fun refreshSession(request: RefreshSessionRequestDto): AppResult<AuthSessionDto>

    suspend fun logout(): AppResult<LogoutResponseDto>

    suspend fun updateCurrentLocation(request: UpdateCurrentLocationRequestDto): AppResult<CurrentLocationDto>

    suspend fun queryGrid(request: GridQueryRequestDto = GridQueryRequestDto()): AppResult<GridQueryResponseDto>

    suspend fun getPublicProfile(userId: String): AppResult<PublicProfileDto>

    suspend fun listActivity(limit: Int? = null): AppResult<ActivityFeedResponseDto>

    suspend fun sendFlirt(request: SendFlirtRequestDto): AppResult<SendFlirtResponseDto>

    suspend fun requestAlbumAccess(request: RequestAlbumAccessRequestDto): AppResult<RequestAlbumAccessResponseDto>

    suspend fun respondToAlbumAccessRequest(
        eventId: String,
        request: RespondAlbumAccessRequestDto
    ): AppResult<RespondAlbumAccessResponseDto>

    suspend fun createDirectConversation(request: CreateDirectConversationRequestDto): AppResult<ConversationDetailDto>

    suspend fun listConversations(): AppResult<ConversationListResponseDto>

    suspend fun getConversationDetail(
        conversationId: String,
        before: String? = null,
        limit: Int? = null
    ): AppResult<ConversationDetailDto>

    suspend fun markConversationRead(conversationId: String): AppResult<ConversationDto>

    suspend fun sendMessage(conversationId: String, request: SendMessageRequestDto): AppResult<MessageDto>

    suspend fun createMediaUpload(request: CreateMediaUploadRequestDto): AppResult<MediaUploadSlotDto>

    suspend fun completeMediaUpload(
        mediaAssetId: String,
        request: CompleteMediaUploadRequestDto
    ): AppResult<MediaAlbumItemDto>

    suspend fun listMediaAlbum(albumKind: String): AppResult<MediaAlbumItemsResponseDto>

    suspend fun listUserPrivateAlbum(ownerUserId: String): AppResult<MediaAlbumItemsResponseDto>

    suspend fun listOwnPrivateAlbumGrants(): AppResult<MediaAlbumGrantsResponseDto>

    suspend fun revokePrivateAlbumAccess(granteeUserId: String): AppResult<MediaAlbumGrantDto>

    fun mediaAssetContentUrl(mediaAssetId: String): String

    suspend fun heartbeatPresence(): AppResult<PresenceHeartbeatResponseDto>
}

class KtorPynapliApiClient(
    private val config: PynapliConfig,
    private val accessTokenProvider: AccessTokenProvider,
    private val httpClient: HttpClient = createPynapliHttpClient(),
    private val json: Json = PynapliJson
) : PynapliApiClient {
    override suspend fun emailSignup(request: EmailSignupRequestDto): AppResult<AuthSessionDto> =
        postJson("/v1/auth/email/signup", request)

    override suspend fun emailLogin(request: EmailLoginRequestDto): AppResult<AuthSessionDto> =
        postJson("/v1/auth/email/login", request)

    override suspend fun refreshSession(request: RefreshSessionRequestDto): AppResult<AuthSessionDto> =
        postJson("/v1/auth/refresh", request)

    override suspend fun logout(): AppResult<LogoutResponseDto> =
        authenticatedPost { token ->
            httpClient.post(config.endpoint("/v1/auth/logout")) {
                bearerAuth(token)
            }.toAppResult(json)
        }

    override suspend fun updateCurrentLocation(request: UpdateCurrentLocationRequestDto): AppResult<CurrentLocationDto> =
        authenticatedPost { token ->
            httpClient.post(config.endpoint("/v1/location/current")) {
                bearerAuth(token)
                contentType(ContentType.Application.Json)
                setBody(request)
            }.toAppResult(json)
        }

    override suspend fun queryGrid(request: GridQueryRequestDto): AppResult<GridQueryResponseDto> =
        authenticatedPost { token ->
            httpClient.post(config.endpoint("/v1/grid/query")) {
                bearerAuth(token)
                contentType(ContentType.Application.Json)
                setBody(request)
            }.toAppResult(json)
        }

    override suspend fun getPublicProfile(userId: String): AppResult<PublicProfileDto> =
        authenticatedPost { token ->
            httpClient.get(config.endpoint("/v1/profiles/$userId")) {
                bearerAuth(token)
            }.toAppResult(json)
        }

    override suspend fun listActivity(limit: Int?): AppResult<ActivityFeedResponseDto> =
        authenticatedPost { token ->
            httpClient.get(config.endpoint("/v1/activity")) {
                bearerAuth(token)
                limit?.let { parameter("limit", it) }
            }.toAppResult(json)
        }

    override suspend fun sendFlirt(request: SendFlirtRequestDto): AppResult<SendFlirtResponseDto> =
        authenticatedPost { token ->
            httpClient.post(config.endpoint("/v1/activity/flirts")) {
                bearerAuth(token)
                contentType(ContentType.Application.Json)
                setBody(request)
            }.toAppResult(json)
        }

    override suspend fun requestAlbumAccess(
        request: RequestAlbumAccessRequestDto
    ): AppResult<RequestAlbumAccessResponseDto> =
        authenticatedPost { token ->
            httpClient.post(config.endpoint("/v1/activity/album-requests")) {
                bearerAuth(token)
                contentType(ContentType.Application.Json)
                setBody(request)
            }.toAppResult(json)
        }

    override suspend fun respondToAlbumAccessRequest(
        eventId: String,
        request: RespondAlbumAccessRequestDto
    ): AppResult<RespondAlbumAccessResponseDto> =
        authenticatedPost { token ->
            httpClient.post(config.endpoint("/v1/activity/album-requests/$eventId/respond")) {
                bearerAuth(token)
                contentType(ContentType.Application.Json)
                setBody(request)
            }.toAppResult(json)
        }

    override suspend fun createDirectConversation(
        request: CreateDirectConversationRequestDto
    ): AppResult<ConversationDetailDto> =
        authenticatedPost { token ->
            httpClient.post(config.endpoint("/v1/messages/threads")) {
                bearerAuth(token)
                contentType(ContentType.Application.Json)
                setBody(request)
            }.toAppResult(json)
        }

    override suspend fun listConversations(): AppResult<ConversationListResponseDto> =
        authenticatedPost { token ->
            httpClient.get(config.endpoint("/v1/messages/threads")) {
                bearerAuth(token)
            }.toAppResult(json)
        }

    override suspend fun getConversationDetail(
        conversationId: String,
        before: String?,
        limit: Int?
    ): AppResult<ConversationDetailDto> =
        authenticatedPost { token ->
            httpClient.get(config.endpoint("/v1/messages/threads/$conversationId")) {
                bearerAuth(token)
                before?.let { parameter("before", it) }
                limit?.let { parameter("limit", it) }
            }.toAppResult(json)
        }

    override suspend fun markConversationRead(conversationId: String): AppResult<ConversationDto> =
        authenticatedPost { token ->
            httpClient.post(config.endpoint("/v1/messages/threads/$conversationId/read")) {
                bearerAuth(token)
            }.toAppResult(json)
        }

    override suspend fun sendMessage(
        conversationId: String,
        request: SendMessageRequestDto
    ): AppResult<MessageDto> =
        authenticatedPost { token ->
            httpClient.post(config.endpoint("/v1/messages/threads/$conversationId/messages")) {
                bearerAuth(token)
                contentType(ContentType.Application.Json)
                setBody(request)
            }.toAppResult(json)
        }

    override suspend fun createMediaUpload(request: CreateMediaUploadRequestDto): AppResult<MediaUploadSlotDto> =
        authenticatedPost { token ->
            httpClient.post(config.endpoint("/v1/media/uploads")) {
                bearerAuth(token)
                contentType(ContentType.Application.Json)
                setBody(request)
            }.toAppResult(json)
        }

    override suspend fun completeMediaUpload(
        mediaAssetId: String,
        request: CompleteMediaUploadRequestDto
    ): AppResult<MediaAlbumItemDto> =
        authenticatedPost { token ->
            httpClient.post(config.endpoint("/v1/media/uploads/$mediaAssetId/complete")) {
                bearerAuth(token)
                contentType(ContentType.Application.Json)
                setBody(request)
            }.toAppResult(json)
        }

    override suspend fun listMediaAlbum(albumKind: String): AppResult<MediaAlbumItemsResponseDto> =
        authenticatedPost { token ->
            httpClient.get(config.endpoint("/v1/media/albums/$albumKind")) {
                bearerAuth(token)
            }.toAppResult(json)
        }

    override suspend fun listUserPrivateAlbum(ownerUserId: String): AppResult<MediaAlbumItemsResponseDto> =
        authenticatedPost { token ->
            httpClient.get(config.endpoint("/v1/media/users/$ownerUserId/albums/private")) {
                bearerAuth(token)
            }.toAppResult(json)
        }

    override suspend fun listOwnPrivateAlbumGrants(): AppResult<MediaAlbumGrantsResponseDto> =
        authenticatedPost { token ->
            httpClient.get(config.endpoint("/v1/media/albums/private/grants")) {
                bearerAuth(token)
            }.toAppResult(json)
        }

    override suspend fun revokePrivateAlbumAccess(granteeUserId: String): AppResult<MediaAlbumGrantDto> =
        authenticatedPost { token ->
            httpClient.post(config.endpoint("/v1/media/albums/private/grants/$granteeUserId/revoke")) {
                bearerAuth(token)
            }.toAppResult(json)
        }

    override fun mediaAssetContentUrl(mediaAssetId: String): String =
        config.endpoint("/v1/media/uploads/local/$mediaAssetId")

    override suspend fun heartbeatPresence(): AppResult<PresenceHeartbeatResponseDto> =
        authenticatedPost { token ->
            httpClient.post(config.endpoint("/v1/presence/heartbeat")) {
                bearerAuth(token)
            }.toAppResult(json)
        }

    private suspend inline fun <reified Request : Any, reified Response : Any> postJson(
        path: String,
        request: Request
    ): AppResult<Response> =
        runCatching {
            httpClient.post(config.endpoint(path)) {
                contentType(ContentType.Application.Json)
                setBody(request)
            }.toAppResult<Response>(json)
        }.getOrElse { error ->
            AppResult.Failure(
                AppError(
                    code = "NETWORK_ERROR",
                    message = error.message ?: "Network request failed."
                )
            )
        }

    private suspend fun <T> authenticatedPost(
        call: suspend (String) -> AppResult<T>
    ): AppResult<T> {
        val token = accessTokenProvider.accessToken()?.trim()
        if (token.isNullOrEmpty()) {
            return AppResult.Failure(
                AppError(
                    code = "UNAUTHENTICATED",
                    message = "Please sign in again."
                )
            )
        }

        return runCatching { call(token) }.getOrElse { error ->
            AppResult.Failure(
                AppError(
                    code = "NETWORK_ERROR",
                    message = error.message ?: "Network request failed."
                )
            )
        }
    }

    private fun PynapliConfig.endpoint(path: String): String =
        apiBaseUrl.trimEnd('/') + path
}

fun createPynapliHttpClient(json: Json = PynapliJson): HttpClient = HttpClient {
    expectSuccess = false
    install(ContentNegotiation) {
        json(json)
    }
}

private suspend inline fun <reified T> HttpResponse.toAppResult(json: Json): AppResult<T> {
    if (status.value in 200..299) {
        return AppResult.Success(body())
    }

    val apiError = runCatching {
        json.decodeFromString<ApiErrorDto>(bodyAsText())
    }.getOrNull()

    return AppResult.Failure(
        AppError(
            code = apiError?.code ?: "HTTP_${status.value}",
            message = apiError?.message ?: "Request failed.",
            recoverable = status.value >= 500 || status == HttpStatusCode.TooManyRequests
        )
    )
}
