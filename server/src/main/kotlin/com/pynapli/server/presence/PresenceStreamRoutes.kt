package com.pynapli.server.presence

import com.pynapli.server.auth.AuthService
import com.pynapli.server.realtime.RealtimeFanout
import com.pynapli.server.realtime.authenticateStream
import com.pynapli.server.realtime.installRealtimeWebSocketsIfNeeded
import com.pynapli.server.realtime.streamJson
import com.pynapli.server.safety.BlockRepository
import com.pynapli.shared.network.dto.PresenceDeltaDto
import com.pynapli.shared.network.dto.PresenceStreamSubscribeDto
import io.ktor.server.application.Application
import io.ktor.server.routing.routing
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

fun Application.configurePresenceStreamRoutes(
    authService: AuthService,
    broadcaster: PresenceBroadcaster,
    blockRepository: BlockRepository,
) {
    installRealtimeWebSocketsIfNeeded()

    val logger = LoggerFactory.getLogger("PresenceStreamRoutes")

    routing {
        webSocket("/v1/presence/stream") {
            val viewerId = authenticateStream(authService) ?: return@webSocket

            val subscription = AtomicReference<Set<UUID>>(emptySet())

            val emitterJob =
                launch {
                    broadcaster.deltas
                        // Own bounded buffer: the block check below is a DB read, and a slow socket
                        // must drop its oldest presence deltas rather than hold up everyone else's.
                        .buffer(RealtimeFanout.PER_CONNECTION_BUFFER, BufferOverflow.DROP_OLDEST)
                        .filter { delta ->
                            val targetId = runCatching { UUID.fromString(delta.userId) }.getOrNull() ?: return@filter false
                            targetId in subscription.get() &&
                                !blockRepository.isBlockedEitherWay(viewerId, targetId)
                        }.collect { delta ->
                            runCatching {
                                send(streamJson.encodeToString(PresenceDeltaDto.serializer(), delta))
                            }.onFailure { error ->
                                logger.debug("Failed to send presence delta to viewer={}: {}", viewerId, error.message)
                            }
                        }
                }

            try {
                for (frame in incoming) {
                    if (frame !is Frame.Text) continue
                    val payload =
                        runCatching {
                            streamJson.decodeFromString(PresenceStreamSubscribeDto.serializer(), frame.readText())
                        }.getOrNull() ?: continue
                    val parsed = payload.userIds.mapNotNull { runCatching { UUID.fromString(it) }.getOrNull() }.toSet()
                    subscription.set(parsed)
                }
            } catch (_: ClosedReceiveChannelException) {
                // Client disconnected; fall through.
            } finally {
                emitterJob.cancel()
            }
        }
    }
}
