package com.pynapli.server.psync

import com.pynapli.server.auth.AuthService
import com.pynapli.server.realtime.installRealtimeWebSocketsIfNeeded
import com.pynapli.server.realtime.recipientStream
import com.pynapli.shared.network.dto.PsyncRealtimeDeltaDto
import io.ktor.server.application.Application
import io.ktor.server.routing.routing
import org.slf4j.LoggerFactory

/**
 * PS-4b: server-driven Psync session stream. Emits convenience deltas (partner started/
 * finished, session activated/completed/paused/resumed, nudge) to the authenticated viewer.
 * Never carries swipe-by-swipe detail; HTTP fetch remains canonical.
 */
fun Application.configurePsyncStreamRoutes(
    authService: AuthService,
    broadcaster: PsyncBroadcaster,
) {
    installRealtimeWebSocketsIfNeeded()
    val logger = LoggerFactory.getLogger("PsyncStreamRoutes")
    routing {
        recipientStream(
            path = "/v1/psync/stream",
            authService = authService,
            deltas = broadcaster.deltas,
            recipientOf = { it.recipientUserId },
            toDto = { it.toDto() },
            dtoSerializer = PsyncRealtimeDeltaDto.serializer(),
            logger = logger,
        )
    }
}
