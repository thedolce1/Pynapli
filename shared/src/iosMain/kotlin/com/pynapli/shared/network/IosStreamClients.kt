package com.pynapli.shared.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.websocket.WebSockets

/*
 * iOS factories for the realtime stream clients, mirroring the Android ones.
 *
 * Darwin engine (NSURLSession) rather than OkHttp, and native like Android rather than like web:
 * these sockets can set an Authorization header, so there is no `sendTokenAsFirstFrame = true`.
 * That flag exists only because browsers cannot set WebSocket headers (WEB_PWA_GAMEPLAN W3) — the
 * server accepts either, so using the header path here keeps iOS on the same route as Android.
 *
 * Written on Windows, where `compileKotlinIosSimulatorArm64` type-checks this but no framework can
 * be linked. Compiled, not run: see docs/IOS_BRINGUP_PLAN.md.
 */

fun buildIosPresenceStreamClient(
    websocketBaseUrl: String,
    tokenProvider: AccessTokenProvider,
    backoff: PresenceStreamBackoff = PresenceStreamBackoff(),
): PresenceStreamClient =
    buildPresenceStreamClient(
        websocketHttpClient = HttpClient(Darwin) { install(WebSockets) },
        websocketBaseUrl = websocketBaseUrl,
        tokenProvider = tokenProvider,
        backoff = backoff,
    )

fun buildIosActivityStreamClient(
    websocketBaseUrl: String,
    tokenProvider: AccessTokenProvider,
    backoff: ActivityStreamBackoff = ActivityStreamBackoff(),
): ActivityStreamClient =
    buildActivityStreamClient(
        websocketHttpClient = HttpClient(Darwin) { install(WebSockets) },
        websocketBaseUrl = websocketBaseUrl,
        tokenProvider = tokenProvider,
        backoff = backoff,
    )

fun buildIosMessageStreamClient(
    websocketBaseUrl: String,
    tokenProvider: AccessTokenProvider,
    backoff: MessageStreamBackoff = MessageStreamBackoff(),
): MessageStreamClient =
    buildMessageStreamClient(
        websocketHttpClient = HttpClient(Darwin) { install(WebSockets) },
        websocketBaseUrl = websocketBaseUrl,
        tokenProvider = tokenProvider,
        backoff = backoff,
    )

fun buildIosPsyncStreamClient(
    websocketBaseUrl: String,
    tokenProvider: AccessTokenProvider,
    backoff: PsyncStreamBackoff = PsyncStreamBackoff(),
): PsyncStreamClient =
    buildPsyncStreamClient(
        websocketHttpClient = HttpClient(Darwin) { install(WebSockets) },
        websocketBaseUrl = websocketBaseUrl,
        tokenProvider = tokenProvider,
        backoff = backoff,
    )
