package com.pynapli.app.store

import com.pynapli.shared.core.result.AppResult

interface StoreRepository {
    suspend fun loadStore(): AppResult<StoreUi>

    /** Spends one Boost; answers the running Boost without spending another if one is on. */
    suspend fun activateBoost(): AppResult<com.pynapli.shared.network.dto.BoostActivationDto> =
        AppResult.Failure(
            com.pynapli.shared.core.result
                .AppError(code = "NOT_SUPPORTED", message = "Not available."),
        )
}
