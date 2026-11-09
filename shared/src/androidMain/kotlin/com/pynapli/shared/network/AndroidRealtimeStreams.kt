package com.pynapli.shared.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.WebSockets

/**
 * Android realtime streams over one OkHttp client: the Ktor Android engine doesn't support WebSockets
 * in Ktor 3.x. Native header auth (no first-frame token).
 */
fun androidRealtimeStreams(
    websocketBaseUrl: String,
    tokenProvider: AccessTokenProvider,
): RealtimeStreams =
    RealtimeStreams(
        httpClient = HttpClient(OkHttp) { install(WebSockets) },
        websocketBaseUrl = websocketBaseUrl,
        tokenProvider = tokenProvider,
    )
