package com.pynapli.app.store

import androidx.compose.runtime.Immutable
import com.pynapli.shared.network.dto.PremiumInventoryBalanceDto
import com.pynapli.shared.network.dto.PremiumProductDto
import com.pynapli.shared.network.dto.SubscriptionEntitlementDto
import com.pynapli.shared.network.dto.SubscriptionTier
import com.pynapli.shared.network.dto.SubscriptionTierDto

@Immutable
data class StoreUiState(
    val tiers: List<SubscriptionTierDto> = emptyList(),
    val premiumProducts: List<PremiumProductDto> = emptyList(),
    val balances: List<PremiumInventoryBalanceDto> = emptyList(),
    val entitlement: SubscriptionEntitlementDto? = null,
    val isLoading: Boolean = false,
    val pendingPurchaseTier: String? = null,
    val pendingPurchaseProductId: String? = null,
    val isRestoringPurchases: Boolean = false,
    val purchaseMessage: String? = null,
    val errorMessage: String? = null,
) {
    val currentTier: String get() = entitlement?.tier ?: SubscriptionTier.FREE

    fun balanceFor(inventoryType: String): Int = balances.firstOrNull { it.inventoryType == inventoryType }?.quantity ?: 0
}

@Immutable
data class StorePurchaseResult(
    val message: String,
    val refreshEntitlement: Boolean = true,
)

@Immutable
data class StoreUi(
    val tiers: List<SubscriptionTierDto>,
    val premiumProducts: List<PremiumProductDto>,
    val balances: List<PremiumInventoryBalanceDto>,
    val entitlement: SubscriptionEntitlementDto,
)

internal fun String.sortOrder(): Int =
    when (this) {
        SubscriptionTier.FREE -> 0
        SubscriptionTier.SOCIAL -> 1
        SubscriptionTier.SELECT -> 2
        else -> 99
    }
