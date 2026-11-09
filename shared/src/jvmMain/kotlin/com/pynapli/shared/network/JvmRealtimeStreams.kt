package com.pynapli.shared.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.WebSockets

/**
 * Desktop (JVM) realtime streams. OkHttp, the same engine as Android, because the desktop shell stands
 * in for the mobile ones during UI iteration and an engine difference would quietly undermine that.
 * Native header auth like Android and iOS: only browsers need the first-frame token.
 */
fun jvmRealtimeStreams(
    websocketBaseUrl: String,
    tokenProvider: AccessTokenProvider,
): RealtimeStreams =
    RealtimeStreams(
        httpClient = HttpClient(OkHttp) { install(WebSockets) },
        websocketBaseUrl = websocketBaseUrl,
        tokenProvider = tokenProvider,
    )
