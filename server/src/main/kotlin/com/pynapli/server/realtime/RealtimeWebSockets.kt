package com.pynapli.server.realtime

import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.application.pluginOrNull
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.timeout
import kotlin.time.Duration.Companion.seconds

fun Application.installRealtimeWebSocketsIfNeeded() {
    if (pluginOrNull(WebSockets) != null) return

    install(WebSockets) {
        pingPeriod = 30.seconds
        timeout = 60.seconds
    }
}
