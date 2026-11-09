package com.pynapli.shared.network

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.WebSockets

/**
 * Web realtime streams (WEB_PWA_GAMEPLAN W3.2). The default wasm engine rides the browser WebSocket
 * API, which can't set request headers, so these authenticate with a first frame (StreamAuthFrameDto).
 */
fun webRealtimeStreams(
    websocketBaseUrl: String,
    tokenProvider: AccessTokenProvider,
): RealtimeStreams =
    RealtimeStreams(
        httpClient = HttpClient { install(WebSockets) },
        websocketBaseUrl = websocketBaseUrl,
        tokenProvider = tokenProvider,
        sendTokenAsFirstFrame = true,
    )
