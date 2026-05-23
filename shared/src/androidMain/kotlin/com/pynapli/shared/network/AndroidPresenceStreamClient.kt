package com.pynapli.shared.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.WebSockets

/**
 * Android factory for the WebSocket-capable HTTP client used by [PresenceStreamClient].
 * Uses the OkHttp engine because [io.ktor.client.engine.android.Android] does not support
 * WebSockets in Ktor 3.x.
 */
fun buildAndroidPresenceStreamClient(
    websocketBaseUrl: String,
    tokenProvider: AccessTokenProvider,
    backoff: PresenceStreamBackoff = PresenceStreamBackoff()
): PresenceStreamClient = buildPresenceStreamClient(
    websocketHttpClient = HttpClient(OkHttp) { install(WebSockets) },
    websocketBaseUrl = websocketBaseUrl,
    tokenProvider = tokenProvider,
    backoff = backoff
)

fun buildAndroidActivityStreamClient(
    websocketBaseUrl: String,
    tokenProvider: AccessTokenProvider,
    backoff: ActivityStreamBackoff = ActivityStreamBackoff()
): ActivityStreamClient = buildActivityStreamClient(
    websocketHttpClient = HttpClient(OkHttp) { install(WebSockets) },
    websocketBaseUrl = websocketBaseUrl,
    tokenProvider = tokenProvider,
    backoff = backoff
)

fun buildAndroidMessageStreamClient(
    websocketBaseUrl: String,
    tokenProvider: AccessTokenProvider,
    backoff: MessageStreamBackoff = MessageStreamBackoff()
): MessageStreamClient = buildMessageStreamClient(
    websocketHttpClient = HttpClient(OkHttp) { install(WebSockets) },
    websocketBaseUrl = websocketBaseUrl,
    tokenProvider = tokenProvider,
    backoff = backoff
)
