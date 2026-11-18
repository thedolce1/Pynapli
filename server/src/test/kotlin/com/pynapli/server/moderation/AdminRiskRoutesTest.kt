package com.pynapli.server.moderation

import com.pynapli.server.admin.ADMIN_MFA_HEADER
import com.pynapli.server.admin.AdminTotp
import com.pynapli.server.config.ServerConfig
import com.pynapli.server.plugins.configureSerialization
import com.pynapli.server.plugins.configureStatusPages
import com.pynapli.server.testsupport.testServerConfig
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AdminRiskRoutesTest {
    @Test
    fun riskStatesRequireAdminToken() =
        testApplication {
            application {
                configureSerialization()
                configureStatusPages()
                configureAdminRiskRoutes(testConfig(), AdminRiskService(RouteAdminRiskRepository()), revokeSessions = {})
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
                configureAdminRiskRoutes(testConfig(), AdminRiskService(RouteAdminRiskRepository()), revokeSessions = {})
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
                configureAdminRiskRoutes(testConfig(), AdminRiskService(repository), revokeSessions = {})
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
                configureAdminRiskRoutes(testConfig(), AdminRiskService(repository), revokeSessions = {})
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

    @Test
    fun lockBanAndSetTierNeedASeniorAdminWithAValidMfaCode() =
        testApplication {
            val userId = UUID.fromString("00000000-0000-0000-0000-000000000923")
            val repository = RouteAdminRiskRepository(action = enforcedAction(userId))
            val revoked = mutableListOf<UUID>()
            val config =
                testConfig().copy(
                    adminRoles = mapOf("senior-1" to "senior", "operator-1" to "operator"),
                    adminMfaEnforced = true,
                    adminMfaSecrets = mapOf("senior-1" to MFA_SECRET),
                )
            application {
                configureSerialization()
                configureStatusPages()
                configureAdminRiskRoutes(config, AdminRiskService(repository), revokeSessions = { revoked += it })
            }

            for (action in listOf("lock", "ban", "set-tier")) {
                val body = if (action == "set-tier") """{"tier":"terminate","reason":"confirmed abuse"}""" else """{"reason":"confirmed abuse"}"""
                val asOperator = adminPost("/v1/admin/risk/users/$userId/$action", "operator-1", mfa = null, body)
                assertEquals(HttpStatusCode.Forbidden, asOperator.status, "$action as operator")
                assertTrue(asOperator.bodyAsText().contains("ADMIN_ROLE_REQUIRED"))

                val noCode = adminPost("/v1/admin/risk/users/$userId/$action", "senior-1", mfa = null, body)
                assertEquals(HttpStatusCode.Forbidden, noCode.status, "$action without MFA")
                assertTrue(noCode.bodyAsText().contains("ADMIN_MFA_REQUIRED"))
            }
            assertEquals(null, repository.lastEnforcementAction, "nothing ran without the step-up")
            assertEquals(null, repository.lastTier)

            val code = AdminTotp.generate(MFA_SECRET, Instant.now().epochSecond)
            assertEquals(HttpStatusCode.OK, adminPost("/v1/admin/risk/users/$userId/ban", "senior-1", code, """{"reason":"confirmed abuse"}""").status)
            assertEquals("ban", repository.lastEnforcementAction)
            assertEquals(listOf(userId), revoked, "a ban signs the account out everywhere at once")
            assertEquals(HttpStatusCode.OK, adminPost("/v1/admin/risk/users/$userId/lock", "senior-1", code, """{"reason":"confirmed abuse"}""").status)
            assertEquals(listOf(userId, userId), revoked)
        }

    private suspend fun io.ktor.server.testing.ApplicationTestBuilder.adminPost(
        path: String,
        actorId: String,
        mfa: String?,
        body: String,
    ) = client.post(path) {
        header("X-Admin-Token", "test-admin-token")
        header("X-Admin-Id", actorId)
        mfa?.let { header(ADMIN_MFA_HEADER, it) }
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    private fun enforcedAction(userId: UUID) =
        AdminRiskActionRecord(
            userId = userId,
            score = 90,
            tier = "terminate",
            lastReviewedAt = Instant.parse("2026-04-24T18:00:00Z"),
            updatedAt = Instant.parse("2026-04-24T18:00:00Z"),
            accountStatus = "banned",
        )

    private fun testConfig(): ServerConfig =
        testServerConfig().copy(
            adminApiToken = "test-admin-token",
        )

    private companion object {
        const val MFA_SECRET = "JBSWY3DPEHPK3PXP"
    }
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
