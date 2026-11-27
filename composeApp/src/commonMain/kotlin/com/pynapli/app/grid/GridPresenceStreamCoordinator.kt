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
 * The Grid's share of the presence subscription (G3-A), at most [limit] ids, nearest the screen
 * first: the cards in [visibleCards], then up to [margin] cards below them (where a scroll usually
 * goes next), then up to [margin] above, then outward from both edges until [limit] is spent. So a
 * list within the cap is always watched whole, wherever the viewer is (no resubscribe on scroll), and
 * a longer one follows the viewer. With no range yet (the grid has not laid out), the cards from the
 * top. Locked cards are never included. Out-of-range indices (the loading row sits past the last
 * card) are clamped. Capped here, not only in [presenceSubscription]: the subscription is compared as
 * a set, so an uncapped list that only changed order would never register a scroll.
 */
internal fun presenceWindowIds(
    cards: List<GridProfileCardUi>,
    visibleCards: IntRange?,
    margin: Int = PRESENCE_WINDOW_MARGIN,
    limit: Int = PRESENCE_SUBSCRIPTION_CAP,
): Set<String> {
    val ids = LinkedHashSet<String>()
    if (cards.isEmpty() || limit <= 0) return ids
    val watch: (Int) -> Unit = { index ->
        val card = cards[index]
        if (ids.size < limit && !card.isLockedCard) ids += card.id
    }
    if (visibleCards == null) {
        for (index in cards.indices) watch(index)
        return ids
    }
    val first = visibleCards.first.coerceIn(0, cards.lastIndex)
    val last = visibleCards.last.coerceIn(first, cards.lastIndex)
    for (index in first..last) watch(index)
    for (index in (last + 1)..minOf(cards.lastIndex, last + margin)) watch(index)
    for (index in (first - 1) downTo maxOf(0, first - margin)) watch(index)
    var below = last + margin + 1
    var above = first - margin - 1
    while (ids.size < limit && (below <= cards.lastIndex || above >= 0)) {
        if (below <= cards.lastIndex) watch(below)
        if (above >= 0) watch(above)
        below += 1
        above -= 1
    }
    return ids
}
