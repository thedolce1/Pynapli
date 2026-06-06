package com.pynapli.shared.network

import com.pynapli.shared.core.config.PynapliConfig
import com.pynapli.shared.core.result.AppError
import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.network.dto.AccountDataExportDto
import com.pynapli.shared.network.dto.ActivityFeedResponseDto
import com.pynapli.shared.network.dto.ActivitySummaryDto
import com.pynapli.shared.network.dto.AgeVerificationRequirementsDto
import com.pynapli.shared.network.dto.AgeVerificationStatusDto
import com.pynapli.shared.network.dto.ApiErrorDto
import com.pynapli.shared.network.dto.AuthSessionDto
import com.pynapli.shared.network.dto.BlockSummaryDto
import com.pynapli.shared.network.dto.BlockUserRequestDto
import com.pynapli.shared.network.dto.BlocksListDto
import com.pynapli.shared.network.dto.BoostActivationDto
import com.pynapli.shared.network.dto.CompleteMediaUploadRequestDto
import com.pynapli.shared.network.dto.ConversationDetailDto
import com.pynapli.shared.network.dto.ConversationDto
import com.pynapli.shared.network.dto.ConversationListResponseDto
import com.pynapli.shared.network.dto.ConversationPartnerInviteResponseDto
import com.pynapli.shared.network.dto.CreateDirectConversationRequestDto
import com.pynapli.shared.network.dto.CreateMediaUploadRequestDto
import com.pynapli.shared.network.dto.CreateProfileLinkInvitationRequestDto
import com.pynapli.shared.network.dto.CreatePsyncSessionRequestDto
import com.pynapli.shared.network.dto.CreateReportRequestDto
import com.pynapli.shared.network.dto.CreateSavedGridFilterRequestDto
import com.pynapli.shared.network.dto.CreateSavedSwipeFilterRequestDto
import com.pynapli.shared.network.dto.CurrentLocationDto
import com.pynapli.shared.network.dto.CurrentPsyncSessionDto
import com.pynapli.shared.network.dto.CurrentTonightSessionDto
import com.pynapli.shared.network.dto.DeleteAccountRequestDto
import com.pynapli.shared.network.dto.DeleteAccountResponseDto
import com.pynapli.shared.network.dto.DismissActivityEventResponseDto
import com.pynapli.shared.network.dto.EmailLoginRequestDto
import com.pynapli.shared.network.dto.EmailSignupRequestDto
import com.pynapli.shared.network.dto.GridQueryRequestDto
import com.pynapli.shared.network.dto.GridQueryResponseDto
import com.pynapli.shared.network.dto.HiddenProfileSummaryDto
import com.pynapli.shared.network.dto.HiddenProfilesListDto
import com.pynapli.shared.network.dto.LogoutResponseDto
import com.pynapli.shared.network.dto.MarkActivitySeenRequestDto
import com.pynapli.shared.network.dto.MarkActivitySeenResponseDto
import com.pynapli.shared.network.dto.MatchesListResponseDto
import com.pynapli.shared.network.dto.MeDto
import com.pynapli.shared.network.dto.MediaAlbumGrantDto
import com.pynapli.shared.network.dto.MediaAlbumGrantsResponseDto
import com.pynapli.shared.network.dto.MediaAlbumItemDto
import com.pynapli.shared.network.dto.MediaAlbumItemsResponseDto
import com.pynapli.shared.network.dto.MediaUploadSlotDto
import com.pynapli.shared.network.dto.MessageDto
import com.pynapli.shared.network.dto.NotificationPreferencesDto
import com.pynapli.shared.network.dto.PresenceHeartbeatResponseDto
import com.pynapli.shared.network.dto.ProfileLinksSummaryDto
import com.pynapli.shared.network.dto.ProposeTonightSessionRequestDto
import com.pynapli.shared.network.dto.PsyncActionResponseDto
import com.pynapli.shared.network.dto.PsyncDeckResponseDto
import com.pynapli.shared.network.dto.PsyncNudgeResponseDto
import com.pynapli.shared.network.dto.PsyncRecapDto
import com.pynapli.shared.network.dto.PsyncSessionDto
import com.pynapli.shared.network.dto.PublicProfileDto
import com.pynapli.shared.network.dto.PushDeviceRegistrationResponseDto
import com.pynapli.shared.network.dto.RefreshSessionRequestDto
import com.pynapli.shared.network.dto.RegisterPushDeviceRequestDto
import com.pynapli.shared.network.dto.ReportDto
import com.pynapli.shared.network.dto.ReportHistoryListDto
import com.pynapli.shared.network.dto.RequestAlbumAccessRequestDto
import com.pynapli.shared.network.dto.RequestAlbumAccessResponseDto
import com.pynapli.shared.network.dto.RespondAlbumAccessRequestDto
import com.pynapli.shared.network.dto.RespondAlbumAccessResponseDto
import com.pynapli.shared.network.dto.SavedGridFilterDto
import com.pynapli.shared.network.dto.SavedGridFiltersListDto
import com.pynapli.shared.network.dto.SavedSwipeFilterDto
import com.pynapli.shared.network.dto.SavedSwipeFiltersListDto
import com.pynapli.shared.network.dto.SendFlirtRequestDto
import com.pynapli.shared.network.dto.SendFlirtResponseDto
import com.pynapli.shared.network.dto.SendMessageRequestDto
import com.pynapli.shared.network.dto.SendTypingIndicatorRequestDto
import com.pynapli.shared.network.dto.SetTonightAvailableRequestDto
import com.pynapli.shared.network.dto.SetTravelLocationRequestDto
import com.pynapli.shared.network.dto.StartAgeVerificationRequestDto
import com.pynapli.shared.network.dto.StorePurchaseResponseDto
import com.pynapli.shared.network.dto.SubmitDevStorePurchaseRequestDto
import com.pynapli.shared.network.dto.SubmitPlayStoreSubscriptionPurchaseRequestDto
import com.pynapli.shared.network.dto.SubmitPsyncActionRequestDto
import com.pynapli.shared.network.dto.SubscriptionEntitlementDto
import com.pynapli.shared.network.dto.SubscriptionInventoryResponseDto
import com.pynapli.shared.network.dto.SubscriptionPurchaseSyncResponseDto
import com.pynapli.shared.network.dto.SuggestPsyncFiltersRequestDto
import com.pynapli.shared.network.dto.SupportContactDto
import com.pynapli.shared.network.dto.SwipeActionRequestDto
import com.pynapli.shared.network.dto.SwipeActionResponseDto
import com.pynapli.shared.network.dto.SwipeDeckRequestDto
import com.pynapli.shared.network.dto.SwipeDeckResponseDto
import com.pynapli.shared.network.dto.SwipeRewindResponseDto
import com.pynapli.shared.network.dto.TonightAvailabilityDto
import com.pynapli.shared.network.dto.TonightFeedResponseDto
import com.pynapli.shared.network.dto.TonightSessionDto
import com.pynapli.shared.network.dto.TopPicksResponseDto
import com.pynapli.shared.network.dto.TravelLocationDto
import com.pynapli.shared.network.dto.UnregisterPushDeviceRequestDto
import com.pynapli.shared.network.dto.UpdateCurrentLocationRequestDto
import com.pynapli.shared.network.dto.UpdateDiscoverySettingsRequestDto
import com.pynapli.shared.network.dto.UpdateNotificationPreferencesRequestDto
import com.pynapli.shared.network.dto.UpdateProfileRequestDto
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

