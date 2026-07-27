package com.pynapli.app.grid

import androidx.compose.runtime.Immutable
import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.core.result.runCatchingCancellable
import com.pynapli.shared.network.PynapliApiClient
import com.pynapli.shared.network.dto.PresenceDeltaDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

class GridStateHolder(
    private val repository: GridFeedRepository,
    private val snapshotStore: GridSnapshotStore = NoOpGridSnapshotStore,
) {
    private val _state = MutableStateFlow(GridUiState(isRefreshing = true))
    val state: StateFlow<GridUiState> = _state.asStateFlow()

    /**
     * Instant first paint: render the last persisted Grid cards immediately (if any) so the screen
     * isn't blank while the network refresh runs, then load fresh. Only primes when no cards are
     * present yet, so it never clobbers a live grid. Call once on first composition instead of [load].
     */
    suspend fun primeFromCacheThenLoad() {
        if (_state.value.cards.isEmpty()) {
            val cached = runCatchingCancellable { snapshotStore.load() }.getOrElse { emptyList() }
            if (cached.isNotEmpty() && _state.value.cards.isEmpty()) {
                // Show cached cards instantly; a quiet refresh swaps in the live feed atomically
                // (no blank, no scroll reset) per the spec's instant-render rule.
                _state.update { current -> current.copy(cards = cached, isRefreshing = true) }
                load(quiet = true)
                return
            }
        }
        load()
    }

    suspend fun load(quiet: Boolean = false) {
        val before = _state.value
        val keepCardsVisible = quiet && before.cards.isNotEmpty()

        _state.update { current ->
            current.copy(
                isRefreshing = if (keepCardsVisible) current.isRefreshing else true,
                errorCode = if (keepCardsVisible) current.errorCode else null,
                errorMessage = if (keepCardsVisible) current.errorMessage else null,
            )
        }
        runCatchingCancellable { repository.loadGrid(_state.value.filters) }
            .onSuccess { feed ->
                _state.update { current ->
                    current.copy(
                        cards = feed.cards,
                        nextCursor = feed.nextCursor,
                        adSupported = feed.adSupported,
                        paywallMessage = feed.resultLimitPaywallMessage,
                        verificationMessage = null,
                        isRefreshing = false,
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

        _state.update { it.copy(isLoadingMore = true, errorCode = null, errorMessage = null) }
        runCatchingCancellable { repository.loadGrid(current.filters, cursor) }
            .onSuccess { feed ->
                _state.update { existing ->
                    val existingIds = existing.cards.mapTo(mutableSetOf()) { it.id }
                    val appended = feed.cards.filterNot { it.id in existingIds }
                    existing.copy(
                        cards = existing.cards + appended,
                        nextCursor = feed.nextCursor,
                        adSupported = feed.adSupported,
                        paywallMessage = feed.resultLimitPaywallMessage,
                        isLoadingMore = false,
                        errorCode = null,
                        errorMessage = null,
                    )
                }
            }.onFailure { error ->
                _state.update { existing ->
                    existing.copy(
                        isLoadingMore = false,
                        errorCode = (error as? GridFeedException)?.code,
                        errorMessage = error.message ?: "More profiles could not be loaded.",
                    )
                }
            }
    }

    fun removeCardById(userId: String) {
        _state.update { current ->
            current.copy(cards = current.cards.filterNot { it.id == userId })
        }
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
}

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
                    adSupported = result.value.adSupported,
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

class SampleGridFeedRepository : GridFeedRepository {
    override suspend fun loadGrid(
        filters: GridFilters,
        cursor: String?,
    ): GridFeedUi =
        GridFeedUi(
            cards = sampleGridCards,
            adSupported = true,
            advancedFiltersApplied = filters.requiresAdvancedEntitlement,
        )
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
    val adSupported: Boolean = false,
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
