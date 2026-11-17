package com.pynapli.server.subscriptions

import com.pynapli.server.config.ServerConfig
import com.pynapli.server.plugins.configureSerialization
import com.pynapli.server.plugins.configureStatusPages
import com.pynapli.server.testsupport.testServerConfig
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import java.time.Instant
import java.util.Base64
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class SubscriptionAdminRoutesTest {
    @Test
    fun playStoreSyncRequiresAdminToken() =
        testApplication {
            application {
                configureSerialization()
                configureStatusPages()
                configureSubscriptionAdminRoutes(testConfig(), SubscriptionService(RouteSubscriptionRepository()))
            }

            val response =
                client.post("/v1/admin/subscriptions/play-store/sync") {
                    header("X-Admin-Id", "billing-ops")
                }

            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

    @Test
    fun playStoreSyncRunsProtectedAdminRoute() =
        testApplication {
            application {
                configureSerialization()
                configureStatusPages()
                configureSubscriptionAdminRoutes(testConfig(), SubscriptionService(RouteSubscriptionRepository()))
            }

            val response =
                client.post("/v1/admin/subscriptions/play-store/sync") {
                    header("X-Admin-Token", "test-admin-token")
                    header("X-Admin-Id", "billing-ops")
                }

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(0, response.decodeBody<PlayStoreSubscriptionSyncSummaryDto>().scanned)
        }

    @Test
    fun grantEntitlementRequiresAdminReason() =
        testApplication {
            val user = uuid(101)
            application {
                configureSerialization()
                configureStatusPages()
                configureSubscriptionAdminRoutes(testConfig(), SubscriptionService(RouteSubscriptionRepository(validUsers = setOf(user))))
            }

            val response =
                client.post("/v1/admin/subscriptions/entitlements") {
                    header("X-Admin-Token", "test-admin-token")
                    header("X-Admin-Id", "billing-ops")
                    contentType(ContentType.Application.Json)
                    setBody("""{"userId":"$user","tier":"select","source":"admin"}""")
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
        }

    @Test
    fun grantListAndRevokeEntitlementUseProtectedAdminRoutes() =
        testApplication {
            val user = uuid(102)
            val repository = RouteSubscriptionRepository(validUsers = setOf(user))
            application {
                configureSerialization()
                configureStatusPages()
                configureSubscriptionAdminRoutes(testConfig(), SubscriptionService(repository))
            }

            val grant =
                client.post("/v1/admin/subscriptions/entitlements") {
                    header("X-Admin-Token", "test-admin-token")
                    header("X-Admin-Id", "billing-ops")
                    header("X-Admin-Reason", "launch comp")
                    contentType(ContentType.Application.Json)
                    setBody(
                        """
                        {
                          "userId":"$user",
                          "tier":"select",
                          "source":"admin",
                          "startsAt":"2026-05-07T00:00:00Z",
                          "endsAt":"2026-06-07T00:00:00Z"
                        }
                        """.trimIndent(),
                    )
                }

            assertEquals(HttpStatusCode.OK, grant.status)
            val granted = grant.decodeBody<AdminEntitlementDto>()
            assertEquals("select", granted.tier)
            assertEquals("active", granted.status)

            val list =
                client.get("/v1/admin/subscriptions/users/$user/entitlements") {
                    header("X-Admin-Token", "test-admin-token")
                    header("X-Admin-Id", "billing-ops")
                }
            assertEquals(HttpStatusCode.OK, list.status)
            assertEquals(1, list.decodeBody<AdminEntitlementListDto>().entitlements.size)

            val revoke =
                client.post("/v1/admin/subscriptions/entitlements/${granted.entitlementId}/revoke") {
                    header("X-Admin-Token", "test-admin-token")
                    header("X-Admin-Id", "billing-ops")
                    header("X-Admin-Reason", "support correction")
                }
            assertEquals(HttpStatusCode.OK, revoke.status)
            assertEquals("revoked", revoke.decodeBody<AdminEntitlementDto>().status)
        }

    @Test
    fun rtdnWebhookRequiresConfiguredToken() =
        testApplication {
            application {
                configureSerialization()
                configureStatusPages()
                configurePlayStoreRtdnRoutes(testConfig(rtdnEnabled = true), SubscriptionService(RouteSubscriptionRepository()))
            }

            val response =
                client.post("/v1/webhooks/play-store/rtdn") {
                    contentType(ContentType.Application.Json)
                    setBody(pubSubBody(testRtdnPayload()))
                }

            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

    @Test
    fun rtdnWebhookProcessesTestNotification() =
        testApplication {
            application {
                configureSerialization()
                configureStatusPages()
                configurePlayStoreRtdnRoutes(testConfig(rtdnEnabled = true), SubscriptionService(RouteSubscriptionRepository()))
            }

            val response =
                client.post("/v1/webhooks/play-store/rtdn?token=test-rtdn-token") {
                    contentType(ContentType.Application.Json)
                    setBody(pubSubBody(testRtdnPayload()))
                }

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("processed", response.decodeBody<PlayStoreRtdnProcessingResultDto>().status)
        }

    private fun testConfig(rtdnEnabled: Boolean = false): ServerConfig =
        testServerConfig().copy(
            playStoreRtdnEnabled = rtdnEnabled,
            playStoreRtdnWebhookToken = "test-rtdn-token",
            adminApiToken = "test-admin-token",
        )

    private fun pubSubBody(payload: String): String {
        val encoded = Base64.getEncoder().encodeToString(payload.toByteArray(Charsets.UTF_8))
        return """{"message":{"messageId":"route-rtdn-message","data":"$encoded"}}"""
    }

    private fun testRtdnPayload(): String = """{"version":"1.0","packageName":"com.pynapli.app","eventTimeMillis":"1770000000000","testNotification":{}}"""
}

private suspend inline fun <reified T> HttpResponse.decodeBody(): T = Json.decodeFromString(bodyAsText())

private class RouteSubscriptionRepository(
    private val validUsers: Set<UUID> = emptySet(),
) : SubscriptionRepository {
    private val entitlements = mutableMapOf<UUID, StoredEntitlementRecord>()

    override suspend fun findBestActiveOwnSubscription(
        userId: UUID,
        now: Instant,
    ): SubscriptionRecord? = null

    override suspend fun findBestActiveLinkedPartnerSubscription(
        userId: UUID,
        now: Instant,
    ): LinkedPartnerSubscriptionRecord? = null

    override suspend fun findBestActiveStoredEntitlement(
        userId: UUID,
        now: Instant,
    ): StoredEntitlementRecord? = null

    override suspend fun listStoredEntitlementsForUser(
        userId: UUID,
        limit: Int,
    ): List<StoredEntitlementRecord> = entitlements.values.filter { it.userId == userId }.take(limit)

    override suspend fun grantStoredEntitlement(
        userId: UUID,
        tier: String,
        source: String,
        startsAt: Instant,
        endsAt: Instant?,
        actorId: String,
        reason: String,
    ): StoredEntitlementRecord? {
        if (userId !in validUsers) return null
        val record =
            StoredEntitlementRecord(
                entitlementId = UUID.randomUUID(),
                userId = userId,
                tier = tier,
                source = source,
                sourceId = null,
                startsAt = startsAt,
                endsAt = endsAt,
                status = "active",
            )
        entitlements[record.entitlementId] = record
        return record
    }

    override suspend fun revokeStoredEntitlement(
        entitlementId: UUID,
        actorId: String,
        reason: String,
    ): StoredEntitlementRecord? {
        val current = entitlements[entitlementId]?.takeIf { it.status == "active" } ?: return null
        val revoked = current.copy(status = "revoked")
        entitlements[entitlementId] = revoked
        return revoked
    }
}

private fun uuid(value: Int): UUID = UUID.fromString("00000000-0000-0000-0000-${value.toString().padStart(12, '0')}")
