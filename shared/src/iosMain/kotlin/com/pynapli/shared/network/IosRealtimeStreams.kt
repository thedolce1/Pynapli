package com.pynapli.shared.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.websocket.WebSockets

/**
 * iOS realtime streams over the Darwin engine (NSURLSession). Native header auth like Android: these
 * sockets can set an Authorization header, so no first-frame token. Type-checks on Windows via
 * `compileKotlinIosSimulatorArm64`; linking and running need a Mac (docs/IOS_BRINGUP_PLAN.md).
 */
fun iosRealtimeStreams(
    websocketBaseUrl: String,
    tokenProvider: AccessTokenProvider,
): RealtimeStreams =
    RealtimeStreams(
        httpClient = HttpClient(Darwin) { install(WebSockets) },
        websocketBaseUrl = websocketBaseUrl,
        tokenProvider = tokenProvider,
    )
