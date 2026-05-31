package com.pynapli.app.store

import com.pynapli.shared.core.result.AppError
import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.network.dto.PremiumProductDto
import com.pynapli.shared.network.dto.SubscriptionTierDto

interface StorePurchaseGateway {
    suspend fun purchase(tier: SubscriptionTierDto): AppResult<StorePurchaseResult>

    suspend fun purchaseProduct(product: PremiumProductDto): AppResult<StorePurchaseResult>

    suspend fun restorePurchases(): AppResult<StorePurchaseResult>
}

class UnavailableStorePurchaseGateway : StorePurchaseGateway {
    override suspend fun purchase(tier: SubscriptionTierDto): AppResult<StorePurchaseResult> = storeFailure("BILLING_UNAVAILABLE", "Purchases are not available in this build.")

    override suspend fun purchaseProduct(product: PremiumProductDto): AppResult<StorePurchaseResult> = storeFailure("BILLING_UNAVAILABLE", "Purchases are not available in this build.")

    override suspend fun restorePurchases(): AppResult<StorePurchaseResult> = storeFailure("BILLING_UNAVAILABLE", "Restore is not available in this build.")
}

internal fun storeFailure(
    code: String,
    message: String,
): AppResult.Failure = AppResult.Failure(AppError(code = code, message = message))