interface PynapliApiClient :
    AuthApi,
    ConversationApi,
    GridApi,
    SwipeApi,
    ProfileApi,
    ActivityApi,
    SafetyApi,
    MediaApi,
    SubscriptionApi,
    VerificationApi,
    NotificationApi,
    ProfileLinkApi,
    AccountApi,
    LocationApi,
    TonightApi {
    // Psync (synchronized partner swipe). Server is the product authority; the client just
    // negotiates, swipes the shared deck privately, and reads the recap.
    suspend fun createPsyncSession(
        request: CreatePsyncSessionRequestDto = CreatePsyncSessionRequestDto(),
    ): AppResult<PsyncSessionDto>

    suspend fun getCurrentPsyncSession(): AppResult<CurrentPsyncSessionDto>

    suspend fun getPsyncSession(sessionId: String): AppResult<PsyncSessionDto>

    suspend fun acceptPsyncSession(sessionId: String): AppResult<PsyncSessionDto>

    suspend fun denyPsyncSession(sessionId: String): AppResult<PsyncSessionDto>

    suspend fun suggestPsyncFilters(
        sessionId: String,
        request: SuggestPsyncFiltersRequestDto,
    ): AppResult<PsyncSessionDto>

    suspend fun leavePsyncSession(sessionId: String): AppResult<PsyncSessionDto>

    suspend fun startPsyncSession(sessionId: String): AppResult<PsyncSessionDto>

    suspend fun getPsyncDeck(sessionId: String): AppResult<PsyncDeckResponseDto>

    suspend fun submitPsyncAction(
        sessionId: String,
        request: SubmitPsyncActionRequestDto,
    ): AppResult<PsyncActionResponseDto>

    suspend fun pausePsyncSession(sessionId: String): AppResult<PsyncSessionDto>

    suspend fun resumePsyncSession(sessionId: String): AppResult<PsyncSessionDto>

    suspend fun completePsyncSession(sessionId: String): AppResult<PsyncRecapDto>

    suspend fun getPsyncRecap(sessionId: String): AppResult<PsyncRecapDto>

    suspend fun nudgePsyncSession(sessionId: String): AppResult<PsyncNudgeResponseDto>

    suspend fun heartbeatPresence(): AppResult<PresenceHeartbeatResponseDto>
}

