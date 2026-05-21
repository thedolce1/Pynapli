package com.pynapli.app.store

import androidx.compose.runtime.Immutable
import com.pynapli.shared.core.result.AppError
import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.network.PynapliApiClient
import com.pynapli.shared.network.dto.EntitlementSource
import com.pynapli.shared.network.dto.PremiumInventoryBalanceDto
import com.pynapli.shared.network.dto.PremiumInventoryType
import com.pynapli.shared.network.dto.PremiumProductDto
import com.pynapli.shared.network.dto.SubmitDevStorePurchaseRequestDto
import com.pynapli.shared.network.dto.SubscriptionEntitlementDto
import com.pynapli.shared.network.dto.SubscriptionProductId
import com.pynapli.shared.network.dto.SubscriptionPurchaseSyncStatus
import com.pynapli.shared.network.dto.SubscriptionTier
import com.pynapli.shared.network.dto.SubscriptionTierDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

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
    val errorMessage: String? = null
) {
    val currentTier: String get() = entitlement?.tier ?: SubscriptionTier.FREE

    fun balanceFor(inventoryType: String): Int =
        balances.firstOrNull { it.inventoryType == inventoryType }?.quantity ?: 0
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
                    premiumProducts = result.value.premiumProducts,
                    balances = result.value.balances,
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
        if (_state.value.pendingPurchaseProductId != null) return
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
                        pendingPurchaseProductId = null,
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
                    pendingPurchaseProductId = null,
                    errorMessage = result.error.message,
                    purchaseMessage = null
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
                errorMessage = null
            )
        }

        when (val result = purchaseGateway.purchaseProduct(product)) {
            is AppResult.Success -> {
                _state.update {
                    it.copy(
                        pendingPurchaseProductId = null,
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
                    pendingPurchaseProductId = null,
                    errorMessage = result.error.message,
                    purchaseMessage = null
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

    suspend fun purchaseProduct(product: PremiumProductDto): AppResult<StorePurchaseResult>

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

    override suspend fun purchaseProduct(product: PremiumProductDto): AppResult<StorePurchaseResult> =
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
        return AppResult.Success(
            StoreUi(
                tiers = inventory.tiers,
                premiumProducts = inventory.premiumProducts,
                balances = inventory.balances,
                entitlement = entitlement
            )
        )
    }
}

class DevStorePurchaseGateway(
    private val apiClient: PynapliApiClient
) : StorePurchaseGateway {
    override suspend fun purchase(tier: SubscriptionTierDto): AppResult<StorePurchaseResult> {
        if (tier.tier == SubscriptionTier.FREE) {
            return storeFailure("BILLING_FREE_TIER", "Free does not need checkout.")
        }
        val productId = tier.playStoreProductId
            ?: return storeFailure("BILLING_PRODUCT_UNAVAILABLE", "This subscription is not available yet.")
        return submitDevPurchase(productId, tier.displayName)
    }

    override suspend fun purchaseProduct(product: PremiumProductDto): AppResult<StorePurchaseResult> =
        submitDevPurchase(product.productId, product.displayName)

    override suspend fun restorePurchases(): AppResult<StorePurchaseResult> =
        AppResult.Success(StorePurchaseResult(message = "Store refreshed.", refreshEntitlement = true))

    private suspend fun submitDevPurchase(productId: String, displayName: String): AppResult<StorePurchaseResult> =
        when (val result = apiClient.submitDevStorePurchase(SubmitDevStorePurchaseRequestDto(productId))) {
            is AppResult.Success -> AppResult.Success(
                StorePurchaseResult(
                    message = when (result.value.status) {
                        SubscriptionPurchaseSyncStatus.ACTIVE -> "$displayName active."
                        "inventory_granted" -> "$displayName added."
                        else -> "Purchase applied."
                    },
                    refreshEntitlement = true
                )
            )

            is AppResult.Failure -> result
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
                        playStoreProductId = SubscriptionProductId.PLAY_SOCIAL_MONTHLY,
                        features = listOf("No ads", "Advanced filters", "More Activity reveals")
                    ),
                    SubscriptionTierDto(
                        tier = SubscriptionTier.SELECT,
                        displayName = "Select",
                        monthlyPriceUsdCents = 999,
                        adSupported = false,
                        linkedPartnerIncluded = true,
                        playStoreProductId = SubscriptionProductId.PLAY_SELECT_MONTHLY,
                        features = listOf("Intro Notes", "Deep Activity reveals", "Priority discovery")
                    )
                ),
                premiumProducts = listOf(
                    PremiumProductDto(
                        productId = SubscriptionProductId.BOOST_SINGLE,
                        inventoryType = PremiumInventoryType.BOOST,
                        displayName = "Boost",
                        description = "Increase visibility for 30 minutes.",
                        priceUsdCents = 299,
                        quantity = 1,
                        features = listOf("Visibility lift", "No guaranteed matches")
                    ),
                    PremiumProductDto(
                        productId = SubscriptionProductId.PINEAPPLE_LIKES_FIVE,
                        inventoryType = PremiumInventoryType.PINEAPPLE_LIKE,
                        displayName = "Pineapple Likes",
                        description = "Send a stronger swipe signal.",
                        priceUsdCents = 399,
                        quantity = 5,
                        features = listOf("High-intent signal", "Prioritized in Activity")
                    ),
                    PremiumProductDto(
                        productId = SubscriptionProductId.INTRO_NOTES_THREE,
                        inventoryType = PremiumInventoryType.INTRO_NOTE,
                        displayName = "Intro Notes",
                        description = "Send a short note before matching.",
                        priceUsdCents = 299,
                        quantity = 3,
                        features = listOf("140 characters max", "Recipient controls still apply")
                    )
                ),
                balances = listOf(
                    PremiumInventoryBalanceDto(inventoryType = PremiumInventoryType.BOOST, quantity = 0),
                    PremiumInventoryBalanceDto(inventoryType = PremiumInventoryType.PINEAPPLE_LIKE, quantity = 1),
                    PremiumInventoryBalanceDto(inventoryType = PremiumInventoryType.INTRO_NOTE, quantity = 0)
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
    val premiumProducts: List<PremiumProductDto>,
    val balances: List<PremiumInventoryBalanceDto>,
    val entitlement: SubscriptionEntitlementDto
)

private fun storeFailure(code: String, message: String): AppResult.Failure =
    AppResult.Failure(AppError(code = code, message = message))

private fun String.sortOrder(): Int = when (this) {
    SubscriptionTier.FREE -> 0
    SubscriptionTier.SOCIAL -> 1
    SubscriptionTier.SELECT -> 2
    else -> 99
}
