package com.pynapli.app.grid

import androidx.compose.runtime.Immutable
import com.pynapli.app.profile.FavoriteChange
import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.core.result.runCatchingCancellable
import com.pynapli.shared.network.PynapliApiClient
import com.pynapli.shared.network.dto.PresenceDeltaDto
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/**
 * The Grid's Favorites view (GRID_IMPLEMENTATION_SPEC "Favorites"): the same cards as the Grid, but
 * only the people the viewer starred, online first then coarse distance (the server's order). The
 * Grid's filters don't apply here. Kept in the graph so the view, its cards and its scroll survive a
 * tab switch; a star or un-star anywhere updates it without a refetch.
 */
class FavoritesStateHolder(
    private val repository: FavoritesRepository,
) {
    private val _state = MutableStateFlow(FavoritesUiState())
    val state: StateFlow<FavoritesUiState> = _state.asStateFlow()

    private var generation = 0

    /** Where the Favorites grid was scrolled when it last left the screen. */
    var scrollAnchor: GridScrollAnchor = GridScrollAnchor()
        private set

    fun rememberScroll(
        firstVisibleItemIndex: Int,
        firstVisibleItemScrollOffset: Int,
    ) {
        scrollAnchor = GridScrollAnchor(firstVisibleItemIndex, firstVisibleItemScrollOffset)
    }

    /**
     * Switches the Grid to Favorites: the first time it loads, afterwards the cards on screen stay
     * and a quiet refetch picks up presence, stars made elsewhere, and anyone who blocked or hid.
     */
    suspend fun show() {
        _state.update { it.copy(showing = true) }
        if (_state.value.loaded) load(quiet = true) else load()
    }

    fun showNearby() {
        _state.update { it.copy(showing = false) }
    }

    suspend fun refresh() = load()

    private suspend fun load(quiet: Boolean = false) {
        generation += 1
        val keepCards = quiet && _state.value.cards.isNotEmpty()
        _state.update { it.copy(isLoading = !keepCards, errorMessage = if (keepCards) it.errorMessage else null) }
        runCatchingCancellable { repository.loadFavorites(cursor = null) }
            .onSuccess { feed ->
                _state.update {
                    it.copy(
                        cards = feed.cards,
                        nextCursor = feed.nextCursor,
                        isLoading = false,
                        loaded = true,
                        stale = false,
                        errorMessage = null,
                    )
                }
            }.onFailure { error ->
                _state.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = if (keepCards) it.errorMessage else error.message ?: "Favorites could not be loaded.",
                    )
                }
            }
    }

    suspend fun loadMore() {
        val current = _state.value
        val cursor = current.nextCursor ?: return
        if (current.isLoading || current.isLoadingMore) return
        val started = generation
        _state.update { it.copy(isLoadingMore = true) }
        try {
            runCatchingCancellable { repository.loadFavorites(cursor) }
                .onSuccess { feed ->
                    if (started != generation) return@onSuccess
                    _state.update { existing ->
                        val ids = existing.cards.mapTo(mutableSetOf()) { it.id }
                        existing.copy(cards = existing.cards + feed.cards.filterNot { it.id in ids }, nextCursor = feed.nextCursor)
                    }
                }.onFailure { error ->
                    if (started == generation) _state.update { it.copy(errorMessage = error.message ?: "More favorites could not be loaded.") }
                }
        } finally {
            _state.update { it.copy(isLoadingMore = false) }
        }
    }

    /** A star or un-star confirmed by the server (from any profile sheet). */
    fun onFavoriteChanged(change: FavoriteChange) {
        // A locked card has no profile to star; its token never joins Favorites.
        if (change.userId.startsWith(LOCKED_CARD_ID_PREFIX)) return
        _state.update { current ->
            if (change.favorite) {
                // Its place in the order is the server's call: refetch next time the view shows.
                current.copy(stale = true)
            } else {
                current.copy(cards = current.cards.filterNot { it.id == change.userId })
            }
        }
    }

    /** Who the presence stream should watch for this view: its cards, only while it is showing. */
    val watchedUserIds: Flow<Set<String>> =
        state
            .map { ui -> if (ui.showing) ui.cards.mapTo(LinkedHashSet(ui.cards.size)) { it.id } else emptySet() }
            .distinctUntilChanged()

    /** Someone came online or went offline: flip their dot in place (order waits for the next load). */
    fun applyPresenceDelta(delta: PresenceDeltaDto) {
        _state.update { current ->
            val index = current.cards.indexOfFirst { it.id == delta.userId }
            if (index < 0 || current.cards[index].online == delta.online) return@update current
            current.copy(cards = current.cards.toMutableList().also { it[index] = it[index].copy(online = delta.online) })
        }
    }

    /** Blocked or hidden from a profile: drop the card at once (the server drops it too). */
    fun removeCardById(userId: String) {
        _state.update { current -> current.copy(cards = current.cards.filterNot { it.id == userId }) }
    }

    /** Signed out: forget this account's favorites. */
    fun clear() {
        generation += 1
        scrollAnchor = GridScrollAnchor()
        _state.value = FavoritesUiState()
    }
}

@Immutable
data class FavoritesUiState(
    val showing: Boolean = false,
    val cards: List<GridProfileCardUi> = emptyList(),
    val nextCursor: String? = null,
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    // Loaded at least once this session (an empty list after loading is an honest empty state).
    val loaded: Boolean = false,
    // A star was added somewhere since the last load; refetch quietly when the view shows.
    val stale: Boolean = false,
    val errorMessage: String? = null,
)

interface FavoritesRepository {
    suspend fun loadFavorites(cursor: String?): GridFeedUi
}

class ApiFavoritesRepository(
    private val apiClient: PynapliApiClient,
) : FavoritesRepository {
    override suspend fun loadFavorites(cursor: String?): GridFeedUi =
        when (val result = apiClient.listFavorites(cursor)) {
            is AppResult.Success ->
                GridFeedUi(
                    // Favourites are people the viewer starred, so never a locked card (Section 3);
                    // dropped here too in case one ever came back from the server.
                    cards = result.value.items.mapIndexed { index, card -> card.toGridProfileCardUi(index) }.withoutLockedCards(),
                    nextCursor = result.value.nextCursor,
                )
            is AppResult.Failure -> throw GridFeedException(code = result.error.code, message = result.error.message)
        }
}
