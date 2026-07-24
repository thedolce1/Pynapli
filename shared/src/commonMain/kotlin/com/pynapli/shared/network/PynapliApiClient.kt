package com.pynapli.shared.network

import com.pynapli.shared.core.config.PynapliConfig
import com.pynapli.shared.core.result.AppError
import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.network.dto.ApiErrorDto
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.plugin
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
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
        // Pass a SessionRefresherHolder here and fill in its delegate once the auth layer exists;
        // without a refresher the client has no way to recover from the 15-minute access-token TTL.
        sessionRefresher: SessionRefresher? = null,
    ) : this(
        config = config,
        accessTokenProvider = accessTokenProvider,
        httpClient =
            createPynapliHttpClient(
                json = json,
                appCheckTokenProvider = appCheckTokenProvider,
                sessionRefresher = sessionRefresher,
            ),
        json = json,
    )
}

fun createPynapliHttpClient(
    json: Json = PynapliJson,
    appCheckTokenProvider: AppCheckTokenProvider = NoAppCheckTokenProvider,
    sessionRefresher: SessionRefresher? = null,
): HttpClient =
    HttpClient {
        configurePynapliHttpClient(json, appCheckTokenProvider, sessionRefresher)
    }

internal fun createPynapliHttpClient(
    engine: HttpClientEngine,
    json: Json = PynapliJson,
    appCheckTokenProvider: AppCheckTokenProvider = NoAppCheckTokenProvider,
    sessionRefresher: SessionRefresher? = null,
): HttpClient =
    HttpClient(engine) {
        configurePynapliHttpClient(json, appCheckTokenProvider, sessionRefresher)
    }

private fun io.ktor.client.HttpClientConfig<*>.configurePynapliHttpClient(
    json: Json,
    appCheckTokenProvider: AppCheckTokenProvider,
    sessionRefresher: SessionRefresher?,
) {
    expectSuccess = false
    install(ContentNegotiation) {
        json(json)
    }
    install(HttpTimeout) {
        connectTimeoutMillis = 8_000
        socketTimeoutMillis = 15_000
        requestTimeoutMillis = 15_000
    }
    install(AppCheckHeaderPlugin) {
        tokenProvider = appCheckTokenProvider
    }
    if (sessionRefresher != null) {
        install(SessionRefreshPlugin) {
            refresher = sessionRefresher
        }
    }
}

private class SessionRefreshPluginConfig {
    var refresher: SessionRefresher? = null
}

/**
 * Refresh-on-401: when an authenticated call comes back 401, swap the expired access token for a
 * fresh one and replay the request once. Without this, the 15-minute access-token TTL surfaced as
 * "signed out" — the app read a 401 as a terminal auth failure and cleared the session, even though
 * a valid refresh token was sitting in the store the whole time.
 *
 * Three guards keep it from looping or thrashing: the refresh call itself is never retried,
 * unauthenticated requests are left alone (a 401 there is real), and a request is replayed at most
 * once. Concurrency is the refresher's job — the server rotates refresh tokens, so
 * [SessionRefresher] implementations are single-flight.
 */
private val SessionRefreshPlugin =
    createClientPlugin("PynapliSessionRefresh", ::SessionRefreshPluginConfig) {
        val refresher = pluginConfig.refresher ?: return@createClientPlugin
        client.plugin(HttpSend).intercept { request ->
            val call = execute(request)
            if (call.response.status != HttpStatusCode.Unauthorized) return@intercept call
            // Refreshing the refresh call would recurse; a 401 there is genuinely terminal.
            val path =
                request.url.encodedPathSegments
                    .filter { it.isNotEmpty() }
                    .joinToString("/", prefix = "/")
            if (path.endsWith(AUTH_REFRESH_PATH)) return@intercept call
            // No bearer token means this 401 is not about an expired session.
            val sentAuthorization = request.headers[HttpHeaders.Authorization] ?: return@intercept call
            // Pass the token that actually failed so concurrent 401s collapse into one refresh.
            val expiredAccessToken = sentAuthorization.removePrefix("Bearer ").trim().takeIf { it.isNotEmpty() }

            val newAccessToken = refresher.refreshAccessToken(expiredAccessToken) ?: return@intercept call
            request.headers.remove(HttpHeaders.Authorization)
            request.headers.append(HttpHeaders.Authorization, "Bearer $newAccessToken")
            execute(request)
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
