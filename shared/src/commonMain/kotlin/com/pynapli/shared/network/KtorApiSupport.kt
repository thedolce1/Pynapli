package com.pynapli.shared.network

import com.pynapli.shared.core.config.PynapliConfig
import com.pynapli.shared.core.result.AppError
import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.core.result.runCatchingCancellable
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import kotlinx.serialization.json.Json

/**
 * Shared Ktor plumbing for the per-feature API clients (`KtorAuthApi`, `KtorGridApi`, …) extracted
 * from the monolithic `KtorPynapliApiClient`. Holds the HTTP client + config + JSON + token provider
 * and exposes the three call primitives every feature uses: [endpoint], [postJson] (unauthenticated),
 * and [authenticated] (bearer-token wrapper). Behavior matches the original private helpers verbatim.
 */
internal class KtorApiSupport(
    val httpClient: HttpClient,
    val config: PynapliConfig,
    val json: Json,
    private val accessTokenProvider: AccessTokenProvider,
) {
    fun endpoint(path: String): String = config.apiBaseUrl.trimEnd('/') + path

    suspend inline fun <reified Request : Any, reified Response : Any> postJson(
        path: String,
        request: Request,
    ): AppResult<Response> =
        runCatchingCancellable {
            httpClient
                .post(endpoint(path)) {
                    contentType(ContentType.Application.Json)
                    setBody(request)
                }.toAppResult<Response>(json)
        }.getOrElse { error ->
            error.toNetworkFailure()
        }

    /** Unauthenticated GET (public config such as `/v1/auth/social/config`). */
    suspend inline fun <reified Response : Any> getJson(path: String): AppResult<Response> =
        runCatchingCancellable {
            httpClient.get(endpoint(path)).toAppResult<Response>(json)
        }.getOrElse { error ->
            error.toNetworkFailure()
        }

    suspend fun <T> authenticated(call: suspend (String) -> AppResult<T>): AppResult<T> {
        val token = accessTokenProvider.accessToken()?.trim()
        if (token.isNullOrEmpty()) {
            return AppResult.Failure(
                AppError(
                    code = "UNAUTHENTICATED",
                    message = "Please sign in again.",
                ),
            )
        }

        return runCatchingCancellable { call(token) }.getOrElse { error -> error.toNetworkFailure() }
    }

    /**
     * One authenticated request: bearer token, optional extra [configure] (query parameters etc.),
     * and the response mapped through [toAppResult]. The typed helpers below cover every verb.
     */
    suspend inline fun <reified T> authRequest(
        method: HttpMethod,
        path: String,
        crossinline configure: HttpRequestBuilder.() -> Unit = {},
    ): AppResult<T> =
        authenticated { token ->
            httpClient
                .request(endpoint(path)) {
                    this.method = method
                    bearerAuth(token)
                    configure()
                }.toAppResult<T>(json)
        }

    suspend inline fun <reified T> authGet(
        path: String,
        crossinline configure: HttpRequestBuilder.() -> Unit = {},
    ): AppResult<T> = authRequest(HttpMethod.Get, path, configure)

    suspend inline fun <reified T> authDelete(
        path: String,
        crossinline configure: HttpRequestBuilder.() -> Unit = {},
    ): AppResult<T> = authRequest(HttpMethod.Delete, path, configure)

    suspend inline fun <reified T> authPost(
        path: String,
        crossinline configure: HttpRequestBuilder.() -> Unit = {},
    ): AppResult<T> = authRequest(HttpMethod.Post, path, configure)

    /** Authenticated request with a JSON [body]. */
    suspend inline fun <reified B, reified T> authSend(
        method: HttpMethod,
        path: String,
        body: B,
        crossinline configure: HttpRequestBuilder.() -> Unit = {},
    ): AppResult<T> =
        authRequest(method, path) {
            contentType(ContentType.Application.Json)
            setBody(body)
            configure()
        }
}

private fun Throwable.toNetworkFailure(): AppResult.Failure {
    val rawMessage = message.orEmpty()
    val timedOut =
        rawMessage.contains("timeout", ignoreCase = true) ||
            rawMessage.contains("timed out", ignoreCase = true)
    return AppResult.Failure(
        AppError(
            code = if (timedOut) "NETWORK_TIMEOUT" else "NETWORK_ERROR",
            message =
                if (timedOut) {
                    "Pynapli server did not respond. Check that the backend is running and this APK points to a reachable server."
                } else {
                    "Cannot reach Pynapli server. Check that the backend is running and this APK points to a reachable server."
                },
        ),
    )
}
