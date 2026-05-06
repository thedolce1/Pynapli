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

class AdminReportRoutesTest {
    @Test
    fun reportsQueueRequiresAdminToken() = testApplication {
        application {
            configureSerialization()
            configureStatusPages()
            configureAdminReportRoutes(testConfig(), AdminReportService(RouteAdminReportRepository()))
        }

        val response = client.get("/v1/admin/reports") {
            header("X-Admin-Id", "moderator-1")
            header("X-Admin-Reason", "queue review")
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun reportsQueueRequiresReasonForSensitiveList() = testApplication {
        application {
            configureSerialization()
            configureStatusPages()
            configureAdminReportRoutes(testConfig(), AdminReportService(RouteAdminReportRepository()))
        }

        val response = client.get("/v1/admin/reports") {
            header("X-Admin-Token", "test-admin-token")
            header("X-Admin-Id", "moderator-1")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun resolveReportUsesProtectedAdminRoute() = testApplication {
        val reportId = UUID.fromString("00000000-0000-0000-0000-000000000911")
        val repository = RouteAdminReportRepository(
            action = AdminReportActionRecord(
                reportId = reportId,
                status = "resolved",
                actedAt = Instant.parse("2026-04-24T18:00:00Z")
            )
        )
        application {
            configureSerialization()
            configureStatusPages()
            configureAdminReportRoutes(testConfig(), AdminReportService(repository))
        }

        val response = client.post("/v1/admin/reports/$reportId/resolve") {
            header("X-Admin-Token", "test-admin-token")
            header("X-Admin-Id", "moderator-1")
            contentType(ContentType.Application.Json)
            setBody("""{"reason":"action taken"}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("moderator-1", repository.lastActorId)
        assertEquals("action taken", repository.lastReason)
    }

    private fun testConfig(): ServerConfig = ServerConfig(
        environment = "test",
        port = 8080,
        databaseUrl = "jdbc:postgresql://localhost:5432/pynapli_test",
        databaseUser = "pynapli",
        databasePassword = "pynapli_dev_password",
        redisUrl = "redis://localhost:6379",
        adminApiToken = "test-admin-token"
    )
}

private class RouteAdminReportRepository(
    private val action: AdminReportActionRecord? = null
) : AdminReportRepository {
    var lastActorId: String? = null
    var lastReason: String? = null

    override suspend fun listReports(
        status: String,
        limit: Int,
        actorId: String,
        reason: String
    ): List<AdminReportRecord> {
        lastActorId = actorId
        lastReason = reason
        return emptyList()
    }

    override suspend fun updateReportStatus(
        reportId: UUID,
        status: String,
        actorId: String,
        reason: String
    ): AdminReportActionRecord? {
        lastActorId = actorId
        lastReason = reason
        return action
    }
}
