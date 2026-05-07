package com.pynapli.server.subscriptions

import com.pynapli.server.config.ServerConfig
import com.pynapli.server.plugins.configureSerialization
import com.pynapli.server.plugins.configureStatusPages
import io.ktor.client.call.body
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.junit.Test
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals

class SubscriptionAdminRoutesTest {
    @Test
    fun playStoreSyncRequiresAdminToken() = testApplication {
        application {
            configureSerialization()
            configureStatusPages()
            configureSubscriptionAdminRoutes(testConfig(), SubscriptionService(RouteSubscriptionRepository()))
        }

        val response = client.post("/v1/admin/subscriptions/play-store/sync") {
            header("X-Admin-Id", "billing-ops")
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun playStoreSyncRunsProtectedAdminRoute() = testApplication {
        application {
            configureSerialization()
            configureStatusPages()
            configureSubscriptionAdminRoutes(testConfig(), SubscriptionService(RouteSubscriptionRepository()))
        }

        val response = client.post("/v1/admin/subscriptions/play-store/sync") {
            header("X-Admin-Token", "test-admin-token")
            header("X-Admin-Id", "billing-ops")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(0, response.body<PlayStoreSubscriptionSyncSummaryDto>().scanned)
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

private class RouteSubscriptionRepository : SubscriptionRepository {
    override suspend fun findBestActiveOwnSubscription(userId: UUID, now: Instant): SubscriptionRecord? = null

    override suspend fun findBestActiveLinkedPartnerSubscription(
        userId: UUID,
        now: Instant
    ): LinkedPartnerSubscriptionRecord? = null

    override suspend fun findBestActiveStoredEntitlement(userId: UUID, now: Instant): StoredEntitlementRecord? = null

    override suspend fun consumeIntroNoteInventory(userId: UUID, now: Instant): Boolean = false
}
