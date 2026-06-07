package com.pynapli.server.notifications

import com.pynapli.server.config.ServerConfig
import com.pynapli.server.plugins.configureSerialization
import com.pynapli.server.plugins.configureStatusPages
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import io.mockk.mockk
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AdminMetricsRoutesTest {
    @Test
    fun notificationMetricsRequireAdminToken() =
        testApplication {
            application {
                configureSerialization()
                configureStatusPages()
                configureAdminMetricsRoutes(testConfig(), workerWithMetrics(NotificationMetrics()))
            }

            val response =
                client.get("/v1/admin/metrics/notifications") {
                    header("X-Admin-Id", "ops-1")
                }

            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

    @Test
    fun notificationMetricsSnapshotReturnedForAdmin() =
        testApplication {
            val metrics = NotificationMetrics()
            metrics.record(NotificationMetrics.Outcome.SENT, "tonight_invite", "android")
            metrics.record(NotificationMetrics.Outcome.SENT, "tonight_invite", "android")
            metrics.record(NotificationMetrics.Outcome.FAILED, "tonight_invite")
            application {
                configureSerialization()
                configureStatusPages()
                configureAdminMetricsRoutes(testConfig(), workerWithMetrics(metrics))
            }

            val response =
                client.get("/v1/admin/metrics/notifications") {
                    header("X-Admin-Token", "test-admin-token")
                    header("X-Admin-Id", "ops-1")
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("\"totalSent\":2"), body)
            assertTrue(body.contains("\"totalFailed\":1"), body)
            assertTrue(body.contains("tonight_invite"), body)
        }

    private fun workerWithMetrics(metrics: NotificationMetrics): NotificationDispatchWorker {
        val service =
            NotificationDispatchService(
                repository = mockk(relaxed = true),
                sender = mockk(relaxed = true),
                metrics = metrics,
            )
        return NotificationDispatchWorker(service, intervalMillis = 60_000, batchSize = 10)
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
