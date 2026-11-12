package com.pynapli.server.testsupport

import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * Receives the first frame while re-running [emit] every 100 ms until one arrives.
 *
 * The stream routes subscribe to their broadcaster asynchronously after the socket opens (and the
 * presence route only after the subscribe message is handled), and broadcasters keep no replay, so a
 * single emit right after connecting is lost whenever that setup is slower than the test. A fixed
 * `delay(50)` made these tests fail under load (seen in the pre-push gate). Re-emitting is safe
 * because each test only reads the first frame, and sentinel tests emit the must-not-arrive delta
 * before the sentinel inside [emit] every time, so a leak still shows up first.
 */
suspend fun WebSocketSession.receiveWhileEmitting(
    timeoutMillis: Long = 10_000,
    emit: suspend () -> Unit,
): Frame =
    coroutineScope {
        val emitter =
            launch {
                while (isActive) {
                    emit()
                    delay(100)
                }
            }
        try {
            withTimeout(timeoutMillis) { incoming.receive() }
        } finally {
            emitter.cancel()
        }
    }
