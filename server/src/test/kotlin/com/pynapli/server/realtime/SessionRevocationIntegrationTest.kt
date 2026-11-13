package com.pynapli.server.realtime

import com.pynapli.server.auth.AuthException
import com.pynapli.server.auth.AuthService
import com.pynapli.server.auth.JdbcAuthRepository
import com.pynapli.server.auth.security.PasswordHasher
import com.pynapli.server.testsupport.PynapliTestDatabase
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.time.Duration
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
                    val reason = withTimeout(5_000) { closeReason.await() }
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
