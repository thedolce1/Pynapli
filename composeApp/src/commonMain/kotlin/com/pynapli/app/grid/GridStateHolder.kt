package com.pynapli.app.grid

import androidx.compose.runtime.Immutable
import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.core.result.runCatchingCancellable
import com.pynapli.shared.network.PynapliApiClient
import com.pynapli.shared.network.dto.PresenceDeltaDto
import com.pynapli.shared.network.dto.SubscriptionTier
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

class GridStateHolder(
    private val repository: GridFeedRepository,
    private val snapshotStore: GridSnapshotStore = NoOpGridSnapshotStore,
    /** Injected so the liveness rules (60 s quiet refresh, 15 min expiry) run on a virtual clock in tests. */
    private val timeSource: TimeSource = TimeSource.Monotonic,
    /** The viewer's filters across cold starts, on this device only (cleared on sign-out). */
    private val filterStore: GridFilterStore = NoOpGridFilterStore,
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
        // Before the join check below, which compares the in-flight load's filters with these.
        restoreFiltersOnce()
        if (_state.value.cards.isNotEmpty()) {
            // Returning to the Grid tab: the cards are still here, so keep them (and their order and
            // scroll); presence refreshes in place after a minute, the order only once it expired.
            refreshOnReturn()
            return
        }
        val cached = runCatchingCancellable { snapshotStore.load() }.getOrElse { emptyList() }
        val paintedCache = cached.isNotEmpty() && _state.value.cards.isEmpty()
        if (paintedCache) {
            // Show cached cards instantly; a quiet refresh swaps in the live feed atomically
            // (no blank, no scroll reset) per the spec's instant-render rule. Their presence is days
            // old, so it shows as unknown until that live answer lands.
            _state.update { current ->
                current.copy(cards = cached.map { it.withPresenceUnknown() }, isRefreshing = true, cacheChecked = true)
            }
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

    /** When the shown ORDER last came from the server (a full first-page apply); drives [RESULTS_EXPIRE_AFTER]. */
    private var loadedAt: TimeMark? = null

    /** When the server last answered at all, quiet in-place refreshes included; drives [LIVE_REFRESH_INTERVAL]. */
    private var liveAt: TimeMark? = null

    /**
     * The newer first page a quiet refresh found while it held people not on screen yet. Held back
     * so cards never move under the finger; [applyNewNearby] (the pill) or a refresh applies it.
     */
    private var pendingFeed: GridFeedUi? = null
    private var pendingAt: TimeMark? = null
    private var liveRefreshInFlight = false

    /** True when the cards on screen came from the server longer than [age] ago (or never did). */
    fun resultsOlderThan(age: Duration): Boolean = loadedAt?.let { it.elapsedNow() >= age } ?: true

    /** True when the server last answered (full or quiet in-place refresh) longer than [age] ago, or never. */
    fun liveAnswerOlderThan(age: Duration): Boolean = liveAt?.let { it.elapsedNow() >= age } ?: true

    private fun clearPendingNewNearby() {
        pendingFeed = null
        pendingAt = null
        _state.update { if (it.newNearbyCount == 0) it else it.copy(newNearbyCount = 0) }
    }

    suspend fun load(quiet: Boolean = false) {
        load(quiet = quiet, keepGatePrompt = false, openPaywallOnRefusal = false)
    }

    /**
     * [keepGatePrompt]: this load re-asks with the paid (or age-gated) filters taken out, so the
     * paywall or verification prompt that caused it stays up when the answer lands.
     * [openPaywallOnRefusal]: the viewer just chose these filters, so an entitlement refusal opens
     * the plans. A cold-start restore or a quiet location refresh never pops the paywall unasked.
     */
    private suspend fun load(
        quiet: Boolean,
        keepGatePrompt: Boolean,
        openPaywallOnRefusal: Boolean,
    ) {
        restoreFiltersOnce()
        _state.update { if (it.cacheChecked) it else it.copy(cacheChecked = true) }
        val before = _state.value
        val keepCardsVisible = quiet && before.cards.isNotEmpty()
        feedGeneration += 1
        // Whatever this load answers supersedes a held-back "new nearby" page.
        clearPendingNewNearby()

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
        var reaskWithout: GridFilters? = null
        try {
            // Inside the try, so a cancellation here still releases the in-flight marker.
            persistFilters(filters)
            reaskWithout = fetchFirstPage(filters, generation, keepCardsVisible, keepGatePrompt, openPaywallOnRefusal)
            answered = true
        } finally {
            // Always released, cancellation included, so a joiner never waits on a dead request.
            if (inFlightLoad === flight) inFlightLoad = null
            val applied = answered && generation == feedGeneration
            if (applied) appliedGeneration = generation
            flight.applied.complete(applied)
        }
        // A gate refused a filter: the rest of the viewer's choices still deserve a Grid. Bounded:
        // the re-ask carries no gated filter, so it cannot be refused for one again.
        if (reaskWithout != null && generation == feedGeneration) {
            load(quiet = quiet, keepGatePrompt = true, openPaywallOnRefusal = false)
        }
    }

    /**
     * One first-page request; its answer (cards or error) is applied only while [generation] is
     * current. Returns the filters to re-ask with when a gate refused only some of them.
     */
    private suspend fun fetchFirstPage(
        filters: GridFilters,
        generation: Int,
        keepCardsVisible: Boolean,
        keepGatePrompt: Boolean,
        openPaywallOnRefusal: Boolean,
    ): GridFilters? {
        var reaskWithout: GridFilters? = null
        runCatchingCancellable { repository.loadGrid(filters) }
            .onSuccess { feed ->
                // Signed out (or reloaded) while this was in flight: never resurrect old cards.
                if (generation != feedGeneration) return@onSuccess
                loadedAt = timeSource.markNow()
                liveAt = loadedAt
                // The server ranked a grid for this account, so it holds a location for it.
                _serverHasLocation.value = true
                _state.update { current ->
                    current.copy(
                        cards = feed.cards,
                        nextCursor = feed.nextCursor,
                        widened = feed.widened,
                        paywallMessage = feed.resultLimitPaywallMessage ?: current.paywallMessage.takeIf { keepGatePrompt },
                        paywallUpgradeTier = feed.resultLimitUpgradeTier ?: current.paywallUpgradeTier.takeIf { keepGatePrompt },
                        verificationMessage = current.verificationMessage.takeIf { keepGatePrompt },
                        isRefreshing = false,
                        isLoadingMore = false,
                        errorCode = null,
                        errorMessage = null,
                    )
                }
                // Persist the fresh cards for an instant first paint on next open (best-effort).
                runCatchingCancellable { snapshotStore.save(feed.cards) }
            }.onFailure { error ->
                // Superseded (filter change, reload, sign-out) while on the wire: its refusal must not
                // write old filters or prompts over the newer state, or into the next account's.
                if (generation != feedGeneration) return@onFailure
                if ((error as? GridFeedException)?.code == "LOCATION_REQUIRED") {
                    _serverHasLocation.value = false
                }
                val code = (error as? GridFeedException)?.code
                val entitlementRequired = code == "ENTITLEMENT_REQUIRED"
                val verificationRequired = code == "AGE_VERIFICATION_REQUIRED"
                // Each gate takes out only what it refused (plan G9): the paid filters, or the
                // age-gated lifestyle tags. Everything else the viewer chose stays.
                val allowed =
                    when {
                        entitlementRequired -> filters.withoutPaidFilters()
                        verificationRequired -> filters.copy(lifestyleTags = emptySet())
                        else -> filters
                    }
                if (entitlementRequired && openPaywallOnRefusal) _events.tryEmit(GridEvent.OpenPaywall)
                if (allowed != filters) reaskWithout = allowed
                _state.update { current ->
                    // Both gates surface a prompt instead of a raw error, keeping visible cards.
                    val gated = entitlementRequired || verificationRequired
                    if (keepCardsVisible && !gated) {
                        current.copy(isRefreshing = false)
                    } else {
                        current.copy(
                            isRefreshing = false,
                            filters = if (gated) allowed else current.filters,
                            paywallMessage =
                                if (entitlementRequired) {
                                    PAID_FILTER_PAYWALL_MESSAGE
                                } else {
                                    current.paywallMessage
                                },
                            paywallUpgradeTier =
                                if (entitlementRequired) {
                                    SubscriptionTier.SOCIAL
                                } else {
                                    current.paywallUpgradeTier
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
        return reaskWithout
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

    /**
     * A quiet liveness refresh (plan G7) that never moves a card under the finger: it fetches the
     * first page for the current filters, updates the cards already on screen in place (online dot,
     * active-ago, New marker, photo) and, when the answer holds people not on screen, keeps that page
     * aside and raises [GridUiState.newNearbyCount] for the "N new nearby" pill.
     *
     * Deliberately not a [load]: it never bumps the feed generation (a page being appended survives),
     * never touches filters, the spinner or an error, and a failure is a silent no-op. A shown card
     * missing from the fresh page is kept: it may simply sit on page two now. Skipped while any
     * other request for the Grid is on the wire.
     */
    suspend fun refreshLive() {
        val current = _state.value
        if (current.cards.isEmpty() || current.isRefreshing || current.isLoadingMore) return
        if (inFlightLoad != null || liveRefreshInFlight) return
        val generation = feedGeneration
        val filters = current.filters
        liveRefreshInFlight = true
        try {
            val feed = runCatchingCancellable { repository.loadGrid(filters) }.getOrNull() ?: return
            // A reload, filter change or sign-out happened meanwhile: this answer describes old cards.
            if (generation != feedGeneration || _state.value.filters != filters) return
            val answeredAt = timeSource.markNow()
            liveAt = answeredAt
            _serverHasLocation.value = true
            val shownIds = _state.value.cards.mapTo(HashSet()) { it.id }
            val newCount = feed.cards.count { it.id !in shownIds }
            val freshById = feed.cards.associateBy { it.id }
            _state.update { shownState ->
                shownState.copy(
                    cards =
                        shownState.cards.map { shown ->
                            // Same slot, newer facts; the tile colours stay put so nothing flickers.
                            freshById[shown.id]?.copy(tone = shown.tone, linkedPartnerTone = shown.linkedPartnerTone) ?: shown
                        },
                    newNearbyCount = newCount,
                )
            }
            pendingFeed = if (newCount > 0) feed else null
            pendingAt = if (newCount > 0) answeredAt else null
        } finally {
            liveRefreshInFlight = false
        }
    }

    /**
     * The "N new nearby" pill: applies the held-back first page (new order, cursor and paywall
     * together). Returns true when it replaced the cards, so the screen scrolls to the top. With a
     * load already on the wire nothing is applied: that load answers instead.
     */
    suspend fun applyNewNearby(): Boolean {
        val feed = pendingFeed ?: return false
        val answeredAt = pendingAt
        if (inFlightLoad != null) {
            clearPendingNewNearby()
            return false
        }
        // A page-two request started against the old order must not append onto the new one.
        feedGeneration += 1
        appliedGeneration = feedGeneration
        pendingFeed = null
        pendingAt = null
        loadedAt = answeredAt ?: timeSource.markNow()
        _state.update { current ->
            current.copy(
                cards = feed.cards,
                nextCursor = feed.nextCursor,
                widened = feed.widened,
                paywallMessage = feed.resultLimitPaywallMessage,
                paywallUpgradeTier = feed.resultLimitUpgradeTier,
                isLoadingMore = false,
                newNearbyCount = 0,
            )
        }
        runCatchingCancellable { snapshotStore.save(feed.cards) }
        return true
    }

    /**
     * Tab return or app resume: an expired order (older than [RESULTS_EXPIRE_AFTER]) is replaced
     * quietly; otherwise presence older than [LIVE_REFRESH_INTERVAL] refreshes in place through
     * [refreshLive]. Fresh cards are left alone.
     */
    suspend fun refreshOnReturn() {
        if (_state.value.cards.isEmpty()) return
        if (resultsOlderThan(RESULTS_EXPIRE_AFTER)) {
            if (!joinInFlightLoad()) load(quiet = true)
        } else if (liveAnswerOlderThan(LIVE_REFRESH_INTERVAL)) {
            refreshLive()
        }
    }

    /** App resume: only while the Grid is the screen in front; a later tab return covers the rest. */
    suspend fun refreshOnResume() {
        if (_onScreen.value) refreshOnReturn()
    }

    private val _appVisible = MutableStateFlow(true)

    /** The app (web: the tab) is visible; also gates the presence heartbeat. */
    val appVisible: StateFlow<Boolean> = _appVisible.asStateFlow()

    /**
     * Web only: whether the browser tab is visible. Android and iOS cancel [runLiveRefresh] with the
     * realtime jobs in the background; a browser tab keeps its jobs, so the shell reports it here.
     */
    fun setAppVisible(visible: Boolean) {
        _appVisible.value = visible
    }

    private val _nearbyShowing = MutableStateFlow(true)

    /** False while the Favorites view covers Nearby: no liveness refresh for cards nobody sees. */
    fun setNearbyShowing(showing: Boolean) {
        _nearbyShowing.value = showing
    }

    /**
     * Runs until cancelled: every [LIVE_REFRESH_INTERVAL] while the Grid is on screen, Nearby is
     * showing and the app is visible, a [refreshLive]. Launched with the realtime jobs, which Android
     * and iOS cancel in the background.
     */
    suspend fun runLiveRefresh() {
        combine(_onScreen, _appVisible, _nearbyShowing) { onScreen, visible, nearby -> onScreen && visible && nearby }
            .distinctUntilChanged()
            .collectLatest { live ->
                if (!live) return@collectLatest
                while (true) {
                    delay(LIVE_REFRESH_INTERVAL)
                    // A pull-to-refresh or resume refresh answered moments ago: skip this tick.
                    if (liveAnswerOlderThan(LIVE_REFRESH_INTERVAL / 2)) refreshLive()
                }
            }
    }

    suspend fun toggleQuickFilter(id: QuickFilterId) {
        _state.update { current ->
            current.copy(
                filters = current.filters.toggle(id),
                paywallMessage = null,
                paywallUpgradeTier = null,
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
                paywallUpgradeTier = null,
                verificationMessage = null,
                errorCode = null,
                errorMessage = null,
            )
        }
        // Always asked, never pre-stripped from the client's view of the plan (which can lag a
        // purchase): the server decides, and a refusal takes out only the paid filters, keeps the
        // rest and, because the viewer just chose them, opens the plans (plan G9).
        load(quiet = false, keepGatePrompt = false, openPaywallOnRefusal = true)
    }

    private val _events = MutableSharedFlow<GridEvent>(extraBufferCapacity = 1)

    /** One-off events for the screen: open the paywall after a paid filter was taken out. */
    val events: SharedFlow<GridEvent> = _events.asSharedFlow()

    private var filtersRestored = false
    private var persistedFilters: GridFilters? = null

    /** Cold start: the viewer's last filters come back before the first query asks with them. */
    private suspend fun restoreFiltersOnce() {
        if (filtersRestored) return
        filtersRestored = true
        val saved = runCatchingCancellable { filterStore.load() }.getOrNull() ?: return
        persistedFilters = saved
        // Only into an untouched Grid: a choice made meanwhile wins over the stored one.
        _state.update { if (it.filters == GridFilters.defaults) it.copy(filters = saved) else it }
    }

    private suspend fun persistFilters(filters: GridFilters) {
        if (filters == persistedFilters) return
        persistedFilters = filters
        runCatchingCancellable { filterStore.save(filters) }
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
                        widened = feed.widened,
                        paywallMessage = feed.resultLimitPaywallMessage,
                        paywallUpgradeTier = feed.resultLimitUpgradeTier,
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
        // Someone just blocked or hidden must not come back with the held-back "new nearby" page.
        pendingFeed?.let { pending ->
            val kept = pending.copy(cards = pending.cards.filterNot { it.id == userId })
            val shownIds = _state.value.cards.mapTo(HashSet()) { it.id }
            val newCount = kept.cards.count { it.id !in shownIds }
            pendingFeed = if (newCount > 0) kept else null
            if (newCount == 0) pendingAt = null
            _state.update { it.copy(newNearbyCount = newCount) }
        }
        // Keep the cold-start cache in step, so someone just blocked or hidden never paints again.
        runCatchingCancellable { snapshotStore.save(_state.value.cards) }
    }

    /**
     * Travel mode as it was when the Grid last left the screen. Travel can be switched on or off from
     * Swipe meanwhile; the Grid then reloads on return instead of keeping another place's cards.
     */
    var travelKeyWhenLeft: String? = null

    private val _serverHasLocation = MutableStateFlow<Boolean?>(null)

    /**
     * Whether the server holds a location for this account, as the last live first-page answer this
     * session showed it: true after a grid came back, false after `LOCATION_REQUIRED`, null before
     * any live answer (cached cards say nothing). Read by the silent location refresh rule (SilentLocationRefreshRule); no
     * location is echoed to the client for this.
     */
    val serverHasLocation: StateFlow<Boolean?> = _serverHasLocation.asStateFlow()

    private val _onScreen = MutableStateFlow(false)

    /** True while the Grid screen is composed (its tab is showing); feeds the notification ask timing. */
    val onScreen: StateFlow<Boolean> = _onScreen.asStateFlow()

    fun setOnScreen(visible: Boolean) {
        _onScreen.value = visible
    }

    /** Marks the cards as expired, so the next return to the Grid refetches them quietly. */
    fun invalidate() {
        loadedAt = null
        liveAt = null
        // Another place's held-back page must never be applied over the new place's cards.
        clearPendingNewNearby()
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
        liveAt = null
        pendingFeed = null
        pendingAt = null
        // The next account starts from its own stored filters (none on this device yet).
        filtersRestored = false
        persistedFilters = null
        runCatchingCancellable { filterStore.clear() }
        scrollAnchor = GridScrollAnchor()
        travelKeyWhenLeft = null
        _serverHasLocation.value = null
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
            // "Went offline" arrives once the server's online hold ends, so the newest bucket applies.
            val activeAgo = if (delta.online) GridActiveAgo.ONLINE else GridActiveAgo.MINUTES_15
            updated[targetIndex] = target.copy(online = delta.online, activeAgo = activeAgo)
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
            QuickFilterId.Fresh -> copy(fresh = !fresh)
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
        /** A Grid order older than this is replaced (quietly, keeping scroll) on tab return or resume. */
        val RESULTS_EXPIRE_AFTER: Duration = 15.minutes

        /** Quiet in-place presence refresh cadence while the Grid is visible, and the resume threshold. */
        val LIVE_REFRESH_INTERVAL: Duration = 60.seconds
    }
}

/** One-off Grid events for the screen (StateFlow for state, SharedFlow for these). */
sealed interface GridEvent {
    /** A paid filter was taken out (the client knew, or the server refused): show the plans. */
    data object OpenPaywall : GridEvent
}

internal const val PAID_FILTER_PAYWALL_MESSAGE: String = "Social or Select unlocks Lifestyle and Logistics filters. Your other filters still apply."

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
                    widened = result.value.widened,
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
    // The server widened a sparse first page (and every page continuing its cursor).
    val widened: Boolean = false,
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

    /** The plan that lifts the depth cap the viewer hit: Social from Free, Select from Social. */
    val resultLimitUpgradeTier: String?
        get() =
            if (!resultLimitReached) {
                null
            } else {
                when (resultLimitTier) {
                    SubscriptionTier.FREE -> SubscriptionTier.SOCIAL
                    SubscriptionTier.SOCIAL -> SubscriptionTier.SELECT
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
