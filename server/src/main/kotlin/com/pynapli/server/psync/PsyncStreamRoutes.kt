package com.pynapli.server.psync

import com.pynapli.server.auth.AuthException
import com.pynapli.server.auth.AuthService
import com.pynapli.server.realtime.installRealtimeWebSocketsIfNeeded
import com.pynapli.shared.network.dto.PsyncRealtimeDeltaDto
import io.ktor.http.HttpHeaders
import io.ktor.server.application.Application
import io.ktor.server.routing.routing
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.close
import io.ktor.websocket.send
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

private val psyncStreamJson =
    Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

/**
 * PS-4b: server-driven Psync session stream. Emits convenience deltas (partner started/
 * finished, session activated/completed/paused/resumed, nudge) to the authenticated viewer.
 * Never carries swipe-by-swipe detail; HTTP fetch remains canonical.
 */
fun Application.configurePsyncStreamRoutes(
    authService: AuthService?,
    broadcaster: PsyncBroadcaster?,
) {
    if (authService == null || broadcaster == null) return

    installRealtimeWebSocketsIfNeeded()

    val logger = LoggerFactory.getLogger("PsyncStreamRoutes")

    routing {
        webSocket("/v1/psync/stream") {
            val token =
                call.request.headers[HttpHeaders.Authorization]
                    ?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
                    ?.substringAfter("Bearer ")
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
            if (token == null) {
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "UNAUTHENTICATED"))
                return@webSocket
            }

            val viewerId =
                try {
                    authService.authenticate(token).id
                } catch (e: AuthException) {
                    close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, e.code))
                    return@webSocket
                }

            val emitterJob =
                launch {
                    broadcaster.deltas
                        .filter { delta -> delta.recipientUserId == viewerId }
                        .collect { delta ->
                            runCatching {
                                send(psyncStreamJson.encodeToString(PsyncRealtimeDeltaDto.serializer(), delta.toDto()))
                            }.onFailure { error ->
                                logger.debug("Failed to send psync delta to viewer={}: {}", viewerId, error.message)
                            }
                        }
                }

            try {
                for (frame in incoming) {
                    // Psync stream is server-driven. Incoming frames are ignored to keep the contract small.
                }
            } catch (_: ClosedReceiveChannelException) {
                // Client disconnected; fall through.
            } finally {
                emitterJob.cancel()
            }
        }
    }
}
