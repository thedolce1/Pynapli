package com.pynapli.server.realtime

import com.pynapli.server.auth.AuthException
import com.pynapli.server.auth.AuthService
import com.pynapli.server.auth.JdbcAuthRepository
import com.pynapli.server.auth.security.PasswordHasher
import com.pynapli.server.moderation.AdminRiskService
import com.pynapli.server.moderation.JdbcAdminRiskRepository
import com.pynapli.server.testsupport.PynapliTestDatabase
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import kotlinx.coroutines.isActive
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.time.Duration
import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets

class SessionRevocationIntegrationTest {
    private val dataSource = PynapliTestDatabase.dataSource

    @BeforeTest
    fun reset() {
        PynapliTestDatabase.reset()
    }

    private fun authService(
        relay: RealtimeRelay = LocalOnlyRealtimeRelay,
        cacheTtl: Duration = Duration.ofSeconds(5),
    ) = AuthService(
        JdbcAuthRepository(dataSource),
        passwordHasher = PasswordHasher(iterations = 1_000),
        accessSessionCacheTtl = cacheTtl,
        revocationRelay = relay,
    )

    private suspend fun AuthService.newSession(email: String): String = signupEmail(email, "revocation-pass-1", "1990-01-01", true, true, true, null, "android", "1.0").accessToken

    @Test
    fun anOpenStreamClosesOnceItsSessionIsSignedOut() =
        testApplication {
            val auth = authService()
            val token = runBlocking { auth.newSession("socket@example.com") }
            application {
                installRealtimeWebSocketsIfNeeded()
                routing {
                    webSocket("/test/stream") {
                        authenticateStream(auth, recheckEveryMillis = 100) ?: return@webSocket
                        for (frame in incoming) {
                            // Server-driven; nothing to read.
                        }
                    }
                }
            }
            val client = createClient { install(ClientWebSockets) }

            runBlocking {
                client.webSocket("/test/stream", request = { header(HttpHeaders.Authorization, "Bearer $token") }) {
                    auth.logout(token)
                    // Drain until the server's close arrives (generous: the gate runs this under load).
                    val reason =
                        withTimeout(20_000) {
                            for (frame in incoming) {
                                // Nothing is sent before the close.
                            }
                            closeReason.await()
                        }
                    assertEquals(CloseReason.Codes.VIOLATED_POLICY.code, reason?.code)
                    assertEquals("UNAUTHENTICATED", reason?.message)
                }
            }
        }

    @Test
    fun aBanClosesTheAccountsOpenStreamsAtOnceNotAtTheNextPeriodicCheck() =
        testApplication {
            val auth = authService()
            val session = runBlocking { auth.signupEmail("banned-socket@example.com", "revocation-pass-1", "1990-01-01", true, true, true, null, "android", "1.0") }
            val userId = UUID.fromString(session.user.userId)
            application {
                installRealtimeWebSocketsIfNeeded()
                routing {
                    webSocket("/test/stream") {
                        // The default periodic re-check is a minute away; only the revocation can close this in time.
                        authenticateStream(auth) ?: return@webSocket
                        for (frame in incoming) {
                            // Server-driven; nothing to read.
                        }
                    }
                }
            }
            val client = createClient { install(ClientWebSockets) }

            runBlocking {
                client.webSocket("/test/stream", request = { header(HttpHeaders.Authorization, "Bearer ${session.accessToken}") }) {
                    // Let the server finish authenticating before the ban lands.
                    kotlinx.coroutines.delay(300)
                    AdminRiskService(JdbcAdminRiskRepository(dataSource)).banAccount(userId.toString(), "senior-1", "confirmed abuse")
                    auth.invalidateCachedSessionsForUser(userId)
                    val reason =
                        withTimeout(10_000) {
                            for (frame in incoming) {
                                // Nothing is sent before the close.
                            }
                            closeReason.await()
                        }
                    assertEquals(CloseReason.Codes.VIOLATED_POLICY.code, reason?.code)
                }
            }
        }

