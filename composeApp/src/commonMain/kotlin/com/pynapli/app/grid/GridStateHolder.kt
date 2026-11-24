package com.pynapli.app.grid

import androidx.compose.runtime.Immutable
import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.core.result.runCatchingCancellable
import com.pynapli.shared.network.PynapliApiClient
import com.pynapli.shared.network.dto.PresenceDeltaDto
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeMark
import kotlin.time.TimeSource

class GridStateHolder(
    private val repository: GridFeedRepository,
    private val snapshotStore: GridSnapshotStore = NoOpGridSnapshotStore,
) {
    private val _state = MutableStateFlow(GridUiState(isRefreshing = true, cacheChecked = false))
    val state: StateFlow<GridUiState> = _state.asStateFlow()

    /**
     * Bumped by every [load]. A [loadMore] page that started under an older generation was requested
     * with old filters/cursor, so its result is dropped instead of appended (same latest-wins idea as
     * Activity's refreshGeneration).
     */
    private var feedGeneration = 0

    /**
     * Instant first paint: render the last persisted Grid cards immediately (if any) so the screen
     * isn't blank while the network refresh runs, then load fresh. Only primes when no cards are
     * present yet, so it never clobbers a live grid. Call once on first composition instead of [load].
     */
    suspend fun primeFromCacheThenLoad() {
        if (_state.value.cards.isNotEmpty()) {
            // Returning to the Grid tab: the cards are still here, so keep them (and their order and
            // scroll) and only refresh quietly once they have expired, as the app-resume path does.
            if (resultsOlderThan(RESULTS_EXPIRE_AFTER) && !joinInFlightLoad()) load(quiet = true)
            return
        }
        val cached = runCatchingCancellable { snapshotStore.load() }.getOrElse { emptyList() }
        val paintedCache = cached.isNotEmpty() && _state.value.cards.isEmpty()
        if (paintedCache) {
            // Show cached cards instantly; a quiet refresh swaps in the live feed atomically
            // (no blank, no scroll reset) per the spec's instant-render rule.
            _state.update { current -> current.copy(cards = cached, isRefreshing = true, cacheChecked = true) }
        }
        // A load already on the wire for these filters (the Location step's first grid, or a
        // location refresh on resume) answers this screen too: wait for it instead of starting a
        // second request that would supersede it. Checked after the cache read, which suspends.
        if (joinInFlightLoad()) return
        load(quiet = paintedCache)
    }

    /** The [load] currently on the wire: its generation, the filters it asked with, and its outcome. */
    private class InFlightLoad(
        val generation: Int,
        val filters: GridFilters,
        /** Completes true when this load's answer (cards or error) was applied, false otherwise. */
        val applied: CompletableDeferred<Boolean> = CompletableDeferred(),
    )

    private var inFlightLoad: InFlightLoad? = null

    /** The generation whose first-page answer was last applied; -1 until one is. */
    private var appliedGeneration = -1

    /**
     * Waits for the load already in flight when it is still current (same generation, same filters)
     * and returns true once a current answer has been applied. Returns false when there is none to
     * join, so the caller starts its own. Only the compose-time path joins: a filter change, refresh or
     * location move always calls [load], which bumps the generation and supersedes the old request.
     */
    private suspend fun joinInFlightLoad(): Boolean {
        val flight = inFlightLoad ?: return false
        if (flight.generation != feedGeneration || flight.filters != _state.value.filters) return false
        if (flight.applied.await()) return true
        // Superseded (a filter change, say) or cancelled before it answered. A newer load may already
        // have answered, or still be on the wire: either way this screen needs no request of its own.
        if (appliedGeneration == feedGeneration) return true
        return joinInFlightLoad()
    }

    private var loadedAt: TimeMark? = null

    /** True when the cards on screen came from the server longer than [age] ago (or never did). */
    fun resultsOlderThan(age: Duration): Boolean = loadedAt?.let { it.elapsedNow() >= age } ?: true

    suspend fun load(quiet: Boolean = false) {
        _state.update { if (it.cacheChecked) it else it.copy(cacheChecked = true) }
        val before = _state.value
        val keepCardsVisible = quiet && before.cards.isNotEmpty()
        feedGeneration += 1

        _state.update { current ->
            current.copy(
                isRefreshing = if (keepCardsVisible) current.isRefreshing else true,
                errorCode = if (keepCardsVisible) current.errorCode else null,
                errorMessage = if (keepCardsVisible) current.errorMessage else null,
            )
        }
        val generation = feedGeneration
        val filters = _state.value.filters
        val flight = InFlightLoad(generation, filters)
        inFlightLoad = flight
        var answered = false
        try {
            fetchFirstPage(filters, generation, keepCardsVisible)
            answered = true
        } finally {
            // Always released, cancellation included, so a joiner never waits on a dead request.
            if (inFlightLoad === flight) inFlightLoad = null
            val applied = answered && generation == feedGeneration
            if (applied) appliedGeneration = generation
            flight.applied.complete(applied)
        }
    }

    /** One first-page request; its answer (cards or error) is applied only while [generation] is current. */
    private suspend fun fetchFirstPage(
        filters: GridFilters,
        generation: Int,
        keepCardsVisible: Boolean,
    ) {
        runCatchingCancellable { repository.loadGrid(filters) }
            .onSuccess { feed ->
                // Signed out (or reloaded) while this was in flight: never resurrect old cards.
                if (generation != feedGeneration) return@onSuccess
                loadedAt = TimeSource.Monotonic.markNow()
                _state.update { current ->
                    current.copy(
                        cards = feed.cards,
                        nextCursor = feed.nextCursor,
                        paywallMessage = feed.resultLimitPaywallMessage,
                        verificationMessage = null,
                        isRefreshing = false,
                        isLoadingMore = false,
                        errorCode = null,
                        errorMessage = null,
                    )
                }
                // Persist the fresh cards for an instant first paint on next open (best-effort).
                runCatchingCancellable { snapshotStore.save(feed.cards) }
            }.onFailure { error ->
                _state.update { current ->
                    val code = (error as? GridFeedException)?.code
                    val entitlementRequired = code == "ENTITLEMENT_REQUIRED"
                    val verificationRequired = code == "AGE_VERIFICATION_REQUIRED"
                    // Both gates revert the just-applied (gated) filters and surface a prompt
                    // instead of a raw error, keeping any visible cards on screen.
                    val gated = entitlementRequired || verificationRequired
                    if (keepCardsVisible && !gated) {
                        current.copy(isRefreshing = false)
                    } else {
                        current.copy(
                            isRefreshing = false,
                            filters = if (gated) GridFilters.defaults else current.filters,
                            paywallMessage =
                                if (entitlementRequired) {
                                    "Social or Select unlocks this filter."
                                } else {
                                    current.paywallMessage
                                },
                            verificationMessage =
                                if (verificationRequired) {
                                    "Verify your age to use lifestyle filters."
                                } else {
                                    current.verificationMessage
                                },
                            errorCode = code,
                            errorMessage =
                                if (gated && current.cards.isNotEmpty()) {
                                    null
                                } else {
                                    error.message ?: "Grid could not be loaded."
                                },
                        )
                    }
                }
            }
    }

    /**
     * Explicit user-initiated refresh (pull-to-refresh). Always re-fetches the current filters and
     * surfaces the refresh indicator + any error, unlike a quiet location-movement refresh which
     * keeps cards untouched. Scroll position and filters are preserved; stable card keys animate the
     * diff so the grid is not visually nuked.
     */
    suspend fun refresh() {
        load(quiet = false)
    }

    suspend fun toggleQuickFilter(id: QuickFilterId) {
        _state.update { current ->
            current.copy(
                filters = current.filters.toggle(id),
                paywallMessage = null,
                verificationMessage = null,
                errorCode = null,
                errorMessage = null,
            )
        }
        load()
    }

    suspend fun applyFilters(filters: GridFilters) {
        if (_state.value.filters == filters) return
        _state.update { current ->
            current.copy(
                filters = filters,
                paywallMessage = null,
                verificationMessage = null,
                errorCode = null,
                errorMessage = null,
            )
        }
        load()
    }

    suspend fun setRadiusMeters(meters: Int) {
        val clamped = meters.coerceIn(GridFilters.MIN_RADIUS_METERS, GridFilters.MAX_RADIUS_METERS)
        applyFilters(_state.value.filters.copy(radiusMeters = clamped))
    }

    suspend fun setAgeRange(
        minimum: Int?,
        maximum: Int?,
    ) {
        val (lo, hi) = normalizedAgeRange(minimum, maximum)
        applyFilters(_state.value.filters.copy(minimumAge = lo, maximumAge = hi))
    }

    suspend fun clearAllFilters() {
        applyFilters(GridFilters.defaults)
    }

    suspend fun loadMore() {
        val current = _state.value
        val cursor = current.nextCursor ?: return
        if (current.isRefreshing || current.isLoadingMore || current.cards.isEmpty()) return

        val generation = feedGeneration
        _state.update { it.copy(isLoadingMore = true, errorCode = null, errorMessage = null) }
        try {
            loadMorePage(current.filters, cursor, generation)
        } finally {
            // Always release the flag, including on cancellation (e.g. a tab switch mid-request);
            // otherwise the guard above would block paging for the rest of the session.
            _state.update { it.copy(isLoadingMore = false) }
        }
    }

    private suspend fun loadMorePage(
        filters: GridFilters,
        cursor: String,
        generation: Int,
    ) {
        runCatchingCancellable { repository.loadGrid(filters, cursor) }
            .onSuccess { feed ->
                // Filters changed or a reload ran while this page was in flight: drop the stale page.
                if (generation != feedGeneration) return@onSuccess
                _state.update { existing ->
                    val existingIds = existing.cards.mapTo(mutableSetOf()) { it.id }
                    val appended = feed.cards.filterNot { it.id in existingIds }
                    existing.copy(
                        cards = existing.cards + appended,
                        nextCursor = feed.nextCursor,
                        paywallMessage = feed.resultLimitPaywallMessage,
                        isLoadingMore = false,
                        errorCode = null,
                        errorMessage = null,
                    )
                }
            }.onFailure { error ->
                if (generation != feedGeneration) return@onFailure
                _state.update { existing ->
                    existing.copy(
                        isLoadingMore = false,
                        errorCode = (error as? GridFeedException)?.code,
                        errorMessage = error.message ?: "More profiles could not be loaded.",
                    )
                }
            }
    }

    suspend fun removeCardById(userId: String) {
        _state.update { current ->
            current.copy(cards = current.cards.filterNot { it.id == userId })
        }
        // Keep the cold-start cache in step, so someone just blocked or hidden never paints again.
        runCatchingCancellable { snapshotStore.save(_state.value.cards) }
    }

    /**
     * Travel mode as it was when the Grid last left the screen. Travel can be switched on or off from
     * Swipe meanwhile; the Grid then reloads on return instead of keeping another place's cards.
     */
    var travelKeyWhenLeft: String? = null

    private val _onScreen = MutableStateFlow(false)

    /** True while the Grid screen is composed (its tab is showing); feeds the notification ask timing. */
    val onScreen: StateFlow<Boolean> = _onScreen.asStateFlow()

    fun setOnScreen(visible: Boolean) {
        _onScreen.value = visible
    }

    /** Marks the cards as expired, so the next return to the Grid refetches them quietly. */
    fun invalidate() {
        loadedAt = null
    }

    /** Where the Grid was scrolled when it last left the screen (tab switch), restored on return. */
    var scrollAnchor: GridScrollAnchor = GridScrollAnchor()
        private set

    fun rememberScroll(
        firstVisibleItemIndex: Int,
        firstVisibleItemScrollOffset: Int,
    ) {
        scrollAnchor = GridScrollAnchor(firstVisibleItemIndex, firstVisibleItemScrollOffset)
    }

    /**
     * Signed out (or the session died): forget this account's Grid, in memory and in the cold-start
     * cache, so the next account never sees it.
     */
    suspend fun clearForSignOut() {
        feedGeneration += 1
        loadedAt = null
        scrollAnchor = GridScrollAnchor()
        travelKeyWhenLeft = null
        _state.value = GridUiState(isRefreshing = true, cacheChecked = false)
        runCatchingCancellable { snapshotStore.clear() }
    }

    fun applyPresenceDelta(delta: PresenceDeltaDto) {
        _state.update { current ->
            val targetIndex = current.cards.indexOfFirst { it.id == delta.userId }
            if (targetIndex < 0) return@update current
            val target = current.cards[targetIndex]
            if (target.online == delta.online) return@update current
            val updated = current.cards.toMutableList()
            updated[targetIndex] = target.copy(online = delta.online)
            current.copy(cards = updated)
        }
    }

    val visibleUserIds: kotlinx.coroutines.flow.Flow<Set<String>> =
        state
            .map { ui -> ui.cards.mapTo(LinkedHashSet(ui.cards.size)) { it.id } }
            .distinctUntilChanged()

    private fun GridFilters.toggle(id: QuickFilterId): GridFilters =
        when (id) {
            QuickFilterId.Online -> copy(online = !online)
            QuickFilterId.Singles -> copy(singles = !singles)
            QuickFilterId.Linked -> copy(linked = !linked)
            QuickFilterId.Verified -> copy(verified = !verified)
            QuickFilterId.Tonight -> copy(tonightAvailable = !tonightAvailable)
        }

    private fun normalizedAgeRange(
        minimum: Int?,
        maximum: Int?,
    ): Pair<Int?, Int?> {
        val lo = minimum?.coerceIn(GridFilters.MIN_FILTER_AGE, GridFilters.MAX_FILTER_AGE)
        val hi = maximum?.coerceIn(GridFilters.MIN_FILTER_AGE, GridFilters.MAX_FILTER_AGE)
        return when {
            lo == null && hi == null -> null to null
            lo != null && hi != null && lo > hi -> hi to lo
            else -> lo to hi
        }
    }

    companion object {
        /** Grid results older than this are refetched (quietly, keeping scroll) on return. */
        val RESULTS_EXPIRE_AFTER: Duration = 15.minutes
    }
}

