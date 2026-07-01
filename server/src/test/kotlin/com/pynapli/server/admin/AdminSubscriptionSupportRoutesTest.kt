package com.pynapli.server.admin

import com.pynapli.server.config.ServerConfig
import com.pynapli.server.plugins.configureSerialization
import com.pynapli.server.plugins.configureStatusPages
import com.pynapli.shared.network.dto.SubscriptionEntitlementDto
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

class AdminSubscriptionSupportRoutesTest {
    private val userId = UUID.fromString("00000000-0000-0000-0000-000000000801")
    private val mfaSecret = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"

    @Test
    fun viewRequiresAdminToken() =
        testApplication {
            application {
                configureSerialization()
                configureStatusPages()
                configureAdminSubscriptionSupportRoutes(testConfig(), service())
            }

            val response =
                client.get("/v1/admin/subscriptions/support/$userId") {
                    header("X-Admin-Id", "ops-1")
                    header("X-Admin-Reason", "billing")
                }

            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

    @Test
    fun viewRequiresReason() =
        testApplication {
            application {
                configureSerialization()
                configureStatusPages()
                configureAdminSubscriptionSupportRoutes(testConfig(), service())
            }

            val response =
                client.get("/v1/admin/subscriptions/support/$userId") {
                    header("X-Admin-Token", "test-admin-token")
                    header("X-Admin-Id", "ops-1")
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
        }

    @Test
    fun viewReturnsSupportFactsAndAudits() =
        testApplication {
            val repository = RouteSubscriptionSupportRepository(storedFacts = facts())
            application {
                configureSerialization()
                configureStatusPages()
                configureAdminSubscriptionSupportRoutes(
                    config = testConfig(),
                    service =
                        AdminSubscriptionSupportService(
                            repository = repository,
                            entitlementLookup = { entitlement() },
                        ),
                )
            }

            val response =
                client.get("/v1/admin/subscriptions/support/$userId") {
                    header("X-Admin-Token", "test-admin-token")
                    header("X-Admin-Id", "ops-1")
                    header("X-Admin-Reason", "billing")
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("\"userId\":\"$userId\""), body)
            assertTrue(body.contains("\"tier\":\"select\""), body)
            assertTrue(body.contains("\"platform\":\"play_store\""), body)
            assertEquals(Triple("ops-1", userId, "billing"), repository.audited)
        }

    @Test
    fun invalidUserIdIsRejected() =
        testApplication {
            application {
                configureSerialization()
                configureStatusPages()
                configureAdminSubscriptionSupportRoutes(testConfig(), service())
            }

            val response =
                client.get("/v1/admin/subscriptions/support/not-a-uuid") {
                    header("X-Admin-Token", "test-admin-token")
                    header("X-Admin-Id", "ops-1")
                    header("X-Admin-Reason", "billing")
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
        }

    @Test
    fun routesAreNotInstalledWithoutConfiguredToken() =
        testApplication {
            application {
                configureSerialization()
                configureStatusPages()
                configureAdminSubscriptionSupportRoutes(testConfig(adminToken = null), service())
            }

            val response =
                client.get("/v1/admin/subscriptions/support/$userId") {
                    header("X-Admin-Token", "test-admin-token")
                    header("X-Admin-Id", "ops-1")
                    header("X-Admin-Reason", "billing")
                }

            assertEquals(HttpStatusCode.NotFound, response.status)
        }

    @Test
    fun operatorIsForbiddenWhenRolesConfigured() =
        testApplication {
            application {
                configureSerialization()
                configureStatusPages()
                configureAdminSubscriptionSupportRoutes(
                    config = testConfig(adminRoles = mapOf("ops-1" to "operator")),
                    service = service(),
                )
            }

            val response =
                client.get("/v1/admin/subscriptions/support/$userId") {
                    header("X-Admin-Token", "test-admin-token")
                    header("X-Admin-Id", "ops-1")
                    header("X-Admin-Reason", "billing")
                }

            assertEquals(HttpStatusCode.Forbidden, response.status)
        }

    @Test
    fun seniorIsAllowedWhenRolesConfigured() =
        testApplication {
            application {
                configureSerialization()
                configureStatusPages()
                configureAdminSubscriptionSupportRoutes(
                    config = testConfig(adminRoles = mapOf("boss" to "senior")),
                    service = service(),
                )
            }

            val response =
                client.get("/v1/admin/subscriptions/support/$userId") {
                    header("X-Admin-Token", "test-admin-token")
                    header("X-Admin-Id", "boss")
                    header("X-Admin-Reason", "billing")
                }

            assertEquals(HttpStatusCode.OK, response.status)
        }

    @Test
    fun stepUpMfaIsRequiredWhenEnforced() =
        testApplication {
            application {
                configureSerialization()
                configureStatusPages()
                configureAdminSubscriptionSupportRoutes(
                    config = testConfig(adminMfaEnforced = true, adminMfaSecrets = mapOf("ops-1" to mfaSecret)),
                    service = service(),
                )
            }

            val response =
                client.get("/v1/admin/subscriptions/support/$userId") {
                    header("X-Admin-Token", "test-admin-token")
                    header("X-Admin-Id", "ops-1")
                    header("X-Admin-Reason", "billing")
                    // no X-Admin-MFA header
                }

            assertEquals(HttpStatusCode.Forbidden, response.status)
        }

    @Test
    fun validStepUpMfaCodeIsAccepted() =
        testApplication {
            application {
                configureSerialization()
                configureStatusPages()
                configureAdminSubscriptionSupportRoutes(
                    config = testConfig(adminMfaEnforced = true, adminMfaSecrets = mapOf("ops-1" to mfaSecret)),
                    service = service(),
                )
            }

            val response =
                client.get("/v1/admin/subscriptions/support/$userId") {
                    header("X-Admin-Token", "test-admin-token")
                    header("X-Admin-Id", "ops-1")
                    header("X-Admin-Reason", "billing")
                    header("X-Admin-MFA", AdminTotp.generate(mfaSecret, Instant.now().epochSecond))
                }

            assertEquals(HttpStatusCode.OK, response.status)
        }

    private fun service(): AdminSubscriptionSupportService =
        AdminSubscriptionSupportService(
            repository = RouteSubscriptionSupportRepository(storedFacts = facts()),
            entitlementLookup = { entitlement() },
        )

    private fun facts(): AdminSubscriptionSupportFacts =
        AdminSubscriptionSupportFacts(
            subscriptions =
                listOf(
                    AdminSubscriptionRecordFact(
                        platform = "play_store",
                        tier = "select",
                        status = "active",
                        externalProductId = "pynapli_select_monthly",
                        externalStatus = "SUBSCRIPTION_STATE_ACTIVE",
                        acknowledgementState = "ACKNOWLEDGED",
                        currentPeriodStart = Instant.parse("2026-06-01T00:00:00Z"),
                        currentPeriodEnd = Instant.parse("2026-07-01T00:00:00Z"),
                        lastVerifiedAt = Instant.parse("2026-06-15T00:00:00Z"),
                    ),
                ),
            benefitsGranted = emptyList(),
            benefitsReceived = emptyList(),
        )

    private fun entitlement(): SubscriptionEntitlementDto =
        SubscriptionEntitlementDto(
            tier = "select",
            source = "own_subscription",
            displayName = "Select",
            adSupported = false,
            linkedPartnerIncluded = true,
        )

    private fun testConfig(
        adminToken: String? = "test-admin-token",
        adminRoles: Map<String, String> = emptyMap(),
        adminMfaEnforced: Boolean = false,
        adminMfaSecrets: Map<String, String> = emptyMap(),
    ): ServerConfig =
        ServerConfig(
            environment = "test",
            port = 8080,
            databaseUrl = "jdbc:postgresql://localhost:5432/pynapli_test",
            databaseUser = "pynapli",
            databasePassword = "pynapli_dev_password",
            redisUrl = "redis://localhost:6379",
            adminApiToken = adminToken,
            adminRoles = adminRoles,
            adminMfaEnforced = adminMfaEnforced,
            adminMfaSecrets = adminMfaSecrets,
        )
}

private class RouteSubscriptionSupportRepository(
    private val storedFacts: AdminSubscriptionSupportFacts?,
) : AdminSubscriptionSupportRepository {
    var audited: Triple<String, UUID, String>? = null

    override suspend fun facts(userId: UUID): AdminSubscriptionSupportFacts? = storedFacts

    override suspend fun recordReviewAudit(
        actorId: String,
        userId: UUID,
        reason: String,
    ) {
        audited = Triple(actorId, userId, reason)
    }
}
