package com.pynapli.server.admin

import com.pynapli.server.config.ServerConfig
import com.pynapli.server.plugins.configureSerialization
import com.pynapli.server.plugins.configureStatusPages
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AdminDashboardRoutesTest {
    @Test
    fun dashboardRequiresAdminToken() =
        testApplication {
            application {
                configureSerialization()
                configureStatusPages()
                configureAdminDashboardRoutes(testConfig(), service())
            }

            val response =
                client.get("/v1/admin/dashboard") {
                    header("X-Admin-Id", "ops-1")
                }

            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

    @Test
    fun dashboardReturnsBacklogCounts() =
        testApplication {
            application {
                configureSerialization()
                configureStatusPages()
                configureAdminDashboardRoutes(testConfig(), service())
            }

            val response =
                client.get("/v1/admin/dashboard") {
                    header("X-Admin-Token", "test-admin-token")
                    header("X-Admin-Id", "ops-1")
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("\"reportsOpen\":3"), body)
            assertTrue(body.contains("\"appealsEscalated\":1"), body)
            assertTrue(body.contains("\"heldMessagesOpen\":4"), body)
            assertTrue(body.contains("\"riskQuarantine\":1"), body)
        }

    @Test
    fun routesAreNotInstalledWithoutConfiguredToken() =
        testApplication {
            application {
                configureSerialization()
                configureStatusPages()
                configureAdminDashboardRoutes(testConfig(adminToken = null), service())
            }

            val response =
                client.get("/v1/admin/dashboard") {
                    header("X-Admin-Token", "test-admin-token")
                    header("X-Admin-Id", "ops-1")
                }

            assertEquals(HttpStatusCode.NotFound, response.status)
        }

    private fun service(): AdminDashboardService = AdminDashboardService(FakeDashboardRepository())

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

private class FakeDashboardRepository : AdminDashboardRepository {
    override suspend fun counts(): AdminDashboardCounts =
        AdminDashboardCounts(
            reportsOpen = 3,
            reportsUnderReview = 1,
            appealsOpen = 2,
            appealsUnderReview = 0,
            appealsEscalated = 1,
            heldMessagesOpen = 4,
            riskWatch = 5,
            riskFriction = 2,
            riskQuarantine = 1,
            riskTerminate = 0,
        )
}
