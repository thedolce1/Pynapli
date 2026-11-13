package com.pynapli.shared.network

import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.network.dto.BoostActivationDto
import com.pynapli.shared.network.dto.StorePurchaseResponseDto
import com.pynapli.shared.network.dto.SubmitDevStorePurchaseRequestDto
import com.pynapli.shared.network.dto.SubmitPlayStoreSubscriptionPurchaseRequestDto
import com.pynapli.shared.network.dto.SubscriptionEntitlementDto
import com.pynapli.shared.network.dto.SubscriptionInventoryResponseDto
import com.pynapli.shared.network.dto.SubscriptionPurchaseSyncResponseDto

/**
 * Subscription/Store slice of the API surface, split out of the monolithic [PynapliApiClient]
 * (which now composes the per-feature interfaces). Covers entitlement, inventory, Play-Store and
 * dev-store purchase sync, and boost activation. Signatures are unchanged.
 */
interface SubscriptionApi {
    suspend fun getSubscriptionEntitlement(): AppResult<SubscriptionEntitlementDto>

    suspend fun getSubscriptionInventory(): AppResult<SubscriptionInventoryResponseDto>

    suspend fun submitPlayStoreSubscriptionPurchase(
        request: SubmitPlayStoreSubscriptionPurchaseRequestDto,
    ): AppResult<SubscriptionPurchaseSyncResponseDto>

    suspend fun submitDevStorePurchase(request: SubmitDevStorePurchaseRequestDto): AppResult<StorePurchaseResponseDto>

    suspend fun activateBoost(): AppResult<BoostActivationDto>

    /** Verifies and credits a one-time Google Play purchase; answers already_granted on repeats. */
    suspend fun submitPlayStoreProductPurchase(
        request: com.pynapli.shared.network.dto.SubmitPlayStoreProductPurchaseRequestDto,
    ): AppResult<com.pynapli.shared.network.dto.StorePurchaseResponseDto> =
        AppResult.Failure(
            com.pynapli.shared.core.result
                .AppError(code = "NOT_SUPPORTED", message = "Not available."),
        )
}
