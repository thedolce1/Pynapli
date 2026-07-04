package com.pynapli.shared.network

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.WebSockets

/*
 * Web factories for the realtime stream clients (WEB_PWA_GAMEPLAN.md W3.2). The default
 * wasm engine rides the browser WebSocket API, which cannot set request headers — so the
 * web clients authenticate with a first frame (see StreamAuthFrameDto) instead of the
 * Authorization header the native factories use.
 */

fun buildWebPresenceStreamClient(
    websocketBaseUrl: String,
    tokenProvider: AccessTokenProvider,
    backoff: PresenceStreamBackoff = PresenceStreamBackoff(),
): PresenceStreamClient =
    buildPresenceStreamClient(
        websocketHttpClient = HttpClient { install(WebSockets) },
        websocketBaseUrl = websocketBaseUrl,
        tokenProvider = tokenProvider,
        backoff = backoff,
        sendTokenAsFirstFrame = true,
    )

fun buildWebActivityStreamClient(
    websocketBaseUrl: String,
    tokenProvider: AccessTokenProvider,
    backoff: ActivityStreamBackoff = ActivityStreamBackoff(),
): ActivityStreamClient =
    buildActivityStreamClient(
        websocketHttpClient = HttpClient { install(WebSockets) },
        websocketBaseUrl = websocketBaseUrl,
        tokenProvider = tokenProvider,
        backoff = backoff,
        sendTokenAsFirstFrame = true,
    )

fun buildWebMessageStreamClient(
    websocketBaseUrl: String,
    tokenProvider: AccessTokenProvider,
    backoff: MessageStreamBackoff = MessageStreamBackoff(),
): MessageStreamClient =
    buildMessageStreamClient(
        websocketHttpClient = HttpClient { install(WebSockets) },
        websocketBaseUrl = websocketBaseUrl,
        tokenProvider = tokenProvider,
        backoff = backoff,
        sendTokenAsFirstFrame = true,
    )
