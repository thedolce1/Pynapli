package com.pynapli.server.subscriptions

import com.pynapli.server.auth.AuthService
import com.pynapli.server.auth.JdbcAuthRepository
import com.pynapli.server.auth.security.PasswordHasher
import com.pynapli.server.testsupport.PynapliTestDatabase
import com.pynapli.shared.network.dto.PremiumInventoryType
import com.pynapli.shared.network.dto.SubmitPlayStoreProductPurchaseRequestDto
import com.pynapli.shared.network.dto.SubscriptionProductId
import kotlinx.coroutines.runBlocking
import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** A one-time Play purchase is verified, then credited exactly once to the account that bought it. */
class PlayStoreProductPurchaseIntegrationTest {
    private val dataSource = PynapliTestDatabase.dataSource
    private val auth = AuthService(JdbcAuthRepository(dataSource), passwordHasher = PasswordHasher(iterations = 1_000))
    private val service =
        SubscriptionService(
            repository = JdbcSubscriptionRepository(dataSource),
            playStorePurchaseVerifier = SandboxPlayStorePurchaseVerifier(),
        )

    @BeforeTest
    fun reset() {
        PynapliTestDatabase.reset()
    }

    private suspend fun user(email: String) = UUID.fromString(auth.signupEmail(email, "store-password-1", "1990-01-01", true, true, true, null, "android", "1.0").user.userId)

    private fun purchase(
        productId: String = SubscriptionProductId.PINEAPPLE_LIKES_FIVE,
        token: String = "sandbox-pineapple-token-0001",
        packageName: String = "com.pynapli.app",
    ) = SubmitPlayStoreProductPurchaseRequestDto(productId = productId, purchaseToken = token, packageName = packageName)

    private suspend fun balance(
        userId: UUID,
        type: String,
    ) = service
        .getInventory(userId)
        .balances
        .firstOrNull { it.inventoryType == type }
        ?.quantity ?: 0

    @Test
    fun aPurchaseIsCreditedOnceHoweverOftenItIsSubmitted() =
        runBlocking<Unit> {
            val buyer = user("buyer@example.com")

            val first = service.recordPlayStoreProductPurchase(buyer, purchase())
            val again = service.recordPlayStoreProductPurchase(buyer, purchase())

            assertEquals("inventory_granted", first.status)
            assertEquals("already_granted", again.status)
            assertEquals(5, balance(buyer, PremiumInventoryType.PINEAPPLE_LIKE))
            assertEquals(
                null,
                service
                    .getInventory(buyer)
                    .balances
                    .first { it.inventoryType == PremiumInventoryType.PINEAPPLE_LIKE }
                    .nextExpiresAt,
            )
        }

    @Test
    fun aPurchaseOfTheRetiredIntroNotesPackIsCreditedAsThreePineappleLikes() =
        runBlocking<Unit> {
            val buyer = user("buyer@example.com")

            val credited =
                service.recordPlayStoreProductPurchase(
                    buyer,
                    purchase(productId = SubscriptionProductId.LEGACY_INTRO_NOTES_THREE, token = "sandbox-intro-notes-token-01"),
                )

            assertEquals("inventory_granted", credited.status)
            assertEquals(3, balance(buyer, PremiumInventoryType.PINEAPPLE_LIKE))
            assertEquals(listOf(PremiumInventoryType.PINEAPPLE_LIKE), service.getInventory(buyer).balances.map { it.inventoryType })
            // The retired pack is never offered again.
            assertEquals(
                false,
                service.getInventory().premiumProducts.any { it.productId == SubscriptionProductId.LEGACY_INTRO_NOTES_THREE },
            )
        }

    @Test
    fun aTokenAlreadyCreditedToSomeoneElseIsRefused() =
        runBlocking<Unit> {
            val buyer = user("buyer@example.com")
            val other = user("other@example.com")
            service.recordPlayStoreProductPurchase(buyer, purchase(productId = SubscriptionProductId.BOOST_SINGLE, token = "sandbox-boost-token-00001"))

            val error =
                assertFailsWith<SubscriptionException> {
                    service.recordPlayStoreProductPurchase(other, purchase(productId = SubscriptionProductId.BOOST_SINGLE, token = "sandbox-boost-token-00001"))
                }

            assertEquals("INVALID_PURCHASE_TOKEN", error.code)
            assertEquals(0, balance(other, PremiumInventoryType.BOOST))
            assertEquals(1, balance(buyer, PremiumInventoryType.BOOST))
        }

    @Test
    fun invalidTokensUnknownProductsAndOtherAppsCreditNothing() =
        runBlocking<Unit> {
            val buyer = user("buyer@example.com")

            listOf(
                purchase(token = "sandbox-invalid-token-0001") to "INVALID_PURCHASE_TOKEN",
                purchase(productId = "pynapli_unknown") to "INVALID_STORE_PRODUCT",
                purchase(packageName = "com.someone.else") to "INVALID_PURCHASE_PACKAGE",
                purchase(token = "not-a-sandbox-token-0001") to "SUBSCRIPTION_PURCHASE_SYNC_UNAVAILABLE",
            ).forEach { (request, code) ->
                assertEquals(code, assertFailsWith<SubscriptionException> { service.recordPlayStoreProductPurchase(buyer, request) }.code)
            }
            assertEquals(0, balance(buyer, PremiumInventoryType.PINEAPPLE_LIKE))
        }
}