class KtorPynapliApiClient(
    private val config: PynapliConfig,
    private val accessTokenProvider: AccessTokenProvider,
    private val httpClient: HttpClient = createPynapliHttpClient(),
    private val json: Json = PynapliJson,
) : PynapliApiClient,
    AuthApi by KtorAuthApi(KtorApiSupport(httpClient, config, json, accessTokenProvider)),
    ConversationApi by KtorConversationApi(KtorApiSupport(httpClient, config, json, accessTokenProvider)),
    GridApi by KtorGridApi(KtorApiSupport(httpClient, config, json, accessTokenProvider)),
    SwipeApi by KtorSwipeApi(KtorApiSupport(httpClient, config, json, accessTokenProvider)),
    ProfileApi by KtorProfileApi(KtorApiSupport(httpClient, config, json, accessTokenProvider)),
    ActivityApi by KtorActivityApi(KtorApiSupport(httpClient, config, json, accessTokenProvider)),
    SafetyApi by KtorSafetyApi(KtorApiSupport(httpClient, config, json, accessTokenProvider)),
    MediaApi by KtorMediaApi(KtorApiSupport(httpClient, config, json, accessTokenProvider)),
    SubscriptionApi by KtorSubscriptionApi(KtorApiSupport(httpClient, config, json, accessTokenProvider)),
    VerificationApi by KtorVerificationApi(KtorApiSupport(httpClient, config, json, accessTokenProvider)),
    NotificationApi by KtorNotificationApi(KtorApiSupport(httpClient, config, json, accessTokenProvider)),
    ProfileLinkApi by KtorProfileLinkApi(KtorApiSupport(httpClient, config, json, accessTokenProvider)),
    AccountApi by KtorAccountApi(KtorApiSupport(httpClient, config, json, accessTokenProvider)),
    LocationApi by KtorLocationApi(KtorApiSupport(httpClient, config, json, accessTokenProvider)),
    TonightApi by KtorTonightApi(KtorApiSupport(httpClient, config, json, accessTokenProvider)) {
    override suspend fun createPsyncSession(request: CreatePsyncSessionRequestDto): AppResult<PsyncSessionDto> =
        authenticatedPost { token ->
            httpClient
                .post(config.endpoint("/v1/psync/sessions")) {
                    bearerAuth(token)
                    contentType(ContentType.Application.Json)
                    setBody(request)
                }.toAppResult(json)
        }

    override suspend fun getCurrentPsyncSession(): AppResult<CurrentPsyncSessionDto> =
        authenticatedPost { token ->
            httpClient
                .get(config.endpoint("/v1/me/psync/session")) {
                    bearerAuth(token)
                }.toAppResult(json)
        }

    override suspend fun getPsyncSession(sessionId: String): AppResult<PsyncSessionDto> =
        authenticatedPost { token ->
            httpClient
                .get(config.endpoint("/v1/psync/sessions/$sessionId")) {
                    bearerAuth(token)
                }.toAppResult(json)
        }

    override suspend fun acceptPsyncSession(sessionId: String): AppResult<PsyncSessionDto> =
        authenticatedPost { token ->
            httpClient
                .post(config.endpoint("/v1/psync/sessions/$sessionId/accept")) {
                    bearerAuth(token)
                }.toAppResult(json)
        }

    override suspend fun denyPsyncSession(sessionId: String): AppResult<PsyncSessionDto> =
        authenticatedPost { token ->
            httpClient
                .post(config.endpoint("/v1/psync/sessions/$sessionId/deny")) {
                    bearerAuth(token)
                }.toAppResult(json)
        }

    override suspend fun suggestPsyncFilters(
        sessionId: String,
        request: SuggestPsyncFiltersRequestDto,
    ): AppResult<PsyncSessionDto> =
        authenticatedPost { token ->
            httpClient
                .post(config.endpoint("/v1/psync/sessions/$sessionId/suggest-filters")) {
                    bearerAuth(token)
                    contentType(ContentType.Application.Json)
                    setBody(request)
                }.toAppResult(json)
        }

    override suspend fun leavePsyncSession(sessionId: String): AppResult<PsyncSessionDto> =
        authenticatedPost { token ->
            httpClient
                .post(config.endpoint("/v1/psync/sessions/$sessionId/leave")) {
                    bearerAuth(token)
                }.toAppResult(json)
        }

    override suspend fun startPsyncSession(sessionId: String): AppResult<PsyncSessionDto> =
        authenticatedPost { token ->
            httpClient
                .post(config.endpoint("/v1/psync/sessions/$sessionId/start")) {
                    bearerAuth(token)
                }.toAppResult(json)
        }

    override suspend fun getPsyncDeck(sessionId: String): AppResult<PsyncDeckResponseDto> =
        authenticatedPost { token ->
            httpClient
                .get(config.endpoint("/v1/psync/sessions/$sessionId/deck")) {
                    bearerAuth(token)
                }.toAppResult(json)
        }

    override suspend fun submitPsyncAction(
        sessionId: String,
        request: SubmitPsyncActionRequestDto,
    ): AppResult<PsyncActionResponseDto> =
        authenticatedPost { token ->
            httpClient
                .post(config.endpoint("/v1/psync/sessions/$sessionId/actions")) {
                    bearerAuth(token)
                    contentType(ContentType.Application.Json)
                    setBody(request)
                }.toAppResult(json)
        }

    override suspend fun pausePsyncSession(sessionId: String): AppResult<PsyncSessionDto> =
        authenticatedPost { token ->
            httpClient
                .post(config.endpoint("/v1/psync/sessions/$sessionId/pause")) {
                    bearerAuth(token)
                }.toAppResult(json)
        }

    override suspend fun resumePsyncSession(sessionId: String): AppResult<PsyncSessionDto> =
        authenticatedPost { token ->
            httpClient
                .post(config.endpoint("/v1/psync/sessions/$sessionId/resume")) {
                    bearerAuth(token)
                }.toAppResult(json)
        }

    override suspend fun completePsyncSession(sessionId: String): AppResult<PsyncRecapDto> =
        authenticatedPost { token ->
            httpClient
                .post(config.endpoint("/v1/psync/sessions/$sessionId/complete")) {
                    bearerAuth(token)
                }.toAppResult(json)
        }

    override suspend fun getPsyncRecap(sessionId: String): AppResult<PsyncRecapDto> =
        authenticatedPost { token ->
            httpClient
                .get(config.endpoint("/v1/psync/sessions/$sessionId/recap")) {
                    bearerAuth(token)
                }.toAppResult(json)
        }

    override suspend fun nudgePsyncSession(sessionId: String): AppResult<PsyncNudgeResponseDto> =
        authenticatedPost { token ->
            httpClient
                .post(config.endpoint("/v1/psync/sessions/$sessionId/nudge")) {
                    bearerAuth(token)
                }.toAppResult(json)
        }

    override suspend fun heartbeatPresence(): AppResult<PresenceHeartbeatResponseDto> =
        authenticatedPost { token ->
            httpClient
                .post(config.endpoint("/v1/presence/heartbeat")) {
                    bearerAuth(token)
                }.toAppResult(json)
        }

    private suspend inline fun <reified Request : Any, reified Response : Any> postJson(
        path: String,
        request: Request,
    ): AppResult<Response> =
        runCatching {
            httpClient
                .post(config.endpoint(path)) {
                    contentType(ContentType.Application.Json)
                    setBody(request)
                }.toAppResult<Response>(json)
        }.getOrElse { error ->
            AppResult.Failure(
                AppError(
                    code = "NETWORK_ERROR",
                    message = error.message ?: "Network request failed.",
                ),
            )
        }

    private suspend fun <T> authenticatedPost(
        call: suspend (String) -> AppResult<T>,
    ): AppResult<T> {
        val token = accessTokenProvider.accessToken()?.trim()
        if (token.isNullOrEmpty()) {
            return AppResult.Failure(
                AppError(
                    code = "UNAUTHENTICATED",
                    message = "Please sign in again.",
                ),
            )
        }

        return runCatching { call(token) }.getOrElse { error ->
            AppResult.Failure(
                AppError(
                    code = "NETWORK_ERROR",
                    message = error.message ?: "Network request failed.",
                ),
            )
        }
    }

    private fun PynapliConfig.endpoint(path: String): String = apiBaseUrl.trimEnd('/') + path
}

fun createPynapliHttpClient(json: Json = PynapliJson): HttpClient =
    HttpClient {
        expectSuccess = false
        install(ContentNegotiation) {
            json(json)
        }
    }

internal suspend inline fun <reified T> HttpResponse.toAppResult(json: Json): AppResult<T> {
    if (status.value in 200..299) {
        return AppResult.Success(body())
    }

    val apiError =
        runCatching {
            json.decodeFromString<ApiErrorDto>(bodyAsText())
        }.getOrNull()

    return AppResult.Failure(
        AppError(
            code = apiError?.code ?: "HTTP_${status.value}",
            message = apiError?.message ?: "Request failed.",
            recoverable = status.value >= 500 || status == HttpStatusCode.TooManyRequests,
        ),
    )
}
