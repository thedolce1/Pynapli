package com.pynapli.shared.network

import com.pynapli.shared.core.config.PynapliConfig
import com.pynapli.shared.core.result.AppError
import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.network.dto.ApiErrorDto
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.api.createClientPlugin
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
    PresenceApi by KtorPresenceApi(KtorApiSupport(httpClient, config, json, accessTokenProvider)) {
    constructor(
        config: PynapliConfig,
        accessTokenProvider: AccessTokenProvider,
        appCheckTokenProvider: AppCheckTokenProvider,
        json: Json = PynapliJson,
    ) : this(
        config = config,
        accessTokenProvider = accessTokenProvider,
        httpClient = createPynapliHttpClient(json = json, appCheckTokenProvider = appCheckTokenProvider),
        json = json,
    )
}

fun createPynapliHttpClient(
    json: Json = PynapliJson,
    appCheckTokenProvider: AppCheckTokenProvider = NoAppCheckTokenProvider,
): HttpClient =
    HttpClient {
        configurePynapliHttpClient(json, appCheckTokenProvider)
    }

internal fun createPynapliHttpClient(
    engine: HttpClientEngine,
    json: Json = PynapliJson,
    appCheckTokenProvider: AppCheckTokenProvider = NoAppCheckTokenProvider,
): HttpClient =
    HttpClient(engine) {
        configurePynapliHttpClient(json, appCheckTokenProvider)
    }

private fun io.ktor.client.HttpClientConfig<*>.configurePynapliHttpClient(
    json: Json,
    appCheckTokenProvider: AppCheckTokenProvider,
) {
    expectSuccess = false
    install(ContentNegotiation) {
        json(json)
    }
    install(AppCheckHeaderPlugin) {
        tokenProvider = appCheckTokenProvider
    }
}

private class AppCheckHeaderPluginConfig {
    var tokenProvider: AppCheckTokenProvider = NoAppCheckTokenProvider
}

private val AppCheckHeaderPlugin =
    createClientPlugin("PynapliAppCheckHeader", ::AppCheckHeaderPluginConfig) {
        val tokenProvider = pluginConfig.tokenProvider
        onRequest { request, _ ->
            val token = tokenProvider.appCheckToken()?.trim()?.takeIf { it.isNotEmpty() } ?: return@onRequest
            request.headers.append(APP_CHECK_HEADER, token)
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