    @Test
    fun aSixthStreamOfOneTypeClosesTheOldestAndOversizedFramesAreRefused() =
        testApplication {
            val auth = authService()
            val token = runBlocking { auth.newSession("many-sockets@example.com") }
            application {
                installRealtimeWebSocketsIfNeeded()
                routing {
                    webSocket("/test/stream") {
                        authenticateStream(auth) ?: return@webSocket
                        for (frame in incoming) {
                            // Server-driven; nothing to read.
                        }
                    }
                }
            }
            val client = createClient { install(ClientWebSockets) }

            runBlocking {
                val first = client.webSocketSession("/test/stream") { header(HttpHeaders.Authorization, "Bearer $token") }
                // Let the server register each socket before the next one opens.
                kotlinx.coroutines.delay(200)
                val others =
                    (1..MAX_STREAMS_PER_USER).map {
                        client.webSocketSession("/test/stream") { header(HttpHeaders.Authorization, "Bearer $token") }.also { kotlinx.coroutines.delay(200) }
                    }
                val evicted = withTimeout(10_000) { first.closeReason.await() }
                assertEquals(CloseReason.Codes.VIOLATED_POLICY.code, evicted?.code)
                assertEquals("TOO_MANY_STREAMS", evicted?.message)
                assertEquals(true, others.all { it.isActive }, "the newest sockets stay open")

                // A frame over the size cap closes the socket.
                val newest = others.last()
                newest.send(Frame.Text("x".repeat(STREAM_MAX_FRAME_BYTES.toInt() + 1)))
                val tooBig = withTimeout(10_000) { newest.closeReason.await() }
                assertEquals(CloseReason.Codes.TOO_BIG.code, tooBig?.code)
                others.forEach { runCatching { it.close() } }
            }
        }

    @Test
    fun anOversizedFirstAuthFrameIsRefusedBeforeItIsParsed() =
        testApplication {
            val auth = authService()
            val token = runBlocking { auth.newSession("padded-frame@example.com") }
            application {
                installRealtimeWebSocketsIfNeeded()
                routing {
                    webSocket("/test/stream") {
                        authenticateStream(auth) ?: return@webSocket
                        for (frame in incoming) {
                            // Server-driven; nothing to read.
                        }
                    }
                }
            }
            val client = createClient { install(ClientWebSockets) }

            runBlocking {
                client.webSocket("/test/stream") {
                    // A valid token, padded past the cap with a field the parser would otherwise ignore.
                    send(Frame.Text("""{"type":"auth","token":"$token","pad":"${"x".repeat(STREAM_AUTH_FRAME_MAX_BYTES)}"}"""))
                    val reason = withTimeout(10_000) { closeReason.await() }
                    assertEquals(CloseReason.Codes.VIOLATED_POLICY.code, reason?.code)
                    assertEquals("UNAUTHENTICATED", reason?.message)
                }
            }
        }

    @Test
    fun aSignOutOnOneReplicaIsHonouredAtOnceByAnotherThatCachedTheSession() =
        runBlocking<Unit> {
            val relayA = RedisRealtimeRelay(PynapliTestDatabase.redisUri())
            val relayB = RedisRealtimeRelay(PynapliTestDatabase.redisUri())
            try {
                // A long cache on B: without the relay, B would keep accepting the token for a minute.
                val replicaA = authService(relay = relayA)
                val replicaB = authService(relay = relayB, cacheTtl = Duration.ofMinutes(1))
                val token = replicaA.newSession("replicas@example.com")
                replicaB.authenticate(token)

                replicaA.logout(token)

                withTimeout(5_000) {
                    while (runCatching { replicaB.authenticate(token) }.isSuccess) kotlinx.coroutines.delay(20)
                }
                assertFailsWith<AuthException> { replicaB.authenticate(token) }
            } finally {
                relayA.close()
                relayB.close()
            }
        }
}