/** A saved [androidx.compose.foundation.lazy.grid.LazyGridState] position. */
data class GridScrollAnchor(
    val index: Int = 0,
    val offset: Int = 0,
)

interface GridFeedRepository {
    suspend fun loadGrid(
        filters: GridFilters,
        cursor: String? = null,
    ): GridFeedUi
}

class ApiGridFeedRepository(
    private val apiClient: PynapliApiClient,
) : GridFeedRepository {
    override suspend fun loadGrid(
        filters: GridFilters,
        cursor: String?,
    ): GridFeedUi =
        when (val result = apiClient.queryGrid(filters.toGridQueryRequest(cursor))) {
            is AppResult.Success ->
                GridFeedUi(
                    cards =
                        result.value.items.mapIndexed { index, card ->
                            card.toGridProfileCardUi(index)
                        },
                    nextCursor = result.value.nextCursor,
                    advancedFiltersApplied = result.value.advancedFiltersApplied,
                    resultLimitReached = result.value.resultLimitReached,
                    resultLimitTier = result.value.resultLimitTier,
                )

            is AppResult.Failure -> throw GridFeedException(
                code = result.error.code,
                message = result.error.message,
            )
        }
}

/**
 * [Immutable] because `cards` is a `List`, and `List` is only an interface — the compiler cannot
 * prove it is not a `MutableList`, so without this the whole class is inferred unstable and every
 * composable taking it becomes non-skippable.
 *
 * The promise holds: every field is a `val`, `GridProfileCardUi` is itself stable, and the list is
 * only ever replaced wholesale (`copy(cards = existing.cards + appended)`), never mutated in place.
 * If that changes — if anything ever hands out a `MutableList` it retains — this annotation becomes
 * a lie and Compose will skip recompositions it should have run.
 */
