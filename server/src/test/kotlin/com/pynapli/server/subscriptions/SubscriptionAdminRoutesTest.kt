package com.pynapli.server.subscriptions

import com.pynapli.server.config.ServerConfig
import com.pynapli.server.plugins.configureSerialization
import com.pynapli.server.plugins.configureStatusPages
import io.ktor.client.call.body
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import org.junit.Test
import java.time.Instant
import java.util.Base64
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

    @Test
    fun rtdnWebhookRequiresConfiguredToken() = testApplication {
        application {
            configureSerialization()
            configureStatusPages()
            configurePlayStoreRtdnRoutes(testConfig(rtdnEnabled = true), SubscriptionService(RouteSubscriptionRepository()))
        }

        val response = client.post("/v1/webhooks/play-store/rtdn") {
            contentType(ContentType.Application.Json)
            setBody(pubSubBody(testRtdnPayload()))
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun rtdnWebhookProcessesTestNotification() = testApplication {
        application {
            configureSerialization()
            configureStatusPages()
            configurePlayStoreRtdnRoutes(testConfig(rtdnEnabled = true), SubscriptionService(RouteSubscriptionRepository()))
        }

        val response = client.post("/v1/webhooks/play-store/rtdn?token=test-rtdn-token") {
            contentType(ContentType.Application.Json)
            setBody(pubSubBody(testRtdnPayload()))
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("processed", response.body<PlayStoreRtdnProcessingResultDto>().status)
    }

    private fun testConfig(rtdnEnabled: Boolean = false): ServerConfig = ServerConfig(
        environment = "test",
        port = 8080,
        databaseUrl = "jdbc:postgresql://localhost:5432/pynapli_test",
        databaseUser = "pynapli",
        databasePassword = "pynapli_dev_password",
        redisUrl = "redis://localhost:6379",
        playStoreRtdnEnabled = rtdnEnabled,
        playStoreRtdnWebhookToken = "test-rtdn-token",
        adminApiToken = "test-admin-token"
    )

    private fun pubSubBody(payload: String): String {
        val encoded = Base64.getEncoder().encodeToString(payload.toByteArray(Charsets.UTF_8))
        return """{"message":{"messageId":"route-rtdn-message","data":"$encoded"}}"""
    }

    private fun testRtdnPayload(): String =
        """{"version":"1.0","packageName":"com.pynapli.app","eventTimeMillis":"1770000000000","testNotification":{}}"""
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
