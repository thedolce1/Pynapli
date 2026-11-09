package com.pynapli.server.subscriptions

import com.pynapli.server.config.ServerConfig
import com.pynapli.server.testsupport.testServerConfig
import com.pynapli.shared.network.dto.SubscriptionProductId
import kotlinx.coroutines.runBlocking
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SandboxPlayStorePurchaseVerifierTest {
    private val clock = Clock.fixed(Instant.parse("2026-06-13T12:00:00Z"), ZoneOffset.UTC)
    private val verifier = SandboxPlayStorePurchaseVerifier(clock = clock)

    @Test
    fun grantsVerifiedForSandboxTokenEchoingProductAndMonthlyPeriod() =
        runBlocking {
            val result =
                verifier.verifySubscriptionPurchase(
                    packageName = "com.pynapli.app",
                    productId = SubscriptionProductId.PLAY_SELECT_MONTHLY,
                    purchaseToken = "sandbox-select-00000000000000000001",
                )

            val verified = assertIs<PlayStorePurchaseVerificationResult.Verified>(result)
            assertEquals(SubscriptionProductId.PLAY_SELECT_MONTHLY, verified.purchase.productId)
            assertEquals("SUBSCRIPTION_STATE_ACTIVE", verified.purchase.subscriptionState)
            assertEquals("ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED", verified.purchase.acknowledgementState)
            assertEquals(Instant.parse("2026-06-13T12:00:00Z"), verified.purchase.currentPeriodStart)
            assertEquals(Instant.parse("2026-07-13T12:00:00Z"), verified.purchase.currentPeriodEnd)
        }

    @Test
    fun rejectsSandboxInvalidToken() =
        runBlocking {
            val result =
                verifier.verifySubscriptionPurchase(
                    packageName = "com.pynapli.app",
                    productId = SubscriptionProductId.PLAY_SELECT_MONTHLY,
                    purchaseToken = "sandbox-invalid-00000000000000000001",
                )

            assertEquals("INVALID_PURCHASE_TOKEN", assertIs<PlayStorePurchaseVerificationResult.Invalid>(result).code)
        }

    @Test
    fun treatsNonSandboxTokenAsUnavailable() =
        runBlocking {
            val result =
                verifier.verifySubscriptionPurchase(
                    packageName = "com.pynapli.app",
                    productId = SubscriptionProductId.PLAY_SELECT_MONTHLY,
                    purchaseToken = "real-play-token-00000000000000000001",
                )

            assertEquals(PlayStorePurchaseVerificationResult.VerificationUnavailable, result)
        }

    @Test
    fun resolvesProductFromTokenForTokenOnlyPath() =
        runBlocking {
            val select =
                verifier.verifySubscriptionPurchaseByToken("com.pynapli.app", "sandbox-select-00000000000000000002")
            val social =
                verifier.verifySubscriptionPurchaseByToken("com.pynapli.app", "sandbox-social-00000000000000000003")

            assertEquals(
                SubscriptionProductId.PLAY_SELECT_MONTHLY,
                assertIs<PlayStorePurchaseVerificationResult.Verified>(select).purchase.productId,
            )
            assertEquals(
                SubscriptionProductId.PLAY_SOCIAL_MONTHLY,
                assertIs<PlayStorePurchaseVerificationResult.Verified>(social).purchase.productId,
            )
        }

    @Test
    fun tokenOnlyPathWithoutProductHintIsInvalid() =
        runBlocking {
            val result =
                verifier.verifySubscriptionPurchaseByToken("com.pynapli.app", "sandbox-00000000000000000004")

            assertEquals(
                "INVALID_SUBSCRIPTION_PRODUCT",
                assertIs<PlayStorePurchaseVerificationResult.Invalid>(result).code,
            )
        }

    @Test
    fun selectsSandboxVerifierWhenEnabledOutsideProduction() {
        val selected = selectPlayStorePurchaseVerifier(config(environment = "local", sandbox = true))

        assertIs<SandboxPlayStorePurchaseVerifier>(selected)
    }

    @Test
    fun refusesSandboxVerifierInProductionEvenWhenFlagSet() {
        val selected = selectPlayStorePurchaseVerifier(config(environment = "production", sandbox = true))

        assertEquals(DisabledPlayStorePurchaseVerifier, selected)
    }

    @Test
    fun usesDisabledVerifierWhenSandboxOff() {
        val selected = selectPlayStorePurchaseVerifier(config(environment = "local", sandbox = false))

        assertEquals(DisabledPlayStorePurchaseVerifier, selected)
    }

    private fun config(
        environment: String,
        sandbox: Boolean,
    ): ServerConfig =
        testServerConfig(environment = environment).copy(
            playStoreSandboxEnabled = sandbox,
        )
}
