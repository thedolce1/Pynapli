package com.pynapli.server.activity

import com.pynapli.server.auth.AuthService
import com.pynapli.server.realtime.installRealtimeWebSocketsIfNeeded
import com.pynapli.server.realtime.recipientStream
import com.pynapli.shared.network.dto.ActivityDeltaDto
import io.ktor.server.application.Application
import io.ktor.server.routing.routing
import org.slf4j.LoggerFactory

fun Application.configureActivityStreamRoutes(
    authService: AuthService,
    broadcaster: ActivityBroadcaster,
) {
    installRealtimeWebSocketsIfNeeded()
    val logger = LoggerFactory.getLogger("ActivityStreamRoutes")
    routing {
        recipientStream(
            path = "/v1/activity/stream",
            authService = authService,
            deltas = broadcaster.deltas,
            recipientOf = { it.recipientUserId },
            toDto = { it.toDto() },
            dtoSerializer = ActivityDeltaDto.serializer(),
            logger = logger,
        )
    }
}
