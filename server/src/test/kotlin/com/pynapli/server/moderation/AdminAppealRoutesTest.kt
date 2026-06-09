package com.pynapli.server.moderation

import com.pynapli.server.config.ServerConfig
import com.pynapli.server.plugins.configureSerialization
import com.pynapli.server.plugins.configureStatusPages
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import org.junit.Test
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals

class AdminAppealRoutesTest {
    @Test
    fun appealsQueueRequiresAdminToken() =
        testApplication {
            application {
                configureSerialization()
                configureStatusPages()
                configureAdminAppealRoutes(testConfig(), AdminAppealService(RouteAdminAppealRepository()))
            }

            val response =
                client.get("/v1/admin/appeals") {
                    header("X-Admin-Id", "senior-mod-1")
                    header("X-Admin-Reason", "appeal queue")
                }

            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

    @Test
    fun appealsQueueRequiresReasonForSensitiveList() =
        testApplication {
            application {
                configureSerialization()
                configureStatusPages()
                configureAdminAppealRoutes(testConfig(), AdminAppealService(RouteAdminAppealRepository()))
            }

            val response =
                client.get("/v1/admin/appeals") {
                    header("X-Admin-Token", "test-admin-token")
                    header("X-Admin-Id", "senior-mod-1")
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
        }

    @Test
    fun decideAppealUsesProtectedAdminRoute() =
        testApplication {
            val appealId = UUID.fromString("00000000-0000-0000-0000-00000000a911")
            val userId = UUID.fromString("00000000-0000-0000-0000-00000000a912")
            val repository =
                RouteAdminAppealRepository(
                    action =
                        AdminAppealActionRecord(
                            appealId = appealId,
                            userId = userId,
                            status = "reduced",
                            actedAt = Instant.parse("2026-05-03T12:00:00Z"),
                        ),
                )
            application {
                configureSerialization()
                configureStatusPages()
                configureAdminAppealRoutes(testConfig(), AdminAppealService(repository))
            }

            val response =
                client.post("/v1/admin/appeals/$appealId/decide") {
                    header("X-Admin-Token", "test-admin-token")
                    header("X-Admin-Id", "senior-mod-1")
                    contentType(ContentType.Application.Json)
                    setBody("""{"outcome":"reduced","reason":"false positive","adminNote":"Reduced restriction."}""")
                }

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("senior-mod-1", repository.lastActorId)
            assertEquals("false positive", repository.lastReason)
            assertEquals("Reduced restriction.", repository.lastAdminNote)
        }

    private fun testConfig(): ServerConfig =
        ServerConfig(
            environment = "test",
            port = 8080,
            databaseUrl = "jdbc:postgresql://localhost:5432/pynapli_test",
            databaseUser = "pynapli",
            databasePassword = "pynapli_dev_password",
            redisUrl = "redis://localhost:6379",
            adminApiToken = "test-admin-token",
        )
}

private class RouteAdminAppealRepository(
    private val action: AdminAppealActionRecord? = null,
) : AdminAppealRepository {
    var lastActorId: String? = null
    var lastReason: String? = null
    var lastAdminNote: String? = null

    override suspend fun listAppeals(
        status: String,
        limit: Int,
        actorId: String,
        reason: String,
    ): List<AdminAppealRecord> {
        lastActorId = actorId
        lastReason = reason
        return emptyList()
    }

    override suspend fun markUnderReview(
        appealId: UUID,
        actorId: String,
        reason: String,
    ): AdminAppealActionRecord? {
        lastActorId = actorId
        lastReason = reason
        return action
    }

    override suspend fun decideAppeal(
        appealId: UUID,
        status: String,
        actorId: String,
        reason: String,
        adminNote: String?,
    ): AdminAppealActionRecord? {
        lastActorId = actorId
        lastReason = reason
        lastAdminNote = adminNote
        return action
    }
}
