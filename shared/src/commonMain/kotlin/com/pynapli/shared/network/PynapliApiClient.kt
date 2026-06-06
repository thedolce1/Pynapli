package com.pynapli.shared.network

import com.pynapli.shared.core.config.PynapliConfig
import com.pynapli.shared.core.result.AppError
import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.network.dto.ApiErrorDto
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
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
    TonightApi,
    // Psync (synchronized partner swipe). Server is the product authority; the client just
    // negotiates, swipes the shared deck privately, and reads the recap.
    PsyncApi,
    PresenceApi

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
    TonightApi by KtorTonightApi(KtorApiSupport(httpClient, config, json, accessTokenProvider)),
    PsyncApi by KtorPsyncApi(KtorApiSupport(httpClient, config, json, accessTokenProvider)),
    PresenceApi by KtorPresenceApi(KtorApiSupport(httpClient, config, json, accessTokenProvider))

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
