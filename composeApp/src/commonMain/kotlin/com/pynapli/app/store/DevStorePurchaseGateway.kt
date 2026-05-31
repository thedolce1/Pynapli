package com.pynapli.app.store

import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.network.PynapliApiClient
import com.pynapli.shared.network.dto.PremiumProductDto
import com.pynapli.shared.network.dto.SubmitDevStorePurchaseRequestDto
import com.pynapli.shared.network.dto.SubscriptionPurchaseSyncStatus
import com.pynapli.shared.network.dto.SubscriptionTier
import com.pynapli.shared.network.dto.SubscriptionTierDto

class DevStorePurchaseGateway(
    private val apiClient: PynapliApiClient,
) : StorePurchaseGateway {
    override suspend fun purchase(tier: SubscriptionTierDto): AppResult<StorePurchaseResult> {
        if (tier.tier == SubscriptionTier.FREE) {
            return storeFailure("BILLING_FREE_TIER", "Free does not need checkout.")
        }
        val productId =
            tier.playStoreProductId
                ?: return storeFailure("BILLING_PRODUCT_UNAVAILABLE", "This subscription is not available yet.")
        return submitDevPurchase(productId, tier.displayName)
    }

    override suspend fun purchaseProduct(product: PremiumProductDto): AppResult<StorePurchaseResult> = submitDevPurchase(product.productId, product.displayName)

    override suspend fun restorePurchases(): AppResult<StorePurchaseResult> = AppResult.Success(StorePurchaseResult(message = "Store refreshed.", refreshEntitlement = true))

    private suspend fun submitDevPurchase(
        productId: String,
        displayName: String,
    ): AppResult<StorePurchaseResult> =
        when (val result = apiClient.submitDevStorePurchase(SubmitDevStorePurchaseRequestDto(productId))) {
            is AppResult.Success ->
                AppResult.Success(
                    StorePurchaseResult(
                        message =
                            when (result.value.status) {
                                SubscriptionPurchaseSyncStatus.ACTIVE -> "$displayName active."
                                "inventory_granted" -> "$displayName added."
                                else -> "Purchase applied."
                            },
                        refreshEntitlement = true,
                    ),
                )

            is AppResult.Failure -> result
        }
}
