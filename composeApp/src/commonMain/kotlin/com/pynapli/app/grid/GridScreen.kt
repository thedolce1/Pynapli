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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.pynapli.app.conversation.ConversationStateHolder
import com.pynapli.app.location.LocationUiState
import com.pynapli.app.location.TravelLocationStateHolder
import com.pynapli.app.location.TravelLocationUiState
import com.pynapli.app.profile.ProfileDetailSheet
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
    // Travel mode is a Select perk, so its paywall opens on Select rather than the plan list.
    onOpenTravelStore: () -> Unit = onOpenStore,
    // From the viewer's plan (Social/Select true, Free false, null until it loads). Display only:
    // it puts locks on paid chips; the server decides what a query may use.
    advancedFiltersAllowed: Boolean? = null,
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

    // A paid filter was taken out (only that one): show the plans.
    val openStore by rememberUpdatedState(onOpenStore)
    LaunchedEffect(stateHolder) {
        stateHolder.events.collect { event ->
            when (event) {
                GridEvent.OpenPaywall -> openStore()
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
        onApplySavedPreset = { dto ->
            scope.launch { stateHolder.applyFilters(GridFilters.fromGridQueryRequest(dto.payload)) }
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
        advancedFiltersAllowed = advancedFiltersAllowed,
        travelCityName = travelState.current?.city,
        onTurnOffTravel = {
            travelLocationStateHolder?.let { holder ->
                scope.launch {
                    holder.clear()
                    stateHolder.refresh()
                }
            }
        },
        onProfileSelected = { card ->
            scope.launch { profileDetailStateHolder.open(card) }
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

    LaunchedEffect(profileState.blockedTargetUserId) {
        val blockedId = profileDetailStateHolder.consumeBlockedTargetUserId() ?: return@LaunchedEffect
        stateHolder.removeCardById(blockedId)
        favoritesStateHolder?.removeCardById(blockedId)
        profileDetailStateHolder.dismiss()
    }

    LaunchedEffect(profileState.hiddenTargetUserId) {
        val hiddenId = profileDetailStateHolder.consumeHiddenTargetUserId() ?: return@LaunchedEffect
        stateHolder.removeCardById(hiddenId)
        favoritesStateHolder?.removeCardById(hiddenId)
        profileDetailStateHolder.dismiss()
    }

    if (profileState.visible) {
        ProfileDetailSheet(
            state = profileState,
            onDismiss = profileDetailStateHolder::dismiss,
            onSpark = {
                scope.launch { profileDetailStateHolder.sendSpark() }
            },
            onRequestAlbum = {
                scope.launch { profileDetailStateHolder.requestAlbumAccess() }
            },
            onViewPrivateAlbum = {
                scope.launch { profileDetailStateHolder.loadPrivateAlbum() }
            },
            onMessage = {
                val card = profileState.selectedCard
                if (card != null) {
                    profileDetailStateHolder.dismiss()
                    scope.launch {
                        conversationStateHolder.openDirect(
                            card = card,
                            viewerUserId = viewerUserId,
                        )
                    }
                }
            },
            onBlock = { reason ->
                scope.launch { profileDetailStateHolder.blockUser(reason) }
            },
            onHide = {
                scope.launch { profileDetailStateHolder.hideProfile() }
            },
            onReportAlbum = { albumId, category -> scope.launch { profileDetailStateHolder.submitAlbumReport(albumId, category) } },
            onReport = { category, note ->
                scope.launch { profileDetailStateHolder.submitReport(category, note) }
            },
            profileLinkAction =
                profileLinksState?.profileLinkActionFor(
                    targetUserId = profileState.profile?.userId ?: profileState.selectedCard?.id,
                    viewerUserId = viewerUserId,
                ),
            onInviteProfileLink = {
                val targetUserId = profileState.profile?.userId ?: profileState.selectedCard?.id
                if (targetUserId != null) {
                    scope.launch { profileLinksStateHolder?.createInvitation(targetUserId) }
                }
            },
            onOpenLinkedPartner = { partner ->
                scope.launch { profileDetailStateHolder.openLinkedPartner(partner) }
            },
            distanceUnit = distanceUnit,
            onToggleFavorite = { scope.launch { profileDetailStateHolder.toggleFavorite() } },
        )
    }
}

private fun com.pynapli.shared.network.dto.TravelLocationDto?.travelKey(): String = this?.let { "${it.city}|${it.latitude}|${it.longitude}|${it.activeFrom}" } ?: "none"
