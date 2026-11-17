package com.pynapli.server.safety

import com.pynapli.server.plugins.configureSerialization
import com.pynapli.server.plugins.configureStatusPages
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.request
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import kotlinx.serialization.Serializable
import java.util.UUID
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

        override suspend fun current(key: String) = counts[key]?.let { RateLimitCounter(count = it, ttlSeconds = 1_234) }
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

    /** A body sent chunked, with no Content-Length header for the cap to look at. */
    private fun chunkedBody(text: String) =
        object : OutgoingContent.WriteChannelContent() {
            override val contentType = ContentType.Application.Json

            override suspend fun writeTo(channel: ByteWriteChannel) {
                channel.writeFully(text.toByteArray())
            }
        }

    @Serializable
    private data class NoteDto(
        val note: String,
    )

    @Test
    fun aChunkedBodyWithNoContentLengthIsCappedOnTheBytesActuallyRead() =
        testApplication {
            application {
                configureSerialization()
                configureStatusPages()
                configureRequestLimits(RateLimitService(CountingRepository()), maxBodyBytes = 64)
                routing { post("/v1/profile") { call.respond(call.receive<NoteDto>().note) } }
            }

            val oversized = client.post("/v1/profile") { setBody(chunkedBody("""{"note":"${"x".repeat(500)}"}""")) }
            assertEquals(null, oversized.request.headers[HttpHeaders.ContentLength])
            assertEquals(HttpStatusCode.PayloadTooLarge, oversized.status)
            assertTrue(oversized.bodyAsText().contains("PAYLOAD_TOO_LARGE"))

            val small = client.post("/v1/profile") { setBody(chunkedBody("""{"note":"hi"}""")) }
            assertEquals(HttpStatusCode.OK, small.status)
        }

    @Test
    fun authenticatedRulesFollowTheUserAcrossSignInsNotTheSession() =
        testApplication {
            val sameUser = UUID.randomUUID()
            application {
                configureSerialization()
                configureRequestLimits(RateLimitService(CountingRepository()), resolveUserId = { token -> sameUser.takeIf { token.startsWith("alex-") } })
                routing { post("/v1/messages/threads/{id}/messages") { call.respond("sent") } }
            }

            // Spread over many threads so only the account-wide budget is in play.
            repeat(600) { index ->
                client.post("/v1/messages/threads/t$index/messages") { header(HttpHeaders.Authorization, "Bearer alex-session-1") }
            }
            // Signing in again (a new session token) must not hand out a fresh budget.
            val again = client.post("/v1/messages/threads/new/messages") { header(HttpHeaders.Authorization, "Bearer alex-session-2") }
            assertEquals(HttpStatusCode.TooManyRequests, again.status)
        }

    @Test
    fun adminPersonLookupsArePerAddressSoALeakedTokenCantCrawlEveryone() =
        testApplication {
            application {
                configureSerialization()
                // Ceilings off so only the lookup rule is in play.
                configureRequestLimits(RateLimitService(CountingRepository()), trustedProxyHops = 1, globalCeilings = false)
                routing { get("/v1/admin/users/{id}") { call.respond("ok") } }
            }

            repeat(300) { index ->
                assertEquals(HttpStatusCode.OK, client.get("/v1/admin/users/u$index") { header(HttpHeaders.XForwardedFor, "192.0.2.1") }.status)
            }
            assertEquals(HttpStatusCode.TooManyRequests, client.get("/v1/admin/users/next") { header(HttpHeaders.XForwardedFor, "192.0.2.1") }.status)
            assertEquals(HttpStatusCode.OK, client.get("/v1/admin/users/next") { header(HttpHeaders.XForwardedFor, "192.0.2.2") }.status)
        }

    @Test
    fun anonymousTrafficFromOneAddressHitsAGlobalCeiling() =
        testApplication {
            application {
                configureSerialization()
                configureRequestLimits(RateLimitService(CountingRepository()), trustedProxyHops = 1)
                routing {
                    get("/v1/anything") { call.respond("ok") }
                    get("/health") { call.respond("ok") }
                }
            }

            repeat(120) {
                assertEquals(HttpStatusCode.OK, client.get("/v1/anything") { header(HttpHeaders.XForwardedFor, "198.51.100.4") }.status)
            }
            assertEquals(HttpStatusCode.TooManyRequests, client.get("/v1/anything") { header(HttpHeaders.XForwardedFor, "198.51.100.4") }.status)
            assertEquals(HttpStatusCode.OK, client.get("/v1/anything") { header(HttpHeaders.XForwardedFor, "198.51.100.5") }.status)
            assertEquals(HttpStatusCode.OK, client.get("/health") { header(HttpHeaders.XForwardedFor, "198.51.100.4") }.status, "probes are exempt")
        }

    @Test
    fun signedInTrafficHitsAPerUserCeilingAndAPerAddressCeiling() =
        testApplication {
            application {
                configureSerialization()
                configureRequestLimits(RateLimitService(CountingRepository()), trustedProxyHops = 1, resolveUserId = { token -> UUID.nameUUIDFromBytes(token.toByteArray()) })
                routing { get("/v1/anything") { call.respond("ok") } }
            }

            repeat(600) {
                client.get("/v1/anything") {
                    header(HttpHeaders.Authorization, "Bearer busy")
                    header(HttpHeaders.XForwardedFor, "203.0.113.9")
                }
            }
            val busy =
                client.get("/v1/anything") {
                    header(HttpHeaders.Authorization, "Bearer busy")
                    header(HttpHeaders.XForwardedFor, "203.0.113.10")
                }
            assertEquals(HttpStatusCode.TooManyRequests, busy.status, "per user, whatever the address")

            // Many signed-in users behind one address share a much larger per-address ceiling.
            repeat(600) { index ->
                client.get("/v1/anything") {
                    header(HttpHeaders.Authorization, "Bearer user-$index")
                    header(HttpHeaders.XForwardedFor, "203.0.113.9")
                }
            }
            val crowded =
                client.get("/v1/anything") {
                    header(HttpHeaders.Authorization, "Bearer fresh")
                    header(HttpHeaders.XForwardedFor, "203.0.113.9")
                }
            assertEquals(HttpStatusCode.TooManyRequests, crowded.status)
        }
}
