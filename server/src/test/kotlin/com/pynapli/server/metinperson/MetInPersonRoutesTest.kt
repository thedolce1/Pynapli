package com.pynapli.server.metinperson

import com.pynapli.server.auth.AuthService
import com.pynapli.server.auth.SessionRecord
import com.pynapli.server.auth.security.Sha256
import com.pynapli.server.contactblocks.ContactBlockTokens
import com.pynapli.server.contactblocks.JdbcContactBlockVisibility
import com.pynapli.server.plugins.configureSerialization
import com.pynapli.server.plugins.configureStatusPages
import com.pynapli.server.plugins.monitoringLogLine
import com.pynapli.server.testsupport.PynapliTestDatabase
import com.pynapli.server.testsupport.SingleSessionAuthRepository
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The member routes carry nothing identifying in the path: conversation, person and action are in the
 * body, so request logs (which record method and path) cannot pin who confirmed whom. Every refusal,
 * including a malformed body or an unknown action, is the same `MET_IN_PERSON_NOT_AVAILABLE`.
 */
class MetInPersonRoutesTest {
    private val dataSource = PynapliTestDatabase.dataSource
    private val clock = MetInPersonTestClock(Instant.parse("2026-11-18T12:00:00Z"))
    private val tokens = MetInPersonTokens("met-in-person-routes-key-0123456789")
    private val fx = MetInPersonFixtures(dataSource, clock)
    private val service =
        MetInPersonService(
            dataSource,
            tokens,
            JdbcContactBlockVisibility(dataSource, ContactBlockTokens.localDevelopment()),
            clock,
        )

    @BeforeTest
    fun reset() {
        PynapliTestDatabase.reset()
    }

    private fun authFor(userId: UUID): AuthService {
        val now = Instant.now()
        return AuthService(
            SingleSessionAuthRepository(
                SessionRecord(
                    id = UUID.randomUUID(),
                    userId = userId,
                    accessTokenHash = Sha256.hash(ACCESS_TOKEN),
                    refreshTokenHash = Sha256.hash("refresh-$ACCESS_TOKEN"),
                    accessExpiresAt = now.plus(1, ChronoUnit.HOURS),
                    refreshExpiresAt = now.plus(30, ChronoUnit.DAYS),
                    revokedAt = null,
                    userStatus = "active",
                ),
            ),
        )
    }

    private suspend fun HttpClient.postJson(
        path: String,
        json: String,
    ): HttpResponse =
        post(path) {
            bearerAuth(ACCESS_TOKEN)
            contentType(ContentType.Application.Json)
            setBody(json)
        }

    @Test
    fun confirmAndTakeBackTravelInTheBody() =
        testApplication {
            val voucher = fx.member("Alex")
            val subject = fx.member("Zephyrine Hidewell")
            val chat = fx.talked(voucher, subject)
            application {
                configureSerialization()
                configureStatusPages()
                configureMetInPersonRoutes(authFor(voucher), service)
            }

            val offers = client.postJson("/v1/met-in-person/offers", """{"threadId":"$chat"}""")
            assertEquals(HttpStatusCode.OK, offers.status, offers.bodyAsText())
            assertTrue(offers.bodyAsText().contains("\"canConfirm\":true"))

            val confirm =
                client.postJson(
                    "/v1/met-in-person/confirmations",
                    """{"threadId":"$chat","subjectUserId":"$subject","action":"confirm"}""",
                )
            assertEquals(HttpStatusCode.OK, confirm.status, confirm.bodyAsText())
            assertTrue(confirm.bodyAsText().contains("\"given\":true"))

            val takeBack =
                client.postJson(
                    "/v1/met-in-person/confirmations",
                    """{"threadId":"$chat","subjectUserId":"$subject","action":"withdraw"}""",
                )
            assertEquals(HttpStatusCode.OK, takeBack.status, takeBack.bodyAsText())
            assertFalse(takeBack.bodyAsText().contains("Zephyrine"), "a take-back answer never carries a name")

            // The old per-conversation paths are gone.
            val oldPath = client.get("/v1/messages/threads/$chat/met-in-person") { bearerAuth(ACCESS_TOKEN) }
            assertEquals(HttpStatusCode.NotFound, oldPath.status)
            assertEquals(
                "200: POST /v1/met-in-person/confirmations",
                monitoringLogLine("POST", "/v1/met-in-person/confirmations", 200),
                "the request log line names no conversation and no person",
            )
        }

    @Test
    fun malformedUnknownAndNothingToTakeBackAllAnswerTheSame() =
        testApplication {
            val voucher = fx.member("Alex")
            val subject = fx.member("Sam")
            val chat = fx.talked(voucher, subject)
            application {
                configureSerialization()
                configureStatusPages()
                configureMetInPersonRoutes(authFor(voucher), service)
            }
            val unknownThread =
                client.postJson(
                    "/v1/met-in-person/confirmations",
                    """{"threadId":"${UUID.randomUUID()}","subjectUserId":"$subject","action":"confirm"}""",
                )
            val refusal = unknownThread.bodyAsText()
            assertEquals(HttpStatusCode.Forbidden, unknownThread.status, refusal)
            assertTrue(refusal.contains(MetInPersonService.NOT_AVAILABLE))

            listOf(
                client.postJson("/v1/met-in-person/offers", "not json"),
                client.postJson("/v1/met-in-person/offers", "{}"),
                client.postJson(
                    "/v1/met-in-person/confirmations",
                    """{"threadId":"$chat","subjectUserId":"$subject","action":"something"}""",
                ),
                client.postJson(
                    "/v1/met-in-person/confirmations",
                    """{"threadId":"$chat","subjectUserId":"$subject","action":"withdraw"}""",
                ),
            ).forEach { response ->
                assertEquals(HttpStatusCode.Forbidden, response.status)
                assertTrue(response.bodyAsText().contains(MetInPersonService.NOT_AVAILABLE))
            }
        }

    private companion object {
        const val ACCESS_TOKEN = "met-in-person-routes-access-token"
    }
}
