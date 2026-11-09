package com.pynapli.server.subscriptions

import com.pynapli.server.auth.AuthService
import com.pynapli.server.auth.SessionRecord
import com.pynapli.server.auth.security.Sha256
import com.pynapli.server.config.ServerConfig
import com.pynapli.server.plugins.configureSerialization
import com.pynapli.server.plugins.configureStatusPages
import com.pynapli.server.testsupport.SingleSessionAuthRepository
import com.pynapli.server.testsupport.testServerConfig
import com.pynapli.shared.network.dto.StorePurchaseResponseDto
import com.pynapli.shared.network.dto.SubscriptionInventoryResponseDto
import com.pynapli.shared.network.dto.SubscriptionProductId
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class SubscriptionRoutesTest {
    @Test
    fun inventoryIncludesAuthenticatedUserBalances() =
        testApplication {
            val userId = uuid(201)
            val token = "store-token"
            val repository =
                StoreRouteSubscriptionRepository(
                    balances =
                        listOf(
                            PremiumInventoryBalanceRecord(
                                inventoryType = "boost",
                                quantity = 2,
                                nextExpiresAt = Instant.parse("2026-06-01T00:00:00Z"),
                            ),
                        ),
                )
            application {
                configureSerialization()
                configureStatusPages()
                configureSubscriptionRoutes(
                    config = testConfig(devStorePurchasesEnabled = true),
                    authService = singleSessionAuthService(token, userId),
                    subscriptionService = SubscriptionService(repository),
                )
            }

            val response =
                client.get("/v1/subscriptions/inventory") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val inventory = response.decodeBody<SubscriptionInventoryResponseDto>()
            assertEquals(1, inventory.balances.size)
            assertEquals("boost", inventory.balances.single().inventoryType)
            assertEquals(2, inventory.balances.single().quantity)
        }

    @Test
    fun devStorePurchaseRequiresEnabledEnvironment() =
        testApplication {
            val userId = uuid(202)
            val token = "store-token-disabled"
            application {
                configureSerialization()
                configureStatusPages()
                configureSubscriptionRoutes(
                    config = testConfig(devStorePurchasesEnabled = false),
                    authService = singleSessionAuthService(token, userId),
                    subscriptionService = SubscriptionService(StoreRouteSubscriptionRepository()),
                )
            }

            val response =
                client.post("/v1/subscriptions/dev/purchases") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                    contentType(ContentType.Application.Json)
                    setBody("""{"productId":"${SubscriptionProductId.BOOST_SINGLE}"}""")
                }

            assertEquals(HttpStatusCode.Forbidden, response.status)
        }

    @Test
    fun devStorePurchaseGrantsInventoryThroughHttp() =
        testApplication {
            val userId = uuid(203)
            val token = "store-token-enabled"
            application {
                configureSerialization()
                configureStatusPages()
                configureSubscriptionRoutes(
                    config = testConfig(devStorePurchasesEnabled = true),
                    authService = singleSessionAuthService(token, userId),
                    subscriptionService = SubscriptionService(StoreRouteSubscriptionRepository()),
                )
            }

            val response =
                client.post("/v1/subscriptions/dev/purchases") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                    contentType(ContentType.Application.Json)
                    setBody("""{"productId":"${SubscriptionProductId.BOOST_SINGLE}"}""")
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val purchase = response.decodeBody<StorePurchaseResponseDto>()
            assertEquals("inventory_granted", purchase.status)
            assertEquals(
                "boost",
                purchase.inventory.balances
                    .single()
                    .inventoryType,
            )
            assertEquals(
                1,
                purchase.inventory.balances
                    .single()
                    .quantity,
            )
        }

    private fun testConfig(devStorePurchasesEnabled: Boolean): ServerConfig =
        testServerConfig().copy(
            devStorePurchasesEnabled = devStorePurchasesEnabled,
        )

    private fun singleSessionAuthService(
        token: String,
        userId: UUID,
    ): AuthService {
        val now = Instant.now()
        val session =
            SessionRecord(
                id = UUID.randomUUID(),
                userId = userId,
                accessTokenHash = Sha256.hash(token),
                refreshTokenHash = Sha256.hash("refresh-$token"),
                accessExpiresAt = now.plus(1, ChronoUnit.HOURS),
                refreshExpiresAt = now.plus(30, ChronoUnit.DAYS),
                revokedAt = null,
                userStatus = "active",
            )
        return AuthService(SingleSessionAuthRepository(session))
    }
}

private suspend inline fun <reified T> HttpResponse.decodeBody(): T = Json.decodeFromString(bodyAsText())

private class StoreRouteSubscriptionRepository(
    private val balances: List<PremiumInventoryBalanceRecord> = emptyList(),
) : SubscriptionRepository {
    private val activeSubscriptions = mutableListOf<SubscriptionRecord>()
    private val inventoryBalances = balances.toMutableList()

    override suspend fun findBestActiveOwnSubscription(
        userId: UUID,
        now: Instant,
    ): SubscriptionRecord? =
        activeSubscriptions
            .filter { it.userId == userId && it.status == "active" && it.currentPeriodEnd > now }
            .maxByOrNull { if (it.tier == "select") 2 else 1 }

    override suspend fun findBestActiveLinkedPartnerSubscription(
        userId: UUID,
        now: Instant,
    ): LinkedPartnerSubscriptionRecord? = null

    override suspend fun findBestActiveStoredEntitlement(
        userId: UUID,
        now: Instant,
    ): StoredEntitlementRecord? = null

    override suspend fun listPremiumInventoryBalances(
        userId: UUID,
        now: Instant,
    ): List<PremiumInventoryBalanceRecord> = inventoryBalances

    override suspend fun grantPremiumInventory(
        userId: UUID,
        inventoryType: String,
        quantity: Int,
        source: String,
        expiresAt: Instant?,
        now: Instant,
    ): PremiumInventoryBalanceRecord {
        inventoryBalances.removeAll { it.inventoryType == inventoryType }
        val record =
            PremiumInventoryBalanceRecord(
                inventoryType = inventoryType,
                quantity = quantity,
                nextExpiresAt = expiresAt,
            )
        inventoryBalances += record
        return record
    }

    override suspend fun upsertDevSubscriptionPurchase(
        userId: UUID,
        productId: String,
        tier: String,
        startsAt: Instant,
        endsAt: Instant,
    ): SubscriptionRecord {
        activeSubscriptions.removeAll { it.userId == userId }
        val record =
            SubscriptionRecord(
                subscriptionId = UUID.randomUUID(),
                userId = userId,
                tier = tier,
                status = "active",
                currentPeriodStart = startsAt,
                currentPeriodEnd = endsAt,
            )
        activeSubscriptions += record
        return record
    }

    override suspend fun consumeIntroNoteInventory(
        userId: UUID,
        now: Instant,
    ): Boolean = false
}

private fun uuid(value: Int): UUID = UUID.fromString("00000000-0000-0000-0000-${value.toString().padStart(12, '0')}")
