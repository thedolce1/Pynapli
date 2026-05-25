package com.pynapli.app.store

import com.pynapli.shared.core.result.AppResult

interface StoreRepository {
    suspend fun loadStore(): AppResult<StoreUi>
}