@Immutable
data class GridFeedUi(
    val cards: List<GridProfileCardUi>,
    val nextCursor: String? = null,
    val advancedFiltersApplied: Boolean = false,
    val resultLimitReached: Boolean = false,
    val resultLimitTier: String? = null,
) {
    val resultLimitPaywallMessage: String?
        get() =
            if (!resultLimitReached) {
                null
            } else {
                when (resultLimitTier) {
                    "free" -> "Social unlocks more nearby profiles."
                    "social" -> "Select unlocks the largest nearby grid."
                    else -> null
                }
            }
}

internal class GridFeedException(
    val code: String,
    override val message: String,
) : RuntimeException(message)

private val sampleGridCards =
    listOf(
        GridProfileCardUi(
            "1",
            "Maya",
            online = true,
            distanceLabel = "800 m",
            tone = GridCardTone.Warm,
            linkedPartner = GridLinkedPartnerUi(userId = "sample-linked-1", displayName = "Noa"),
            linkedPartnerTone = LinkedPartnerTone.Violet,
            distanceMeters = 800,
        ),
        GridProfileCardUi("2", "Alex", online = true, distanceLabel = "1.2 km", tone = GridCardTone.Slate, distanceMeters = 1_200),
        GridProfileCardUi(
            "3",
            "Nico",
            online = false,
            distanceLabel = "2 km",
            tone = GridCardTone.Rust,
            linkedPartner = GridLinkedPartnerUi(userId = "sample-linked-3", displayName = "Lane"),
            linkedPartnerTone = LinkedPartnerTone.Gold,
            distanceMeters = 2_000,
        ),
        GridProfileCardUi("4", "Sam", online = true, distanceLabel = null, tone = GridCardTone.DeepGreen),
        GridProfileCardUi("5", "Ari", online = false, distanceLabel = "4.6 km", tone = GridCardTone.Bronze, distanceMeters = 4_600),
        GridProfileCardUi(
            "6",
            "Drew",
            online = true,
            distanceLabel = "6 km",
            tone = GridCardTone.Graphite,
            linkedPartner = GridLinkedPartnerUi(userId = "sample-linked-6", displayName = "Rae"),
            linkedPartnerTone = LinkedPartnerTone.Pink,
            distanceMeters = 6_000,
        ),
        GridProfileCardUi("7", "Jules", online = false, distanceLabel = "7 km", tone = GridCardTone.Teal, distanceMeters = 7_000),
        GridProfileCardUi(
            "8",
            "Kai",
            online = true,
            distanceLabel = "8.5 km",
            tone = GridCardTone.Rose,
            linkedPartner = GridLinkedPartnerUi(userId = "sample-linked-8", displayName = "Theo"),
            linkedPartnerTone = LinkedPartnerTone.Teal,
            distanceMeters = 8_500,
        ),
        GridProfileCardUi("9", "Rae", online = false, distanceLabel = "9 km", tone = GridCardTone.Cocoa, distanceMeters = 9_000),
        GridProfileCardUi("10", "Theo", online = true, distanceLabel = "10 km", tone = GridCardTone.Moss, distanceMeters = 10_000),
        GridProfileCardUi(
            "11",
            "Noa",
            online = false,
            distanceLabel = "12 km",
            tone = GridCardTone.Stone,
            linkedPartner = GridLinkedPartnerUi(userId = "sample-linked-11", displayName = "Maya"),
            linkedPartnerTone = LinkedPartnerTone.Sun,
            distanceMeters = 12_000,
        ),
        GridProfileCardUi("12", "Lane", online = true, distanceLabel = "14 km", tone = GridCardTone.Midnight, distanceMeters = 14_000),
    )
