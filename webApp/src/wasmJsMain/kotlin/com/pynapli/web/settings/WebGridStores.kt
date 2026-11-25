package com.pynapli.web.settings

import com.pynapli.app.grid.GridFilterStore
import com.pynapli.app.grid.KeyValueGridFilterStore
import com.pynapli.web.platform.BrowserLocalStorage

private const val GRID_FILTERS_KEY = "pynapli.grid.filters.v1"

/**
 * The Grid's filters across browser sessions (plan G9), in `localStorage`: filter choices only, no
 * cards or places, and sign-out clears them (GridStateHolder.clearForSignOut).
 */
fun localStorageGridFilterStore(): GridFilterStore =
    KeyValueGridFilterStore(
        read = { BrowserLocalStorage.get(GRID_FILTERS_KEY) },
        write = { value -> BrowserLocalStorage.set(GRID_FILTERS_KEY, value) },
        remove = { BrowserLocalStorage.remove(GRID_FILTERS_KEY) },
    )
