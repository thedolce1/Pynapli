package com.pynapli.server.realtime

import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.application.pluginOrNull
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.timeout
import kotlin.time.Duration.Companion.seconds

/** Largest frame a client may send on any stream (presence subscriptions are the biggest real ones). */
const val STREAM_MAX_FRAME_BYTES: Long = 64L * 1_024

fun Application.installRealtimeWebSocketsIfNeeded() {
    if (pluginOrNull(WebSockets) != null) return

    install(WebSockets) {
        pingPeriod = 30.seconds
        timeout = 60.seconds
        // Bigger frames close the socket (1009) before they are buffered whole.
        maxFrameSize = STREAM_MAX_FRAME_BYTES
    }
}
