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
 * viewer chose to follow), then the Grid's window ([presenceWindowIds]: the cards on screen first),
 * so nothing is cut silently and
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

/** Cards watched either side of the ones on screen: about three phone screens of 3-up tiles each way. */
internal const val PRESENCE_WINDOW_MARGIN: Int = 30

/**
 * The Grid's share of the presence subscription (G3-A): the cards in [visibleCards] first, then up
 * to [margin] cards below them (where a scroll usually goes next), then up to [margin] above. With no
 * range yet (the grid has not laid out), every card from the top, which [presenceSubscription] then
 * caps. Locked cards are never included. Out-of-range indices (the loading row sits past the last
 * card) are clamped.
 */
internal fun presenceWindowIds(
    cards: List<GridProfileCardUi>,
    visibleCards: IntRange?,
    margin: Int = PRESENCE_WINDOW_MARGIN,
): Set<String> {
    val ids = LinkedHashSet<String>()
    if (cards.isEmpty()) return ids
    val unlockedAt: (Int) -> Unit = { index ->
        val card = cards[index]
        if (!card.isLockedCard) ids += card.id
    }
    if (visibleCards == null) {
        for (index in cards.indices) unlockedAt(index)
        return ids
    }
    val first = visibleCards.first.coerceIn(0, cards.lastIndex)
    val last = visibleCards.last.coerceIn(first, cards.lastIndex)
    for (index in first..last) unlockedAt(index)
    for (index in (last + 1)..minOf(cards.lastIndex, last + margin)) unlockedAt(index)
    for (index in (first - 1) downTo maxOf(0, first - margin)) unlockedAt(index)
    return ids
}
