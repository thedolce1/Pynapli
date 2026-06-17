package com.pynapli.server.admin

import com.pynapli.server.config.ServerConfig
import com.pynapli.server.plugins.configureSerialization
import com.pynapli.server.plugins.configureStatusPages
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.junit.Test
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AdminPartnerLinkReviewRoutesTest {
    private val userId = UUID.fromString("00000000-0000-0000-0000-000000000701")

    @Test
    fun reviewRequiresAdminToken() =
        testApplication {
            application {
                configureSerialization()
                configureStatusPages()
                configureAdminPartnerLinkReviewRoutes(testConfig(), service())
            }

            val response =
                client.get("/v1/admin/partner-links/$userId") {
                    header("X-Admin-Id", "ops-1")
                    header("X-Admin-Reason", "support review")
                }

            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

    @Test
    fun reviewRequiresReason() =
        testApplication {
            application {
                configureSerialization()
                configureStatusPages()
                configureAdminPartnerLinkReviewRoutes(testConfig(), service())
            }

            val response =
                client.get("/v1/admin/partner-links/$userId") {
                    header("X-Admin-Token", "test-admin-token")
                    header("X-Admin-Id", "ops-1")
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
        }

    @Test
    fun reviewReturnsPartnerLinkFactsAndAudits() =
        testApplication {
            val repository = RoutePartnerLinkReviewRepository(facts = reviewFacts())
            application {
                configureSerialization()
                configureStatusPages()
                configureAdminPartnerLinkReviewRoutes(
                    config = testConfig(),
                    service = AdminPartnerLinkReviewService(repository),
                )
            }

            val response =
                client.get("/v1/admin/partner-links/$userId") {
                    header("X-Admin-Token", "test-admin-token")
                    header("X-Admin-Id", "ops-1")
                    header("X-Admin-Reason", "support review")
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("\"userId\":\"$userId\""), body)
            assertTrue(body.contains("\"partnerVisibility\":\"verified_only\""), body)
            assertTrue(body.contains("\"activeLinkCount\":1"), body)
            assertEquals(Triple("ops-1", userId, "support review"), repository.audited)
        }

    @Test
    fun routesAreNotInstalledWithoutConfiguredToken() =
        testApplication {
            application {
                configureSerialization()
                configureStatusPages()
                configureAdminPartnerLinkReviewRoutes(testConfig(adminToken = null), service())
            }

            val response =
                client.get("/v1/admin/partner-links/$userId") {
                    header("X-Admin-Token", "test-admin-token")
                    header("X-Admin-Id", "ops-1")
                    header("X-Admin-Reason", "support review")
                }

            assertEquals(HttpStatusCode.NotFound, response.status)
        }

    private fun service(): AdminPartnerLinkReviewService = AdminPartnerLinkReviewService(RoutePartnerLinkReviewRepository(facts = reviewFacts()))

    private fun reviewFacts(): AdminPartnerLinkReviewFacts =
        AdminPartnerLinkReviewFacts(
            partnerVisibility = "verified_only",
            links =
                listOf(
                    AdminPartnerLinkFact(
                        linkId = UUID.fromString("00000000-0000-0000-0000-000000000702"),
                        otherUserId = UUID.fromString("00000000-0000-0000-0000-000000000703"),
                        status = "active",
                        direction = "received",
                        createdAt = Instant.parse("2026-06-01T00:00:00Z"),
                        acceptedAt = Instant.parse("2026-06-01T01:00:00Z"),
                        unlinkedAt = null,
                    ),
                ),
            reportsAgainstUserCount = 0,
            reportsAgainstCurrentPartnerCount = 0,
        )

    private fun testConfig(adminToken: String? = "test-admin-token"): ServerConfig =
        ServerConfig(
            environment = "test",
            port = 8080,
            databaseUrl = "jdbc:postgresql://localhost:5432/pynapli_test",
            databaseUser = "pynapli",
            databasePassword = "pynapli_dev_password",
            redisUrl = "redis://localhost:6379",
            adminApiToken = adminToken,
        )
}

private class RoutePartnerLinkReviewRepository(
    private val facts: AdminPartnerLinkReviewFacts?,
) : AdminPartnerLinkReviewRepository {
    var audited: Triple<String, UUID, String>? = null

    override suspend fun review(userId: UUID): AdminPartnerLinkReviewFacts? = facts

    override suspend fun recordReviewAudit(
        actorId: String,
        userId: UUID,
        reason: String,
    ) {
        audited = Triple(actorId, userId, reason)
    }
}
