package com.pynapli.server.subscriptions

import com.pynapli.shared.network.dto.SubscriptionProductId
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration

/**
 * Environment-gated stand-in for [GooglePlayStorePurchaseVerifier] that lets the real
 * submit -> verify -> grant subscription path be exercised end-to-end WITHOUT Google Play
 * Developer API credentials.
 *
 * It only ever acts on explicitly sandbox-prefixed purchase tokens ([SANDBOX_PREFIX]); any other
 * token is treated as [PlayStorePurchaseVerificationResult.VerificationUnavailable], so non-sandbox
 * traffic behaves exactly as it would with [DisabledPlayStorePurchaseVerifier] (parked pending,
 * never granted on client trust). A token beginning with [INVALID_PREFIX] simulates an invalid
 * purchase so the reject path is exercisable too.
 *
 * NEVER wire this in production — [selectPlayStorePurchaseVerifier] refuses to select it when
 * `environment == "production"`, and it is opt-in via `PYNAPLI_PLAY_STORE_SANDBOX_ENABLED`.
 */
class SandboxPlayStorePurchaseVerifier(
    private val clock: Clock = Clock.systemUTC(),
    private val validityPeriod: Duration = Duration.ofDays(30),
) : PlayStorePurchaseVerifier {
    private val logger = LoggerFactory.getLogger(SandboxPlayStorePurchaseVerifier::class.java)

    override suspend fun verifySubscriptionPurchase(
        packageName: String,
        productId: String,
        purchaseToken: String,
    ): PlayStorePurchaseVerificationResult = evaluate(purchaseToken, productId)

    override suspend fun verifySubscriptionPurchaseByToken(
        packageName: String,
        purchaseToken: String,
    ): PlayStorePurchaseVerificationResult = evaluate(purchaseToken, productIdFromToken(purchaseToken))

    private fun evaluate(
        purchaseToken: String,
        productId: String?,
    ): PlayStorePurchaseVerificationResult {
        if (!purchaseToken.startsWith(SANDBOX_PREFIX)) {
            return PlayStorePurchaseVerificationResult.VerificationUnavailable
        }
        if (purchaseToken.startsWith(INVALID_PREFIX)) {
            return PlayStorePurchaseVerificationResult.Invalid("INVALID_PURCHASE_TOKEN")
        }
        val resolvedProductId =
            productId ?: return PlayStorePurchaseVerificationResult.Invalid("INVALID_SUBSCRIPTION_PRODUCT")

        val now = clock.instant()
        // Token is logged only by its trailing fragment so a full opaque token never lands in logs.
        logger.info("Sandbox Play Store verification granted: productId={} token=...{}", resolvedProductId, purchaseToken.takeLast(6))
        return PlayStorePurchaseVerificationResult.Verified(
            VerifiedPlayStoreSubscriptionPurchase(
                productId = resolvedProductId,
                purchaseToken = purchaseToken,
                latestOrderId = "SANDBOX.${purchaseToken.takeLast(12)}",
                subscriptionState = "SUBSCRIPTION_STATE_ACTIVE",
                acknowledgementState = "ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED",
                currentPeriodStart = now,
                currentPeriodEnd = now.plus(validityPeriod),
            ),
        )
    }

    /**
     * The token-only path (sync worker / RTDN) has no caller-supplied product, so the tier is read
     * from the sandbox token body: `sandbox-select-<nonce>` / `sandbox-social-<nonce>`.
     */
    private fun productIdFromToken(purchaseToken: String): String? {
        val body = purchaseToken.removePrefix(SANDBOX_PREFIX)
        return when {
            body.startsWith("select") -> SubscriptionProductId.PLAY_SELECT_MONTHLY
            body.startsWith("social") -> SubscriptionProductId.PLAY_SOCIAL_MONTHLY
            else -> null
        }
    }

    companion object {
        const val SANDBOX_PREFIX = "sandbox-"
        private const val INVALID_PREFIX = "sandbox-invalid"
    }
}
