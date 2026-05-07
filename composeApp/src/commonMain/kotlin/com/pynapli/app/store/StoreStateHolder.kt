package com.pynapli.app.store

import androidx.compose.runtime.Immutable
import com.pynapli.shared.core.result.AppError
import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.network.PynapliApiClient
import com.pynapli.shared.network.dto.EntitlementSource
import com.pynapli.shared.network.dto.SubscriptionEntitlementDto
import com.pynapli.shared.network.dto.SubscriptionTier
import com.pynapli.shared.network.dto.SubscriptionTierDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

@Immutable
data class StoreUiState(
    val tiers: List<SubscriptionTierDto> = emptyList(),
    val entitlement: SubscriptionEntitlementDto? = null,
    val isLoading: Boolean = false,
    val pendingPurchaseTier: String? = null,
    val isRestoringPurchases: Boolean = false,
    val purchaseMessage: String? = null,
    val errorMessage: String? = null
) {
    val currentTier: String get() = entitlement?.tier ?: SubscriptionTier.FREE
}

class StoreStateHolder(
    private val repository: StoreRepository,
    private val purchaseGateway: StorePurchaseGateway = UnavailableStorePurchaseGateway()
) {
    private val _state = MutableStateFlow(StoreUiState(isLoading = true))
    val state: StateFlow<StoreUiState> = _state.asStateFlow()

    suspend fun load() {
        _state.update { it.copy(isLoading = true, errorMessage = null) }
        when (val result = repository.loadStore()) {
            is AppResult.Success -> _state.update {
                it.copy(
                    tiers = result.value.tiers.sortedBy { tier -> tier.tier.sortOrder() },
                    entitlement = result.value.entitlement,
                    isLoading = false,
                    errorMessage = null
                )
            }

            is AppResult.Failure -> _state.update {
                it.copy(
                    isLoading = false,
                    errorMessage = result.error.message
                )
            }
        }
    }

    suspend fun purchase(tier: SubscriptionTierDto) {
        if (tier.tier == SubscriptionTier.FREE) return
        if (_state.value.pendingPurchaseTier != null) return
        if (_state.value.isRestoringPurchases) return

        _state.update {
            it.copy(
                pendingPurchaseTier = tier.tier,
                purchaseMessage = null,
                errorMessage = null
            )
        }

        when (val result = purchaseGateway.purchase(tier)) {
            is AppResult.Success -> {
                _state.update {
                    it.copy(
                        pendingPurchaseTier = null,
                        purchaseMessage = result.value.message,
                        errorMessage = null
                    )
                }
                if (result.value.refreshEntitlement) {
                    load()
                }
            }

            is AppResult.Failure -> _state.update {
                it.copy(
                    pendingPurchaseTier = null,
                    errorMessage = result.error.message,
                    purchaseMessage = null
                )
            }
        }
    }

    suspend fun restorePurchases() {
        if (_state.value.pendingPurchaseTier != null) return
        if (_state.value.isRestoringPurchases) return

        _state.update {
            it.copy(
                isRestoringPurchases = true,
                purchaseMessage = null,
                errorMessage = null
            )
        }

        when (val result = purchaseGateway.restorePurchases()) {
            is AppResult.Success -> {
                _state.update {
                    it.copy(
                        isRestoringPurchases = false,
                        purchaseMessage = result.value.message,
                        errorMessage = null
                    )
                }
                if (result.value.refreshEntitlement) {
                    load()
                }
            }

            is AppResult.Failure -> _state.update {
                it.copy(
                    isRestoringPurchases = false,
                    errorMessage = result.error.message,
                    purchaseMessage = null
                )
            }
        }
    }
}

interface StoreRepository {
    suspend fun loadStore(): AppResult<StoreUi>
}

interface StorePurchaseGateway {
    suspend fun purchase(tier: SubscriptionTierDto): AppResult<StorePurchaseResult>

    suspend fun restorePurchases(): AppResult<StorePurchaseResult>
}

@Immutable
data class StorePurchaseResult(
    val message: String,
    val refreshEntitlement: Boolean = true
)

class UnavailableStorePurchaseGateway : StorePurchaseGateway {
    override suspend fun purchase(tier: SubscriptionTierDto): AppResult<StorePurchaseResult> =
        AppResult.Failure(
            AppError(
                code = "BILLING_UNAVAILABLE",
                message = "Purchases are not available in this build."
            )
        )

    override suspend fun restorePurchases(): AppResult<StorePurchaseResult> =
        AppResult.Failure(
            AppError(
                code = "BILLING_UNAVAILABLE",
                message = "Restore is not available in this build."
            )
        )
}

class ApiStoreRepository(
    private val apiClient: PynapliApiClient
) : StoreRepository {
    override suspend fun loadStore(): AppResult<StoreUi> {
        val entitlement = when (val result = apiClient.getSubscriptionEntitlement()) {
            is AppResult.Success -> result.value
            is AppResult.Failure -> return result
        }
        val inventory = when (val result = apiClient.getSubscriptionInventory()) {
            is AppResult.Success -> result.value
            is AppResult.Failure -> return result
        }
        return AppResult.Success(StoreUi(tiers = inventory.tiers, entitlement = entitlement))
    }
}

class SampleStoreRepository : StoreRepository {
    override suspend fun loadStore(): AppResult<StoreUi> =
        AppResult.Success(
            StoreUi(
                tiers = listOf(
                    SubscriptionTierDto(
                        tier = SubscriptionTier.FREE,
                        displayName = "Free",
                        monthlyPriceUsdCents = 0,
                        adSupported = true,
                        linkedPartnerIncluded = false,
                        features = listOf("Grid browsing", "Swipe matching", "Messages after match")
                    ),
                    SubscriptionTierDto(
                        tier = SubscriptionTier.SOCIAL,
                        displayName = "Social",
                        monthlyPriceUsdCents = 499,
                        adSupported = false,
                        linkedPartnerIncluded = true,
                        playStoreProductId = "pynapli_social_monthly",
                        features = listOf("No ads", "Advanced filters", "More Activity reveals")
                    ),
                    SubscriptionTierDto(
                        tier = SubscriptionTier.SELECT,
                        displayName = "Select",
                        monthlyPriceUsdCents = 999,
                        adSupported = false,
                        linkedPartnerIncluded = true,
                        playStoreProductId = "pynapli_select_monthly",
                        features = listOf("Intro Notes", "Deep Activity reveals", "Priority discovery")
                    )
                ),
                entitlement = SubscriptionEntitlementDto(
                    tier = SubscriptionTier.FREE,
                    source = EntitlementSource.FREE,
                    displayName = "Free",
                    adSupported = true,
                    linkedPartnerIncluded = false
                )
            )
        )
}

@Immutable
data class StoreUi(
    val tiers: List<SubscriptionTierDto>,
    val entitlement: SubscriptionEntitlementDto
)

private fun String.sortOrder(): Int = when (this) {
    SubscriptionTier.FREE -> 0
    SubscriptionTier.SOCIAL -> 1
    SubscriptionTier.SELECT -> 2
    else -> 99
}
