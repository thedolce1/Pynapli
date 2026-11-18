package com.pynapli.app.grid

import com.pynapli.shared.network.PresenceStreamClient
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/** One presence stream for the Grid and, while it is on screen, the Favorites view. */
class GridPresenceStreamCoordinator(
    private val client: PresenceStreamClient,
    private val gridStateHolder: GridStateHolder,
    private val favoritesStateHolder: FavoritesStateHolder? = null,
) {
    suspend fun run() {
        val watched =
            if (favoritesStateHolder == null) {
                gridStateHolder.visibleUserIds
            } else {
                gridStateHolder.visibleUserIds
                    .combine(favoritesStateHolder.watchedUserIds) { grid, favorites -> grid + favorites }
                    .distinctUntilChanged()
            }
        client.connect(watched).collect { delta ->
            gridStateHolder.applyPresenceDelta(delta)
            favoritesStateHolder?.applyPresenceDelta(delta)
        }
    }
}
