package com.pynapli.server.messaging

import com.pynapli.server.auth.AuthException
import com.pynapli.server.auth.AuthService
import com.pynapli.server.realtime.awaitStreamToken
import com.pynapli.server.realtime.installRealtimeWebSocketsIfNeeded
import com.pynapli.shared.network.dto.MessageDeltaDto
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

private val messageStreamJson =
    Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

fun Application.configureMessageStreamRoutes(
    authService: AuthService?,
    broadcaster: MessageBroadcaster?,
) {
    if (authService == null || broadcaster == null) return

    installRealtimeWebSocketsIfNeeded()

    val logger = LoggerFactory.getLogger("MessageStreamRoutes")

    routing {
        webSocket("/v1/messages/stream") {
            // Header for native clients; first-frame auth for browsers (awaitStreamToken).
            val token = awaitStreamToken()
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
                                send(messageStreamJson.encodeToString(MessageDeltaDto.serializer(), delta.toDto()))
                            }.onFailure { error ->
                                logger.debug("Failed to send message delta to viewer={}: {}", viewerId, error.message)
                            }
                        }
                }

            try {
                for (frame in incoming) {
                    // Message stream is server-driven. Incoming frames are ignored to keep the contract small.
                }
            } catch (_: ClosedReceiveChannelException) {
                // Client disconnected; fall through.
            } finally {
                emitterJob.cancel()
            }
        }
    }
}
