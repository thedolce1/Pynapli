package com.pynapli.shared.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.plugins.websocket.WebSockets
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KtorStreamSessionFactoryTest {
    @Test
    fun cancellingConnectMidHandshakeCancelsTheSocketJob() =
        runTest {
            val handshake = HandshakeGate()
            val sessionJob = SupervisorJob()
            val sessionScope = CoroutineScope(sessionJob + StandardTestDispatcher(testScheduler))
            val factory = KtorStreamSessionFactory(handshake.client, STREAM_URL, sessionScope = sessionScope)
            try {
                // The user leaves the screen while the socket is still handshaking.
                val caller = launch { factory.connect("token") }
                handshake.reached.await()
                caller.cancelAndJoin()
                advanceUntilIdle()

                assertTrue(
                    sessionJob.children.none { it.isActive },
                    "the socket job outlived the cancelled connect: ${sessionJob.children.toList()}",
                )
            } finally {
                sessionScope.cancel()
                handshake.client.close()
            }
        }

    @Test
    fun aSessionJobCancelledBeforeItConnectsFailsTheConnectInsteadOfHangingIt() =
        runTest {
            val handshake = HandshakeGate()
            val sessionJob = SupervisorJob()
            val sessionScope = CoroutineScope(sessionJob + StandardTestDispatcher(testScheduler))
            val factory = KtorStreamSessionFactory(handshake.client, STREAM_URL, sessionScope = sessionScope)
            val connect = async { runCatching { factory.connect("token") } }
            try {
                handshake.reached.await()
                sessionJob.cancel()
                advanceUntilIdle()

                assertTrue(connect.isCompleted, "connect() still hangs after its session job was cancelled")
                val failure = connect.await().exceptionOrNull()
                assertTrue(failure != null, "connect() should fail when its session never opened")
                // Not a CancellationException: JsonStreamClient would read that as its own collector
                // being cancelled and stop reconnecting.
                assertFalse(failure is CancellationException, "connect() failed with a cancellation: $failure")
            } finally {
                connect.cancel()
                handshake.client.close()
            }
        }
}

/** An HttpClient whose WebSocket handshake parks forever once it starts, until the test cancels it. */
private class HandshakeGate {
    val reached = CompletableDeferred<Unit>()
    private val never = CompletableDeferred<Unit>()

    val client =
        HttpClient(MockEngine { error("the handshake gate never lets a request reach the engine") }) {
            install(WebSockets)
            install(
                createClientPlugin("HandshakeGate") {
                    onRequest { _, _ ->
                        reached.complete(Unit)
                        never.await()
                    }
                },
            )
        }
}

private const val STREAM_URL = "ws://localhost/v1/messages/stream"
