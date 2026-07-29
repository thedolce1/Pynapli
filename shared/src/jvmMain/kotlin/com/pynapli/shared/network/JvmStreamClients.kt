package com.pynapli.shared.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.WebSockets

/*
 * JVM/desktop factories for the realtime stream clients, mirroring the Android and iOS ones.
 *
 * OkHttp rather than CIO: it is the same engine `AndroidPresenceStreamClient` uses, and the desktop
 * shell exists to stand in for the mobile ones during UI iteration — an engine difference here would
 * quietly undermine that. Native-style header auth like Android and iOS, not web's
 * `sendTokenAsFirstFrame`: that flag exists only because browsers cannot set WebSocket headers
 * (WEB_PWA_GAMEPLAN W3), and a JVM client has no such limit.
 */

fun buildJvmPresenceStreamClient(
    websocketBaseUrl: String,
    tokenProvider: AccessTokenProvider,
    backoff: PresenceStreamBackoff = PresenceStreamBackoff(),
): PresenceStreamClient =
    buildPresenceStreamClient(
        websocketHttpClient = HttpClient(OkHttp) { install(WebSockets) },
        websocketBaseUrl = websocketBaseUrl,
        tokenProvider = tokenProvider,
        backoff = backoff,
    )

fun buildJvmActivityStreamClient(
    websocketBaseUrl: String,
    tokenProvider: AccessTokenProvider,
    backoff: ActivityStreamBackoff = ActivityStreamBackoff(),
): ActivityStreamClient =
    buildActivityStreamClient(
        websocketHttpClient = HttpClient(OkHttp) { install(WebSockets) },
        websocketBaseUrl = websocketBaseUrl,
        tokenProvider = tokenProvider,
        backoff = backoff,
    )

fun buildJvmMessageStreamClient(
    websocketBaseUrl: String,
    tokenProvider: AccessTokenProvider,
    backoff: MessageStreamBackoff = MessageStreamBackoff(),
): MessageStreamClient =
    buildMessageStreamClient(
        websocketHttpClient = HttpClient(OkHttp) { install(WebSockets) },
        websocketBaseUrl = websocketBaseUrl,
        tokenProvider = tokenProvider,
        backoff = backoff,
    )

fun buildJvmPsyncStreamClient(
    websocketBaseUrl: String,
    tokenProvider: AccessTokenProvider,
    backoff: PsyncStreamBackoff = PsyncStreamBackoff(),
): PsyncStreamClient =
    buildPsyncStreamClient(
        websocketHttpClient = HttpClient(OkHttp) { install(WebSockets) },
        websocketBaseUrl = websocketBaseUrl,
        tokenProvider = tokenProvider,
        backoff = backoff,
    )
