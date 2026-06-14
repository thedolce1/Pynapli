package com.pynapli.server.subscriptions

import com.pynapli.server.safety.RateLimitService
import com.pynapli.server.safety.RateLimitedAction
import com.pynapli.shared.network.dto.SubmitPlayStoreSubscriptionPurchaseRequestDto
import com.pynapli.shared.network.dto.SubscriptionEntitlementDto
import com.pynapli.shared.network.dto.SubscriptionPurchaseSyncResponseDto
import com.pynapli.shared.network.dto.SubscriptionPurchaseSyncStatus
import java.time.Clock
import java.util.UUID

internal class SubscriptionPlayStorePurchaseActions(
    private val repository: SubscriptionRepository,
    private val clock: Clock,
    private val playStorePackageName: String,
    private val playStorePurchaseVerifier: PlayStorePurchaseVerifier,
    private val purchaseApplier: PlayStoreSubscriptionPurchaseApplier,
    private val rateLimitService: RateLimitService?,
    private val getEntitlement: suspend (UUID) -> SubscriptionEntitlementDto,
) {
    suspend fun recordPlayStoreSubscriptionPurchase(
        userId: UUID,
        request: SubmitPlayStoreSubscriptionPurchaseRequestDto,
    ): SubscriptionPurchaseSyncResponseDto {
        rateLimitService.enforceSubscriptionRateLimit(userId, RateLimitedAction.STORE_PURCHASE)
        val productId = request.productId.trim()
        val purchaseToken = request.purchaseToken.trim()
        val packageName = request.packageName.trim()
        if (productId.isEmpty()) throw SubscriptionException("INVALID_SUBSCRIPTION_PRODUCT")
        if (purchaseToken.length !in 16..4096) throw SubscriptionException("INVALID_PURCHASE_TOKEN")
        if (packageName != playStorePackageName) throw SubscriptionException("INVALID_PURCHASE_PACKAGE")

        val tier = playStoreTierForProduct(productId) ?: throw SubscriptionException("INVALID_SUBSCRIPTION_PRODUCT")
        val now = clock.instant()
        return when (
            val verification =
                playStorePurchaseVerifier.verifySubscriptionPurchase(
                    packageName = packageName,
                    productId = productId,
                    purchaseToken = purchaseToken,
                )
        ) {
            is PlayStorePurchaseVerificationResult.Invalid -> throw SubscriptionException(verification.code)
            PlayStorePurchaseVerificationResult.VerificationUnavailable -> {
                repository.upsertPendingPlayStoreSubscriptionPurchase(
                    userId = userId,
                    productId = productId,
                    purchaseToken = purchaseToken,
                    tier = tier,
                    now = now,
                )
                pendingVerificationResponse(userId)
            }
            is PlayStorePurchaseVerificationResult.Verified -> {
                val subscription =
                    purchaseApplier.applyVerifiedPurchase(
                        userId = userId,
                        tier = tier,
                        purchase = verification.purchase,
                        verifiedAt = now,
                    )

                if (subscription.status in ACTIVE_PLAY_STORE_SUBSCRIPTION_STATUSES) {
                    SubscriptionPurchaseSyncResponseDto(
                        status = SubscriptionPurchaseSyncStatus.ACTIVE,
                        entitlement = getEntitlement(userId),
                    )
                } else {
                    pendingVerificationResponse(userId)
                }
            }
        }
    }

    private suspend fun pendingVerificationResponse(userId: UUID): SubscriptionPurchaseSyncResponseDto =
        SubscriptionPurchaseSyncResponseDto(
            status = SubscriptionPurchaseSyncStatus.PENDING_VERIFICATION,
            entitlement = getEntitlement(userId),
        )
}
