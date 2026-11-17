package com.pynapli.server.safety

import com.pynapli.server.plugins.configureSerialization
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RequestRateLimitPluginTest {
    /** Counts per key like Redis INCR; the window never ends during a test. */
    private class CountingRepository : RateLimitRepository {
        val counts = mutableMapOf<String, Long>()

        override suspend fun increment(
            key: String,
            windowSeconds: Long,
        ): RateLimitCounter {
            val next = (counts[key] ?: 0) + 1
            counts[key] = next
            return RateLimitCounter(count = next, ttlSeconds = 1_234)
        }
    }

    @Test
    fun aSessionOverItsMessageBudgetGets429WithRetryAfterWhileOthersAreUnaffected() =
        testApplication {
            application {
                configureSerialization()
                configureRequestLimits(RateLimitService(CountingRepository()))
                routing { post("/v1/messages/threads/{id}/messages") { call.respond("sent") } }
            }

            repeat(600) {
                val ok = client.post("/v1/messages/threads/t1/messages") { header(HttpHeaders.Authorization, "Bearer spammer-token") }
                assertEquals(HttpStatusCode.OK, ok.status)
            }
            val limited = client.post("/v1/messages/threads/t1/messages") { header(HttpHeaders.Authorization, "Bearer spammer-token") }
            assertEquals(HttpStatusCode.TooManyRequests, limited.status)
            assertEquals("1234", limited.headers[HttpHeaders.RetryAfter])
            assertTrue(limited.bodyAsText().contains("RATE_LIMITED"))

            val other = client.post("/v1/messages/threads/t1/messages") { header(HttpHeaders.Authorization, "Bearer someone-else") }
            assertEquals(HttpStatusCode.OK, other.status)
        }

    @Test
    fun loginIsLimitedPerClientIpTakenFromTheTrustedProxyHop() =
        testApplication {
            val repository = CountingRepository()
            application {
                configureSerialization()
                configureRequestLimits(RateLimitService(repository), trustedProxyHops = 1)
                routing { post("/v1/auth/email/login") { call.respond("ok") } }
            }

            // The client forges the left-most entry; the proxy appended the real address last.
            repeat(100) {
                client.post("/v1/auth/email/login") { header(HttpHeaders.XForwardedFor, "1.1.1.$it, 203.0.113.7") }
            }
            val limited = client.post("/v1/auth/email/login") { header(HttpHeaders.XForwardedFor, "9.9.9.9, 203.0.113.7") }
            assertEquals(HttpStatusCode.TooManyRequests, limited.status)
            assertNotNull(repository.counts.keys.singleOrNull { it.contains("auth_login_ip") }, "one IP counter, not one per forged entry")
        }

    @Test
    fun oversizedBodiesAreRefusedButMediaUploadsAndUnlistedRoutesPass() =
        testApplication {
            application {
                configureSerialization()
                configureRequestLimits(RateLimitService(CountingRepository()), maxBodyBytes = 16)
                routing {
                    post("/v1/profile") { call.respond("ok") }
                    post("/v1/media/uploads/local/abc") { call.respond("ok") }
                    get("/v1/grid") { call.respond("ok") }
                }
            }

            assertEquals(HttpStatusCode.PayloadTooLarge, client.post("/v1/profile") { setBody("x".repeat(64)) }.status)
            assertEquals(HttpStatusCode.OK, client.post("/v1/media/uploads/local/abc") { setBody("x".repeat(64)) }.status)
            assertEquals(HttpStatusCode.OK, client.get("/v1/grid").status)
        }
}
