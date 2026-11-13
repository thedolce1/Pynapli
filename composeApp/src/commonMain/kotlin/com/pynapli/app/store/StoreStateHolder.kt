package com.pynapli.app.store

import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.network.dto.PremiumProductDto
import com.pynapli.shared.network.dto.SubscriptionTier
import com.pynapli.shared.network.dto.SubscriptionTierDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class StoreStateHolder(
    private val repository: StoreRepository,
    private val purchaseGateway: StorePurchaseGateway = UnavailableStorePurchaseGateway(),
) {
    private val _state = MutableStateFlow(StoreUiState(isLoading = true))
    val state: StateFlow<StoreUiState> = _state.asStateFlow()

    suspend fun load() {
        _state.update { it.copy(isLoading = true, errorMessage = null) }
        when (val result = repository.loadStore()) {
            is AppResult.Success -> {
                _state.update {
                    it.copy(
                        tiers = result.value.tiers.sortedBy { tier -> tier.tier.sortOrder() },
                        premiumProducts = result.value.premiumProducts,
                        balances = result.value.balances,
                        entitlement = result.value.entitlement,
                        activeBoostEndsAt = result.value.activeBoostEndsAt,
                        isLoading = false,
                        errorMessage = null,
                    )
                }
                val productIds = result.value.tiers.mapNotNull { it.playStoreProductId }
                if (productIds.isNotEmpty()) {
                    val prices = purchaseGateway.localizedPrices(productIds)
                    if (prices.isNotEmpty()) _state.update { it.copy(localizedPrices = prices) }
                }
            }

            is AppResult.Failure ->
                _state.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = result.error.message,
                    )
                }
        }
    }

    suspend fun purchase(tier: SubscriptionTierDto) {
        if (tier.tier == SubscriptionTier.FREE) return
        if (_state.value.pendingPurchaseTier != null) return
        if (_state.value.pendingPurchaseProductId != null) return
        if (_state.value.isRestoringPurchases) return

        _state.update {
            it.copy(
                pendingPurchaseTier = tier.tier,
                purchaseMessage = null,
                errorMessage = null,
            )
        }

        when (val result = purchaseGateway.purchase(tier)) {
            is AppResult.Success -> {
                _state.update {
                    it.copy(
                        pendingPurchaseTier = null,
                        pendingPurchaseProductId = null,
                        purchaseMessage = result.value.message,
                        errorMessage = null,
                    )
                }
                if (result.value.refreshEntitlement) {
                    load()
                }
            }

            is AppResult.Failure ->
                _state.update {
                    it.copy(
                        pendingPurchaseTier = null,
                        pendingPurchaseProductId = null,
                        errorMessage = result.error.message,
                        purchaseMessage = null,
                    )
                }
        }
    }

    suspend fun purchaseProduct(product: PremiumProductDto) {
        if (_state.value.pendingPurchaseTier != null) return
        if (_state.value.pendingPurchaseProductId != null) return
        if (_state.value.isRestoringPurchases) return

        _state.update {
            it.copy(
                pendingPurchaseProductId = product.productId,
                purchaseMessage = null,
                errorMessage = null,
            )
        }

        when (val result = purchaseGateway.purchaseProduct(product)) {
            is AppResult.Success -> {
                _state.update {
                    it.copy(
                        pendingPurchaseProductId = null,
                        purchaseMessage = result.value.message,
                        errorMessage = null,
                    )
                }
                if (result.value.refreshEntitlement) {
                    load()
                }
            }

            is AppResult.Failure ->
                _state.update {
                    it.copy(
                        pendingPurchaseProductId = null,
                        errorMessage = result.error.message,
                        purchaseMessage = null,
                    )
                }
        }
    }

    /** Turns on a Boost from the balance: shown first to people nearby for 30 minutes. */
    suspend fun useBoost() {
        if (_state.value.isActivatingBoost) return
        _state.update { it.copy(isActivatingBoost = true, purchaseMessage = null, errorMessage = null) }
        when (val result = repository.activateBoost()) {
            is AppResult.Success -> {
                _state.update {
                    it.copy(
                        isActivatingBoost = false,
                        activeBoostEndsAt = result.value.endsAt,
                        purchaseMessage =
                            if (result.value.alreadyActive) {
                                "Your Boost is already on."
                            } else {
                                "Boost on: you're shown first to people nearby for the next 30 minutes."
                            },
                    )
                }
                load()
            }

            is AppResult.Failure ->
                _state.update {
                    it.copy(
                        isActivatingBoost = false,
                        errorMessage = if (result.error.code == "BOOST_INVENTORY_REQUIRED") "You don't have a Boost to use. Get one below." else result.error.message,
                    )
                }
        }
    }

    suspend fun restorePurchases() {
        if (_state.value.pendingPurchaseTier != null) return
        if (_state.value.pendingPurchaseProductId != null) return
        if (_state.value.isRestoringPurchases) return

        _state.update {
            it.copy(
                isRestoringPurchases = true,
                purchaseMessage = null,
                errorMessage = null,
            )
        }

        when (val result = purchaseGateway.restorePurchases()) {
            is AppResult.Success -> {
                _state.update {
                    it.copy(
                        isRestoringPurchases = false,
                        purchaseMessage = result.value.message,
                        errorMessage = null,
                    )
                }
                if (result.value.refreshEntitlement) {
                    load()
                }
            }

            is AppResult.Failure ->
                _state.update {
                    it.copy(
                        isRestoringPurchases = false,
                        errorMessage = result.error.message,
                        purchaseMessage = null,
                    )
                }
        }
    }
}
