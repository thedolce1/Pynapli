package com.pynapli.app.store

import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.network.PynapliApiClient

class ApiStoreRepository(
    private val apiClient: PynapliApiClient,
) : StoreRepository {
    override suspend fun loadStore(): AppResult<StoreUi> {
        val entitlement =
            when (val result = apiClient.getSubscriptionEntitlement()) {
                is AppResult.Success -> result.value
                is AppResult.Failure -> return result
            }
        val inventory =
            when (val result = apiClient.getSubscriptionInventory()) {
                is AppResult.Success -> result.value
                is AppResult.Failure -> return result
            }
        return AppResult.Success(
            StoreUi(
                tiers = inventory.tiers,
                premiumProducts = inventory.premiumProducts,
                balances = inventory.balances,
                entitlement = entitlement,
            ),
        )
    }
}
