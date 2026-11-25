package com.pynapli.web.settings

import com.pynapli.app.grid.GridFilterStore
import com.pynapli.app.grid.GridSnapshotStore
import com.pynapli.app.grid.KeyValueGridFilterStore
import com.pynapli.app.grid.KeyValueGridSnapshotStore
import com.pynapli.web.platform.BrowserLocalStorage
import com.pynapli.web.platform.BrowserSessionStorage

private const val GRID_FILTERS_KEY = "pynapli.grid.filters.v1"
private const val GRID_SNAPSHOT_KEY = "pynapli.grid.snapshot.v1"

/**
 * The Grid's cold-start snapshot on web (plan G12): `sessionStorage` only, so a reload paints the
 * last cards at once and closing the tab forgets them. Never `localStorage`: on a shared computer
 * the next visitor must not see someone's Grid. Any `localStorage` copy under the same key (none
 * was ever written, but a hand-edited or future build could) is removed on start and on clear.
 */
fun sessionStorageGridSnapshotStore(): GridSnapshotStore {
    BrowserLocalStorage.remove(GRID_SNAPSHOT_KEY)
    return KeyValueGridSnapshotStore(
        read = { BrowserSessionStorage.get(GRID_SNAPSHOT_KEY) },
        write = { value -> BrowserSessionStorage.set(GRID_SNAPSHOT_KEY, value) },
        remove = {
            BrowserSessionStorage.remove(GRID_SNAPSHOT_KEY)
            BrowserLocalStorage.remove(GRID_SNAPSHOT_KEY)
        },
    )
}

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
