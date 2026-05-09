package com.pynapli.server.presence

import com.pynapli.server.auth.AuthException
import com.pynapli.server.auth.AuthService
import com.pynapli.server.safety.BlockRepository
import com.pynapli.shared.network.dto.PresenceDeltaDto
import com.pynapli.shared.network.dto.PresenceStreamSubscribeDto
import io.ktor.http.HttpHeaders
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.timeout
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.seconds

private val streamJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

fun Application.configurePresenceStreamRoutes(
    authService: AuthService?,
    broadcaster: PresenceBroadcaster?,
    blockRepository: BlockRepository?
) {
    if (authService == null || broadcaster == null || blockRepository == null) return

    install(WebSockets) {
        pingPeriod = 30.seconds
        timeout = 60.seconds
    }

    val logger = LoggerFactory.getLogger("PresenceStreamRoutes")

    routing {
        webSocket("/v1/presence/stream") {
            val token = call.request.headers[HttpHeaders.Authorization]
                ?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
                ?.substringAfter("Bearer ")
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
            if (token == null) {
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "UNAUTHENTICATED"))
                return@webSocket
            }

            val viewerId = try {
                authService.authenticate(token).id
            } catch (e: AuthException) {
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, e.code))
                return@webSocket
            }

            val subscription = AtomicReference<Set<UUID>>(emptySet())

            val emitterJob = launch {
                broadcaster.deltas
                    .filter { delta ->
                        val targetId = runCatching { UUID.fromString(delta.userId) }.getOrNull() ?: return@filter false
                        targetId in subscription.get() &&
                            !blockRepository.isBlockedEitherWay(viewerId, targetId)
                    }
                    .collect { delta ->
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
                    val payload = runCatching {
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
