package com.pynapli.app.grid

import com.pynapli.shared.network.PresenceStreamClient
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf

/** One presence stream for the Grid and, while it is on screen, the Favorites view. */
class GridPresenceStreamCoordinator(
    private val client: PresenceStreamClient,
    private val gridStateHolder: GridStateHolder,
    private val favoritesStateHolder: FavoritesStateHolder? = null,
) {
    suspend fun run() {
        val favorites = favoritesStateHolder?.watchedUserIds ?: flowOf(emptySet())
        val watched =
            gridStateHolder.visibleUserIds
                .combine(favorites) { grid, starred -> presenceSubscription(favorites = starred, grid = grid) }
                .distinctUntilChanged()
        client.connect(watched).collect { delta ->
            gridStateHolder.applyPresenceDelta(delta)
            favoritesStateHolder?.applyPresenceDelta(delta)
        }
    }
}

/**
 * The server keeps only the first [PRESENCE_SUBSCRIPTION_CAP] ids of a subscription, in the order
 * sent. So the client sends at most that many, in a deliberate order: Favorites first (the people the
 * viewer chose to follow), then Grid cards from the top of the list, so nothing is cut silently and
 * favourites are never the ones dropped.
 */
internal fun presenceSubscription(
    favorites: Set<String>,
    grid: Set<String>,
    cap: Int = PRESENCE_SUBSCRIPTION_CAP,
): Set<String> {
    val ids = LinkedHashSet<String>()
    for (id in favorites) {
        if (ids.size >= cap) return ids
        ids += id
    }
    for (id in grid) {
        if (ids.size >= cap) return ids
        ids += id
    }
    return ids
}

/** Mirrors the server's per-connection presence subscription cap. */
internal const val PRESENCE_SUBSCRIPTION_CAP: Int = 200
