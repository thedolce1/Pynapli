package com.pynapli.app.grid

import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.pynapli.app.conversation.ConversationStateHolder
import com.pynapli.app.location.LocationUiState
import com.pynapli.app.location.TravelLocationStateHolder
import com.pynapli.app.location.TravelLocationUiState
import com.pynapli.app.profile.GridProfileBrowser
import com.pynapli.app.profile.ProfileDetailStateHolder
import com.pynapli.app.profilelink.ProfileLinksStateHolder
import com.pynapli.app.swipe.TravelModeSheet
import com.pynapli.app.tonight.PlayingTonightStateHolder
import com.pynapli.app.tonight.PlayingTonightUiState
import com.pynapli.shared.settings.AppSettings
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone

@Composable
fun GridScreen(
    modifier: Modifier = Modifier,
    stateHolder: GridStateHolder,
    profileDetailStateHolder: ProfileDetailStateHolder,
    conversationStateHolder: ConversationStateHolder,
    viewerUserId: String? = null,
    locationState: LocationUiState = LocationUiState(),
    profileImageUrl: String? = null,
    profileLinksStateHolder: ProfileLinksStateHolder? = null,
    savedGridFilterStateHolder: SavedGridFilterStateHolder? = null,
    playingTonightStateHolder: PlayingTonightStateHolder? = null,
    lifestyleFiltersAllowed: Boolean = true,
    distanceUnit: String = AppSettings.UNIT_METRIC,
    // Restored from the holder so returning to the Grid tab lands where the viewer left it.
    gridState: LazyGridState = rememberLazyGridState(stateHolder.scrollAnchor.index, stateHolder.scrollAnchor.offset),
    onUseCurrentLocation: () -> Unit = {},
    onProfileIconClick: () -> Unit = {},
    onOpenStore: () -> Unit = {},
    onOpenAgeVerification: () -> Unit = {},
    onOpenTonight: () -> Unit = {},
    travelLocationStateHolder: TravelLocationStateHolder? = null,
    favoritesStateHolder: FavoritesStateHolder? = null,
    // The profile's "Verify to message" opens the existing photo-verification flow.
    onOpenPhotoVerification: (() -> Unit)? = null,
    // Travel mode is a Select perk, so its paywall opens on Select rather than the plan list.
    onOpenTravelStore: () -> Unit = onOpenStore,
    // Section 3: a locked tile, the boundary banner and a locked pager page open the plans sheet on
    // the server's unlockTier ("social" or "select").
    onOpenUnlockStore: (unlockTier: String?) -> Unit = { onOpenStore() },
) {
    var travelSheetVisible by remember { mutableStateOf(false) }
    val state by stateHolder.state.collectAsState()
    val favoritesState by favoritesStateHolder?.state?.collectAsState()
        ?: remember { mutableStateOf<FavoritesUiState?>(null) }
    val favoritesGridState =
        favoritesStateHolder?.let { holder -> rememberLazyGridState(holder.scrollAnchor.index, holder.scrollAnchor.offset) }
    val travelState by travelLocationStateHolder?.state?.collectAsState()
        ?: remember { mutableStateOf(TravelLocationUiState()) }
    val profileState by profileDetailStateHolder.state.collectAsState()
    val profileLinksState by profileLinksStateHolder?.state?.collectAsState()
        ?: remember { mutableStateOf(null) }
    val savedFiltersState by savedGridFilterStateHolder?.state?.collectAsState()
        ?: remember { mutableStateOf(SavedGridFiltersUiState()) }
    // PLAYING_TONIGHT_SPEC: sessions are the only Tonight model; the filter sheet only links to them.
    val tonightState by playingTonightStateHolder?.state?.collectAsState()
        ?: remember { mutableStateOf(PlayingTonightUiState()) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(stateHolder) {
        // Travel switched on or off (from Swipe) while the Grid was away: the cards are another place's.
        val leftWith = stateHolder.travelKeyWhenLeft
        if (leftWith != null && leftWith != travelState.current.travelKey()) stateHolder.invalidate()
        stateHolder.primeFromCacheThenLoad()
    }

    // On screen for as long as this is composed: the notification ask waits for some Grid time, and
    // the 60 s liveness refresh runs only then.
    DisposableEffect(stateHolder) {
        stateHolder.setOnScreen(true)
        onDispose { stateHolder.setOnScreen(false) }
    }

    // A plan change reloaded the order from page one: show it from the top.
    LaunchedEffect(stateHolder, gridState) {
        stateHolder.events.collect { event ->
            when (event) {
                GridEvent.ScrollToTop -> gridState.scrollToItem(0)
            }
        }
    }

    // The Favorites view covers Nearby: pause Nearby's liveness refresh meanwhile.
    val nearbyShowing = favoritesState?.showing != true
    LaunchedEffect(stateHolder, nearbyShowing) { stateHolder.setNearbyShowing(nearbyShowing) }

    // Leaving the tab disposes this screen; keep both grids' scroll for the way back.
    DisposableEffect(gridState, favoritesGridState) {
        onDispose {
            stateHolder.travelKeyWhenLeft =
                travelLocationStateHolder
                    ?.state
                    ?.value
                    ?.current
                    .travelKey()
            stateHolder.rememberScroll(gridState.firstVisibleItemIndex, gridState.firstVisibleItemScrollOffset)
            if (favoritesGridState != null) {
                favoritesStateHolder?.rememberScroll(favoritesGridState.firstVisibleItemIndex, favoritesGridState.firstVisibleItemScrollOffset)
            }
        }
    }

    // A star or un-star from the profile sheet updates the Favorites view without a refetch.
    LaunchedEffect(profileDetailStateHolder, favoritesStateHolder) {
        val holder = favoritesStateHolder ?: return@LaunchedEffect
        profileDetailStateHolder.favoriteChanges.collect(holder::onFavoriteChanged)
    }

    LaunchedEffect(savedGridFilterStateHolder) {
        savedGridFilterStateHolder?.load()
    }

    LaunchedEffect(playingTonightStateHolder) {
        playingTonightStateHolder?.refreshCurrentSession()
    }

    // Travel mode moves the Grid too, so the Grid has to know when it is on (it was only loaded by
    // the Swipe screen before).
    LaunchedEffect(travelLocationStateHolder) {
        travelLocationStateHolder?.load()
    }

    GridScreenContent(
        state = state,
        savedFiltersState = savedFiltersState,
        tonightSessionActive = tonightState.currentSession != null,
        lifestyleFiltersAllowed = lifestyleFiltersAllowed,
        locationState = locationState,
        profileImageUrl = profileImageUrl,
        distanceUnit = distanceUnit,
        gridState = gridState,
        onQuickFilterToggled = { id -> scope.launch { stateHolder.toggleQuickFilter(id) } },
        onApplyFilters = { filters -> scope.launch { stateHolder.applyFilters(filters) } },
        onClearAllFilters = { scope.launch { stateHolder.clearAllFilters() } },
        onSavePreset = { name, filters ->
            savedGridFilterStateHolder?.let { holder -> scope.launch { holder.save(name, filters) } }
        },
        onDeleteSavedPreset = { id ->
            savedGridFilterStateHolder?.let { holder -> scope.launch { holder.delete(id) } }
        },
        onLoadMore = { scope.launch { stateHolder.loadMore() } },
        // An explicit refresh applies the new order, so it lands at the top (pull-to-refresh is
        // already there; the wide-window Refresh button may not be).
        onRefresh = {
            scope.launch {
                stateHolder.refresh()
                gridState.scrollToItem(0)
            }
        },
        onShowNewNearby = {
            scope.launch { if (stateHolder.applyNewNearby()) gridState.scrollToItem(0) }
        },
        onOpenTravel = travelLocationStateHolder?.let { { travelSheetVisible = true } },
        travelCityName = travelState.current?.city,
        onTurnOffTravel = {
            travelLocationStateHolder?.let { holder ->
                scope.launch {
                    holder.clear()
                    stateHolder.refresh()
                }
            }
        },
        // A locked tile sells the plan that unlocks it; it never opens (or fetches) a profile.
        onProfileSelected = { card ->
            when (val tap = routeGridTileTap(card, state.unlockTier)) {
                is GridTileTap.OpenPaywall -> onOpenUnlockStore(tap.unlockTier)
                is GridTileTap.OpenProfile -> scope.launch { profileDetailStateHolder.open(tap.card) }
            }
        },
        onUseCurrentLocation = onUseCurrentLocation,
        onProfileIconClick = onProfileIconClick,
        onOpenStore = onOpenStore,
        onOpenAgeVerification = onOpenAgeVerification,
        onOpenTonight = onOpenTonight,
        modifier = modifier,
        favoritesState = favoritesState,
        favoritesGridState = favoritesGridState,
        onShowNearby = { favoritesStateHolder?.showNearby() },
        onShowFavorites = { favoritesStateHolder?.let { holder -> scope.launch { holder.show() } } },
        onRefreshFavorites = { favoritesStateHolder?.let { holder -> scope.launch { holder.refresh() } } },
        onLoadMoreFavorites = { favoritesStateHolder?.let { holder -> scope.launch { holder.loadMore() } } },
        onOpenUnlockStore = onOpenUnlockStore,
    )

    // Travel mode from the Grid header (plan G8): the same picker and holder Swipe uses, no new travel
    // logic. Every change reloads the Grid for the new place (a held-back page is another place's).
    if (travelSheetVisible && travelLocationStateHolder != null) {
        val reloadForNewPlace: suspend () -> Unit = {
            stateHolder.invalidate()
            stateHolder.refresh()
            gridState.scrollToItem(0)
        }
        LaunchedEffect(Unit) { travelLocationStateHolder.loadTrips() }
        TravelModeSheet(
            state = travelState,
            onDismiss = { travelSheetVisible = false },
            onSearchQueryChange = { query -> travelLocationStateHolder.updateSearchQuery(query) },
            onRunSearch = { scope.launch { travelLocationStateHolder.runSearch() } },
            onPickPlace = { place ->
                scope.launch {
                    // Close only once the trip is on: a refusal stays in the sheet with its reason.
                    if (travelLocationStateHolder.setActive(place)) {
                        travelSheetVisible = false
                        reloadForNewPlace()
                    }
                }
            },
            onClear = {
                travelSheetVisible = false
                scope.launch {
                    travelLocationStateHolder.clear()
                    reloadForNewPlace()
                }
            },
            onOpenStore = {
                travelSheetVisible = false
                onOpenTravelStore()
            },
            onOpenAgeVerification = {
                travelSheetVisible = false
                onOpenAgeVerification()
            },
            onSelectPlace = { place -> travelLocationStateHolder.selectPlace(place) },
            onPlanTrip = { place, startsOn, endsOn, announce ->
                scope.launch {
                    val zone = TimeZone.currentSystemDefault().id
                    // A trip starting today is already on: the Grid moves with it.
                    if (travelLocationStateHolder.planTrip(place, startsOn, endsOn, zone, announce)) reloadForNewPlace()
                }
            },
            onCancelTrip = { tripId ->
                scope.launch {
                    travelLocationStateHolder.cancelTrip(tripId)
                    reloadForNewPlace()
                }
            },
        )
    }

    // Profile open (GRID_IMPLEMENTATION_SPEC "Profile open"): swipe through the list on show, in its
    // order; block/hide drops the card here and the pager moves on.
    val browsingFavorites = favoritesState?.showing == true && favoritesStateHolder != null
    GridProfileBrowser(
        profileState = profileState,
        profileDetailStateHolder = profileDetailStateHolder,
        conversationStateHolder = conversationStateHolder,
        cards = if (browsingFavorites) favoritesState?.cards.orEmpty() else state.cards,
        hasMore = if (browsingFavorites) favoritesState?.nextCursor != null else state.nextCursor != null,
        isLoadingMore = if (browsingFavorites) favoritesState?.isLoadingMore == true else state.isLoadingMore,
        onLoadMore = {
            if (browsingFavorites) {
                favoritesStateHolder?.let { holder -> scope.launch { holder.loadMore() } }
            } else {
                scope.launch { stateHolder.loadMore() }
            }
        },
        onRemoveCard = { userId ->
            stateHolder.removeCardById(userId)
            favoritesStateHolder?.removeCardById(userId)
        },
        viewerUserId = viewerUserId,
        profileLinkActionFor = { targetUserId ->
            profileLinksState?.profileLinkActionFor(targetUserId = targetUserId, viewerUserId = viewerUserId)
        },
        onInviteProfileLink = { targetUserId ->
            scope.launch { profileLinksStateHolder?.createInvitation(targetUserId) }
        },
        distanceUnit = distanceUnit,
        onOpenPhotoVerification = onOpenPhotoVerification,
        // The pager's locked "See plans" page: the same plan the banner sells.
        onOpenStore = { onOpenUnlockStore(state.unlockTier) },
    )
}

private fun com.pynapli.shared.network.dto.TravelLocationDto?.travelKey(): String = this?.let { "${it.city}|${it.latitude}|${it.longitude}|${it.activeFrom}" } ?: "none"
