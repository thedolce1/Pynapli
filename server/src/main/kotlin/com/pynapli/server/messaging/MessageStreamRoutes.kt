package com.pynapli.server.messaging

import com.pynapli.server.auth.AuthService
import com.pynapli.server.realtime.installRealtimeWebSocketsIfNeeded
import com.pynapli.server.realtime.recipientStream
import com.pynapli.shared.network.dto.MessageDeltaDto
import io.ktor.server.application.Application
import io.ktor.server.routing.routing
import org.slf4j.LoggerFactory

fun Application.configureMessageStreamRoutes(
    authService: AuthService,
    broadcaster: MessageBroadcaster,
) {
    installRealtimeWebSocketsIfNeeded()
    val logger = LoggerFactory.getLogger("MessageStreamRoutes")
    routing {
        recipientStream(
            path = "/v1/messages/stream",
            authService = authService,
            deltas = broadcaster.deltas,
            recipientOf = { it.recipientUserId },
            toDto = { it.toDto() },
            dtoSerializer = MessageDeltaDto.serializer(),
            logger = logger,
        )
    }
}
