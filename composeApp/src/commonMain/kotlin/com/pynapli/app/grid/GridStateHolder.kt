package com.pynapli.app.grid

import androidx.compose.runtime.Immutable
import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.core.result.runCatchingCancellable
import com.pynapli.shared.network.PynapliApiClient
import com.pynapli.shared.network.dto.GridCountResponseDto
import com.pynapli.shared.network.dto.GridQueryResponseDto
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
        // Locked cards are never saved; filtering again covers a cache written by an older build.
        val cached = runCatchingCancellable { snapshotStore.load() }.getOrElse { emptyList() }.withoutLockedCards()
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

    /** How many of the shown cards came from page one (the slots a page-one refresh speaks for). */
    private var pageOneSize = 0

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
        load(quiet = quiet, keepGatePrompt = false)
    }

    /**
     * [keepGatePrompt]: this load re-asks with the age-gated lifestyle tags taken out, so the
     * verification prompt that caused it stays up when the answer lands.
     */
    private suspend fun load(
        quiet: Boolean,
        keepGatePrompt: Boolean,
    ) {
        // The account this load serves; a sign-out meanwhile makes its filter write a no-op.
        val epoch = accountEpoch
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
            persistFilters(filters, epoch)
            reaskWithout = fetchFirstPage(filters, generation, keepCardsVisible, keepGatePrompt)
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
            load(quiet = quiet, keepGatePrompt = true)
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
    ): GridFilters? {
        var reaskWithout: GridFilters? = null
        runCatchingCancellable { repository.loadGrid(filters) }
            .onSuccess { feed ->
                // Signed out (or reloaded) while this was in flight: never resurrect old cards.
                if (generation != feedGeneration) return@onSuccess
                loadedAt = timeSource.markNow()
                liveAt = loadedAt
                pageOneSize = feed.cards.size
                // The server ranked a grid for this account, so it holds a location for it.
                _serverHasLocation.value = true
                _state.update { current ->
                    current.copy(
                        cards = feed.cards,
                        nextCursor = feed.nextCursor,
                        widened = feed.widened,
                        unlockTier = feed.unlockTier,
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
                saveSnapshot(feed.cards)
            }.onFailure { error ->
                // Superseded (filter change, reload, sign-out) while on the wire: its refusal must not
                // write old filters or prompts over the newer state, or into the next account's.
                if (generation != feedGeneration) return@onFailure
                if ((error as? GridFeedException)?.code == "LOCATION_REQUIRED") {
                    _serverHasLocation.value = false
                }
                val code = (error as? GridFeedException)?.code
                // Every filter is free (founder decision 2, 2026-11-26): the one filter gate left is
                // age verification, which takes out only the lifestyle tags it refused (plan G9).
                // Everything else the viewer chose stays.
                val gated = code == "AGE_VERIFICATION_REQUIRED"
                val allowed = if (gated) filters.copy(lifestyleTags = emptySet()) else filters
                if (allowed != filters) reaskWithout = allowed
                _state.update { current ->
                    // The gate surfaces a prompt instead of a raw error, keeping visible cards.
                    if (keepCardsVisible && !gated) {
                        current.copy(isRefreshing = false)
                    } else {
                        current.copy(
                            isRefreshing = false,
                            filters = if (gated) allowed else current.filters,
                            verificationMessage =
                                if (gated) {
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
     * keeps cards untouched. Filters are preserved and stable card keys animate the diff; the screen
     * returns to the top afterwards, since the new order applies (plan G7).
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
     * from page one that is missing from the fresh page is kept in its slot but loses its dot and
     * caption (presence unknown): it may have gone hidden or incognito, or moved to page two. Cards
     * from later pages are left as they were, since only page one is fetched. Skipped while any
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
            val newCount = newNearbyCount(feed, shownIds)
            val freshById = feed.cards.associateBy { it.id }
            // The shown cards this answer speaks for: all of them when it is the whole result (no next
            // page), otherwise the slots that came from page one. Cards from later pages are beyond
            // what a page-one refresh can see, so they keep their last known state until a reload or
            // a presence delta (a known limitation of refreshing page one only).
            val coveredSlots = if (feed.nextCursor == null) Int.MAX_VALUE else pageOneSize
            _state.update { shownState ->
                shownState.copy(
                    cards =
                        shownState.cards.mapIndexed { index, shown ->
                            val fresh = freshById[shown.id]
                            when {
                                // Same slot, newer facts; the tile colours stay put so nothing flickers.
                                fresh != null -> fresh.copy(linkedPartnerTone = shown.linkedPartnerTone)
                                // Gone from the answer that covers this slot (hidden, incognito, moved
                                // away): never keep a stale green dot. The card stays put (no reorder
                                // under the finger) with its presence unknown until the order is applied.
                                index < coveredSlots -> shown.withPresenceUnknown()
                                else -> shown
                            }
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
        pageOneSize = feed.cards.size
        _state.update { current ->
            current.copy(
                cards = feed.cards,
                nextCursor = feed.nextCursor,
                widened = feed.widened,
                unlockTier = feed.unlockTier,
                paywallMessage = feed.resultLimitPaywallMessage,
                paywallUpgradeTier = feed.resultLimitUpgradeTier,
                isLoadingMore = false,
                newNearbyCount = 0,
            )
        }
        saveSnapshot(feed.cards)
        return true
    }

    /**
     * People in [feed] not on screen yet: the "N new nearby" count. Only real people count; a locked
     * card is a teaser for the plan, and its opaque token may differ between answers anyway.
     */
    private fun newNearbyCount(
        feed: GridFeedUi,
        shownIds: Set<String>,
    ): Int = feed.cards.count { !it.isLockedCard && it.id !in shownIds }

    /**
     * The cold-start cache holds unlocked people only. Locked tokens are per viewer and may not
     * outlive the answer, a cached lock would paint over a plan bought since, and a locked person's
     * photo should not sit on the device beyond the live Grid.
     */
    private suspend fun saveSnapshot(cards: List<GridProfileCardUi>) {
        runCatchingCancellable { snapshotStore.save(cards.withoutLockedCards()) }
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

    private val nearbyShowing = MutableStateFlow(true)

    /** False while the Favorites view covers Nearby: no liveness refresh for cards nobody sees. */
    fun setNearbyShowing(showing: Boolean) {
        nearbyShowing.value = showing
    }

    /**
     * Runs until cancelled: every [LIVE_REFRESH_INTERVAL] while the Grid is on screen, Nearby is
     * showing and the app is visible, a [refreshLive]. Launched with the realtime jobs, which Android
     * and iOS cancel in the background.
     */
    suspend fun runLiveRefresh() {
        combine(_onScreen, _appVisible, nearbyShowing) { onScreen, visible, nearby -> onScreen && visible && nearby }
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
        // G2-2: debounced, and a newer chip tap or apply cancels this one (LatestFilterRequest).
        filterRequests.run(debounced = true) { load() }
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
        // Always asked: the server decides, and an age-verification refusal takes out only the
        // lifestyle tags and keeps the rest (plan G9).
        filterRequests.run(debounced = false) { load(quiet = false, keepGatePrompt = false) }
    }

    /** The newest chip tap or filter apply; an older one still waiting or on the wire is cancelled. */
    private val filterRequests = LatestFilterRequest()

    private val _events = MutableSharedFlow<GridEvent>(extraBufferCapacity = 1)

    /** One-off events for the screen: scroll to top after a plan change reloaded the order. */
    val events: SharedFlow<GridEvent> = _events.asSharedFlow()

    private var filtersRestored = false
    private var persistedFilters: GridFilters? = null

    /**
     * Bumped by [clearForSignOut]. A filter read or write that began under an older value belongs to
     * the account that signed out, so it is dropped (privacy review F9).
     */
    private var accountEpoch = 0

    /** Serialises store reads, writes and the sign-out clear, so a clear always lands last. */
    private val filterStoreLock = Mutex()

    /** Cold start: the viewer's last filters come back before the first query asks with them. */
    private suspend fun restoreFiltersOnce() {
        if (filtersRestored) return
        filtersRestored = true
        val epoch = accountEpoch
        val saved = filterStoreLock.withLock { runCatchingCancellable { filterStore.load() }.getOrNull() } ?: return
        // Signed out while reading: these are the previous account's choices.
        if (epoch != accountEpoch) return
        persistedFilters = saved
        // Only into an untouched Grid: a choice made meanwhile wins over the stored one.
        _state.update { if (it.filters == GridFilters.defaults) it.copy(filters = saved) else it }
    }

    private suspend fun persistFilters(
        filters: GridFilters,
        epoch: Int,
    ) {
        if (epoch != accountEpoch || filters == persistedFilters) return
        persistedFilters = filters
        filterStoreLock.withLock {
            // Signed out while waiting: never write the previous account's choices back.
            if (epoch == accountEpoch) runCatchingCancellable { filterStore.save(filters) }
        }
    }

    suspend fun setRadiusMeters(meters: Int) {
        // Clamped and snapped to the server's step, so the state holds the radius actually searched.
        applyFilters(_state.value.filters.copy(radiusMeters = GridFilters.snapRadius(meters)))
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
                    val cards = existing.cards + appended
                    // Once the list has a lock boundary, its banner sells the rest: a later page that
                    // reports the depth cap must not bring the old row back above the Grid.
                    val lockBoundary = feed.hasLockBoundary || existing.unlockTier != null || cards.any { it.isLockedCard }
                    existing.copy(
                        cards = cards,
                        nextCursor = feed.nextCursor,
                        widened = feed.widened,
                        unlockTier = feed.unlockTier ?: existing.unlockTier,
                        paywallMessage = if (lockBoundary) null else feed.resultLimitPaywallMessage,
                        paywallUpgradeTier = if (lockBoundary) null else feed.resultLimitUpgradeTier,
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

    /**
     * The filter sheet's "Show N people" for a draft the viewer has not applied yet (plan G2-4). The
     * server counts with the Grid's own filters and visibility rules; null when it cannot answer (the
     * sheet then just says "Apply"). The sheet debounces the calls while the viewer edits.
     */
    suspend fun countFor(filters: GridFilters): GridCountResponseDto? = runCatchingCancellable { repository.countGrid(filters.withPartnerFiltersReconciled()) }.getOrNull()

    suspend fun removeCardById(userId: String) {
        val removedIndex = _state.value.cards.indexOfFirst { it.id == userId }
        if (removedIndex in 0 until pageOneSize) pageOneSize -= 1
        _state.update { current ->
            current.copy(cards = current.cards.filterNot { it.id == userId })
        }
        // Someone just blocked or hidden must not come back with the held-back "new nearby" page.
        pendingFeed?.let { pending ->
            val kept = pending.copy(cards = pending.cards.filterNot { it.id == userId })
            val shownIds = _state.value.cards.mapTo(HashSet()) { it.id }
            val newCount = newNearbyCount(kept, shownIds)
            pendingFeed = if (newCount > 0) kept else null
            if (newCount == 0) pendingAt = null
            _state.update { it.copy(newNearbyCount = newCount) }
        }
        // Keep the cold-start cache in step, so someone just blocked or hidden never paints again.
        saveSnapshot(_state.value.cards)
        // Backfill (plan G2-4, Grindr's hide): the cards after the removed one have moved up a slot in
        // place (no reorder, no refresh, the scroll position stays). When that leaves less than a page
        // on screen and the server has more, the next page fills the tail now instead of on scroll. It
        // continues the same cursor, so nobody already shown moves.
        val after = _state.value
        if (removedIndex >= 0 && after.nextCursor != null && after.cards.size < BACKFILL_BELOW) loadMore()
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

    /** The viewer's plan tier as last observed this session; null until the first observation. */
    private var knownPlanTier: String? = null

    /**
     * The client learned the viewer's plan ([tier], a SubscriptionTier value): from the Store after a
     * purchase or restore, or from the Profile Hub on sign-in and resume (a lapse or a partner's
     * benefit ending shows up there). Which cards are locked is the server's call, so a change of
     * plan only means the cards on screen were locked for another plan: the Grid reloads, and the
     * answer unlocks them (an upgrade) or brings the locks back (a downgrade).
     *
     * The first observation only records the tier: the Grid that is loading was answered for it.
     * The reload replaces the order with page one, so it lands at the top (as a refresh does); off
     * screen, the cards are marked expired and the next return reloads them from the top.
     */
    suspend fun onPlanTierObserved(tier: String?) {
        if (tier == null) return
        val previous = knownPlanTier
        knownPlanTier = tier
        if (previous == null || previous == tier) return
        invalidate()
        scrollAnchor = GridScrollAnchor()
        if (!_onScreen.value) return
        load(quiet = true)
        _events.tryEmit(GridEvent.ScrollToTop)
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
        // A chip tap still debouncing would otherwise load, and write filters, after the clear.
        filterRequests.cancelPending()
        accountEpoch += 1
        feedGeneration += 1
        loadedAt = null
        liveAt = null
        pendingFeed = null
        pendingAt = null
        pageOneSize = 0
        // The next account starts from its own stored filters (none on this device yet).
        filtersRestored = false
        persistedFilters = null
        // After any write already under way (the lock), so the clear is the last word.
        filterStoreLock.withLock { runCatchingCancellable { filterStore.clear() } }
        scrollAnchor = GridScrollAnchor()
        travelKeyWhenLeft = null
        // The next account's first plan observation is a baseline, not a change.
        knownPlanTier = null
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

    /**
     * The people on the Grid, for the presence subscription. Locked cards never subscribe: their
     * "locked_" token names nobody, and following a locked person's presence would leak who they are.
     */
    val visibleUserIds: kotlinx.coroutines.flow.Flow<Set<String>> =
        state
            .map { ui -> ui.cards.mapNotNullTo(LinkedHashSet(ui.cards.size)) { card -> card.id.takeUnless { card.isLockedCard } } }
            .distinctUntilChanged()

    private fun GridFilters.toggle(id: QuickFilterId): GridFilters =
        when (id) {
            QuickFilterId.Online -> copy(online = !online)
            QuickFilterId.Verified -> copy(verified = !verified)
            QuickFilterId.Tonight -> copy(tonightAvailable = !tonightAvailable)
            QuickFilterId.Fresh -> copy(fresh = !fresh)
            QuickFilterId.NoSingleMen -> copy(noSingleMen = !noSingleMen)
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

        /** A hide or block that leaves fewer cards than this asks for the next page at once (backfill). */
        const val BACKFILL_BELOW: Int = GridFilters.PAGE_SIZE

        /** Quiet in-place presence refresh cadence while the Grid is visible, and the resume threshold. */
        val LIVE_REFRESH_INTERVAL: Duration = 60.seconds
    }
}

/** One-off Grid events for the screen (StateFlow for state, SharedFlow for these). */
sealed interface GridEvent {
    /** The order was replaced from page one (a plan change reloaded it): show it from the top. */
    data object ScrollToTop : GridEvent
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

    /** The filter sheet's "Show N people" (plan G2-4), or null when it is unavailable. */
    suspend fun countGrid(filters: GridFilters): GridCountResponseDto? = null
}

class ApiGridFeedRepository(
    private val apiClient: PynapliApiClient,
) : GridFeedRepository {
    override suspend fun loadGrid(
        filters: GridFilters,
        cursor: String?,
    ): GridFeedUi =
        when (val result = apiClient.queryGrid(filters.toGridFeedRequest(cursor))) {
            is AppResult.Success -> result.value.toGridFeedUi()

            is AppResult.Failure -> throw GridFeedException(
                code = result.error.code,
                message = result.error.message,
            )
        }

    override suspend fun countGrid(filters: GridFilters): GridCountResponseDto? =
        when (val result = apiClient.countGrid(filters.toGridFeedRequest(cursor = null))) {
            is AppResult.Success -> result.value
            is AppResult.Failure -> null
        }
}

internal fun GridQueryResponseDto.toGridFeedUi(): GridFeedUi =
    GridFeedUi(
        cards = items.mapIndexed { index, card -> card.toGridProfileCardUi(index) },
        nextCursor = nextCursor,
        advancedFiltersApplied = advancedFiltersApplied,
        resultLimitReached = resultLimitReached,
        resultLimitTier = resultLimitTier,
        widened = widened,
        lockedFromIndex = lockedFromIndex,
        unlockTier = unlockTier,
    )

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
    // Section 3: the server's 0-based overall index where locking starts, and the plan that unlocks
    // the locked cards. Null when nothing is locked for this viewer.
    val lockedFromIndex: Int? = null,
    val unlockTier: String? = null,
) {
    /**
     * This answer sells the rest of the Grid with locked cards and their in-grid banner, so the old
     * "Social unlocks more nearby profiles." row above the Grid stays away. It remains the fallback
     * for a server that reports the depth cap without sending locked cards.
     */
    val hasLockBoundary: Boolean get() = lockedFromIndex != null || cards.any { it.isLockedCard }

    val resultLimitPaywallMessage: String?
        get() =
            if (!resultLimitReached || hasLockBoundary) {
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
            if (!resultLimitReached || hasLockBoundary) {
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
