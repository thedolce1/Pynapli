package com.pynapli.server.subscriptions

import com.pynapli.server.safety.RateLimitService
import com.pynapli.server.safety.RateLimitedAction
import com.pynapli.shared.network.dto.PremiumInventoryType
import com.pynapli.shared.network.dto.StorePurchaseResponseDto
import com.pynapli.shared.network.dto.SubmitPlayStoreProductPurchaseRequestDto
import com.pynapli.shared.network.dto.SubscriptionEntitlementDto
import com.pynapli.shared.network.dto.SubscriptionInventoryResponseDto
import com.pynapli.shared.network.dto.SubscriptionProductId
import java.time.Clock
import java.util.UUID

/**
 * One-time Google Play purchases: verify with Google, then credit the items exactly once per
 * purchase token. The app consumes the purchase only after this answers, so a failure here leaves
 * the purchase in Play for the next sync to retry rather than losing it.
 */
internal class SubscriptionPlayStoreProductPurchaseActions(
    private val repository: SubscriptionRepository,
    private val clock: Clock,
    private val playStorePackageName: String,
    private val playStorePurchaseVerifier: PlayStorePurchaseVerifier,
    private val rateLimitService: RateLimitService?,
    private val getEntitlement: suspend (UUID) -> SubscriptionEntitlementDto,
    private val getInventory: suspend (UUID) -> SubscriptionInventoryResponseDto,
) {
    suspend fun recordProductPurchase(
        userId: UUID,
        request: SubmitPlayStoreProductPurchaseRequestDto,
    ): StorePurchaseResponseDto {
        rateLimitService.enforceSubscriptionRateLimit(userId, RateLimitedAction.STORE_PURCHASE)
        if (request.packageName.trim() != playStorePackageName) throw SubscriptionException("INVALID_PURCHASE_PACKAGE")
        val productId = request.productId.trim()
        val grant = PRODUCT_GRANTS[productId] ?: throw SubscriptionException("INVALID_STORE_PRODUCT")
        val purchaseToken = request.purchaseToken.trim().takeIf { it.length in 16..4096 } ?: throw SubscriptionException("INVALID_PURCHASE_TOKEN")

        val status =
            when (val verification = playStorePurchaseVerifier.verifyProductPurchase(playStorePackageName, productId, purchaseToken)) {
                PlayStoreProductVerificationResult.VerificationUnavailable -> throw SubscriptionException("SUBSCRIPTION_PURCHASE_SYNC_UNAVAILABLE")
                is PlayStoreProductVerificationResult.Invalid -> throw SubscriptionException(verification.code)
                PlayStoreProductVerificationResult.Pending -> STATUS_PENDING
                is PlayStoreProductVerificationResult.Verified -> {
                    val credited =
                        repository.creditPlayStoreProductPurchase(
                            purchaseToken = purchaseToken,
                            userId = userId,
                            productId = productId,
                            orderId = verification.orderId,
                            inventoryType = grant.first,
                            quantity = grant.second,
                            now = clock.instant(),
                        )
                    when (credited) {
                        ProductPurchaseCredit.Credited -> STATUS_CREDITED
                        ProductPurchaseCredit.AlreadyCredited -> STATUS_ALREADY_CREDITED
                        // The token was credited to someone else: never move items between accounts.
                        ProductPurchaseCredit.OwnedByAnotherAccount -> throw SubscriptionException("INVALID_PURCHASE_TOKEN")
                    }
                }
            }
        return StorePurchaseResponseDto(
            status = status,
            entitlement = getEntitlement(userId),
            inventory = getInventory(userId),
        )
    }

    companion object {
        const val STATUS_CREDITED = "inventory_granted"
        const val STATUS_ALREADY_CREDITED = "already_granted"
        const val STATUS_PENDING = "pending_verification"

        /**
         * Store product -> (inventory type, quantity). Bought items don't expire. The retired Intro
         * Notes pack is no longer sold, but a purchase made under it (including one still pending in
         * Play) is still verified and credited as the Pineapple Likes the notes became.
         */
        val PRODUCT_GRANTS: Map<String, Pair<String, Int>> =
            mapOf(
                SubscriptionProductId.BOOST_SINGLE to (PremiumInventoryType.BOOST to 1),
                SubscriptionProductId.PINEAPPLE_LIKES_FIVE to (PremiumInventoryType.PINEAPPLE_LIKE to 5),
                SubscriptionProductId.LEGACY_INTRO_NOTES_THREE to (PremiumInventoryType.PINEAPPLE_LIKE to 3),
            )
    }
}

enum class ProductPurchaseCredit { Credited, AlreadyCredited, OwnedByAnotherAccount }

sealed interface PlayStoreProductVerificationResult {
    data class Verified(
        val orderId: String?,
    ) : PlayStoreProductVerificationResult

    /** Paid with a slow method (cash, bank transfer): credit once Play reports it purchased. */
    data object Pending : PlayStoreProductVerificationResult

    data class Invalid(
        val code: String,
    ) : PlayStoreProductVerificationResult

    data object VerificationUnavailable : PlayStoreProductVerificationResult
}
