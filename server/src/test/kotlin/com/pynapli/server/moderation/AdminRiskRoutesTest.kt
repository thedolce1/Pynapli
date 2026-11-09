package com.pynapli.server.moderation

import com.pynapli.server.config.ServerConfig
import com.pynapli.server.plugins.configureSerialization
import com.pynapli.server.plugins.configureStatusPages
import com.pynapli.server.testsupport.testServerConfig
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class AdminRiskRoutesTest {
    @Test
    fun riskStatesRequireAdminToken() =
        testApplication {
            application {
                configureSerialization()
                configureStatusPages()
                configureAdminRiskRoutes(testConfig(), AdminRiskService(RouteAdminRiskRepository()))
            }

            val response =
                client.get("/v1/admin/risk/states") {
                    header("X-Admin-Id", "moderator-1")
                    header("X-Admin-Reason", "risk review")
                }

            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

    @Test
    fun riskEventsRequireReasonForSensitiveList() =
        testApplication {
            val userId = UUID.randomUUID()
            application {
                configureSerialization()
                configureStatusPages()
                configureAdminRiskRoutes(testConfig(), AdminRiskService(RouteAdminRiskRepository()))
            }

            val response =
                client.get("/v1/admin/risk/users/$userId/events") {
                    header("X-Admin-Token", "test-admin-token")
                    header("X-Admin-Id", "moderator-1")
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
        }

    @Test
    fun setTierUsesProtectedAdminRoute() =
        testApplication {
            val userId = UUID.fromString("00000000-0000-0000-0000-000000000921")
            val repository =
                RouteAdminRiskRepository(
                    action =
                        AdminRiskActionRecord(
                            userId = userId,
                            score = 70,
                            tier = "quarantine",
                            lastReviewedAt = Instant.parse("2026-04-24T18:00:00Z"),
                            updatedAt = Instant.parse("2026-04-24T18:00:00Z"),
                        ),
                )
            application {
                configureSerialization()
                configureStatusPages()
                configureAdminRiskRoutes(testConfig(), AdminRiskService(repository))
            }

            val response =
                client.post("/v1/admin/risk/users/$userId/set-tier") {
                    header("X-Admin-Token", "test-admin-token")
                    header("X-Admin-Id", "moderator-1")
                    contentType(ContentType.Application.Json)
                    setBody("""{"tier":"quarantine","reason":"confirmed spam behavior"}""")
                }

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("quarantine", repository.lastTier)
            assertEquals("moderator-1", repository.lastActorId)
            assertEquals("confirmed spam behavior", repository.lastReason)
        }

    @Test
    fun lockAccountUsesProtectedAdminRoute() =
        testApplication {
            val userId = UUID.fromString("00000000-0000-0000-0000-000000000922")
            val repository =
                RouteAdminRiskRepository(
                    action =
                        AdminRiskActionRecord(
                            userId = userId,
                            score = 90,
                            tier = "terminate",
                            lastReviewedAt = Instant.parse("2026-04-24T18:00:00Z"),
                            updatedAt = Instant.parse("2026-04-24T18:00:00Z"),
                            accountStatus = "suspended",
                        ),
                )
            application {
                configureSerialization()
                configureStatusPages()
                configureAdminRiskRoutes(testConfig(), AdminRiskService(repository))
            }

            val response =
                client.post("/v1/admin/risk/users/$userId/lock") {
                    header("X-Admin-Token", "test-admin-token")
                    header("X-Admin-Id", "moderator-1")
                    contentType(ContentType.Application.Json)
                    setBody("""{"reason":"confirmed severe spam"}""")
                }

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("lock", repository.lastEnforcementAction)
            assertEquals("moderator-1", repository.lastActorId)
            assertEquals("confirmed severe spam", repository.lastReason)
        }

    private fun testConfig(): ServerConfig =
        testServerConfig().copy(
            adminApiToken = "test-admin-token",
        )
}

private class RouteAdminRiskRepository(
    private val action: AdminRiskActionRecord? = null,
) : AdminRiskRepository {
    var lastTier: String? = null
    var lastActorId: String? = null
    var lastReason: String? = null
    var lastEnforcementAction: String? = null

    override suspend fun listRiskStates(
        tierFilter: String,
        limit: Int,
        actorId: String,
        reason: String,
    ): List<AdminRiskStateRecord> {
        lastTier = tierFilter
        lastActorId = actorId
        lastReason = reason
        return emptyList()
    }

    override suspend fun listRiskEvents(
        userId: UUID,
        limit: Int,
        actorId: String,
        reason: String,
    ): List<AdminRiskEventRecord> {
        lastActorId = actorId
        lastReason = reason
        return emptyList()
    }

    override suspend fun setRiskTier(
        userId: UUID,
        tier: String,
        actorId: String,
        reason: String,
    ): AdminRiskActionRecord? {
        lastTier = tier
        lastActorId = actorId
        lastReason = reason
        return action
    }

    override suspend fun markRiskReviewed(
        userId: UUID,
        actorId: String,
        reason: String,
    ): AdminRiskActionRecord? {
        lastActorId = actorId
        lastReason = reason
        return action
    }

    override suspend fun lockAccount(
        userId: UUID,
        actorId: String,
        reason: String,
    ): AdminRiskActionRecord? {
        lastEnforcementAction = "lock"
        lastActorId = actorId
        lastReason = reason
        return action
    }

    override suspend fun banAccount(
        userId: UUID,
        actorId: String,
        reason: String,
    ): AdminRiskActionRecord? {
        lastEnforcementAction = "ban"
        lastActorId = actorId
        lastReason = reason
        return action
    }
}
