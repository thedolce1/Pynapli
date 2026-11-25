package com.pynapli.app.grid

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.pynapli.app.design.PynapliWindowSizeClass
import com.pynapli.app.design.currentPynapliWindowSizeClass
import com.pynapli.app.location.LocationUiState
import com.pynapli.shared.network.dto.SavedGridFilterDto

@Composable
internal fun GridScreenContent(
    state: GridUiState,
    savedFiltersState: SavedGridFiltersUiState,
    tonightSessionActive: Boolean,
    lifestyleFiltersAllowed: Boolean,
    locationState: LocationUiState,
    profileImageUrl: String?,
    distanceUnit: String,
    gridState: LazyGridState,
    onQuickFilterToggled: (QuickFilterId) -> Unit,
    onApplyFilters: (GridFilters) -> Unit,
    onClearAllFilters: () -> Unit,
    onSavePreset: (String, GridFilters) -> Unit,
    onApplySavedPreset: (SavedGridFilterDto) -> Unit,
    onDeleteSavedPreset: (String) -> Unit,
    onLoadMore: () -> Unit,
    onRefresh: () -> Unit,
    onProfileSelected: (GridProfileCardUi) -> Unit,
    onUseCurrentLocation: () -> Unit,
    onProfileIconClick: () -> Unit,
    onOpenStore: () -> Unit,
    onOpenAgeVerification: () -> Unit = {},
    onOpenTonight: () -> Unit = {},
    modifier: Modifier = Modifier,
    // Travel mode destination when it is on. The Grid is showing that city, not where the viewer is.
    travelCityName: String? = null,
    onTurnOffTravel: () -> Unit = {},
    // Favorites view; null hides the Favorites chip.
    favoritesState: FavoritesUiState? = null,
    favoritesGridState: LazyGridState? = null,
    onShowNearby: () -> Unit = {},
    onShowFavorites: () -> Unit = {},
    onRefreshFavorites: () -> Unit = {},
    onLoadMoreFavorites: () -> Unit = {},
    // The "N new nearby" pill: apply the held-back order and return to the top.
    onShowNewNearby: () -> Unit = {},
    // The location pill opens Travel mode; null leaves it a plain label.
    onOpenTravel: (() -> Unit)? = null,
) {
    var sheetVisible by remember { mutableStateOf(false) }
    val showingFavorites = favoritesState?.showing == true && favoritesGridState != null

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
    ) {
        val sizeClass = currentPynapliWindowSizeClass()
        GridHeader(
            locationLabel = state.locationLabel,
            profileImageUrl = profileImageUrl,
            onProfileIconClick = onProfileIconClick,
            onOpenTonight = onOpenTonight,
            onOpenFilters = { sheetVisible = true },
            onRefresh =
                when {
                    sizeClass == PynapliWindowSizeClass.Compact -> null
                    showingFavorites -> onRefreshFavorites
                    else -> onRefresh
                },
            travelCityName = travelCityName,
            onOpenTravel = onOpenTravel,
        )
        // One chip row for both views. Favorites is a view switch; a filter chip tapped while
        // Favorites shows goes back to Nearby, since the filters describe Nearby only.
        QuickFilterRow(
            filters = state.filters,
            onToggled = { id ->
                if (showingFavorites) onShowNearby()
                onQuickFilterToggled(id)
            },
            onOpenSheet = { sheetVisible = true },
            showingFavorites = if (favoritesState != null) showingFavorites else null,
            onToggleFavorites = { if (showingFavorites) onShowNearby() else onShowFavorites() },
        )
        if (showingFavorites && favoritesState != null && favoritesGridState != null) {
            // The Grid's filters, paywall and context strip describe Nearby; Favorites is its own list.
            FavoritesBody(
                state = favoritesState,
                distanceUnit = distanceUnit,
                gridState = favoritesGridState,
                onProfileSelected = onProfileSelected,
                onRefresh = onRefreshFavorites,
                onLoadMore = onLoadMoreFavorites,
                modifier = Modifier.weight(1f),
            )
        } else {
            NearbyGridSections(
                state = state,
                locationState = locationState,
                distanceUnit = distanceUnit,
                gridState = gridState,
                travelCityName = travelCityName,
                onTurnOffTravel = onTurnOffTravel,
                onOpenStore = onOpenStore,
                onOpenAgeVerification = onOpenAgeVerification,
                onRefresh = onRefresh,
                onProfileSelected = onProfileSelected,
                onLoadMore = onLoadMore,
                onUseCurrentLocation = onUseCurrentLocation,
                onClearAllFilters = onClearAllFilters,
                onShowNewNearby = onShowNewNearby,
            )
        }
    }

    if (sheetVisible) {
        GridFiltersSheet(
            initial = state.filters,
            savedState = savedFiltersState,
            tonightSessionActive = tonightSessionActive,
            lifestyleFiltersAllowed = lifestyleFiltersAllowed,
            onDismiss = { sheetVisible = false },
            onApply = { applied ->
                sheetVisible = false
                if (showingFavorites) onShowNearby()
                onApplyFilters(applied)
            },
            onClearAll = {
                sheetVisible = false
                onClearAllFilters()
            },
            onSavePreset = onSavePreset,
            onApplySavedPreset = { dto ->
                sheetVisible = false
                val filters = GridFilters.fromGridQueryRequest(dto.payload)
                onApplyFilters(
                    if (lifestyleFiltersAllowed) filters else filters.copy(lifestyleTags = emptySet()),
                )
            },
            onDeleteSavedPreset = onDeleteSavedPreset,
            onOpenTonight = {
                sheetVisible = false
                onOpenTonight()
            },
        )
    }
}

/** The Nearby view: travel and plan rows, and the ranked Grid (the chip row sits above both views). */
@Composable
private fun ColumnScope.NearbyGridSections(
    state: GridUiState,
    locationState: LocationUiState,
    distanceUnit: String,
    gridState: LazyGridState,
    travelCityName: String?,
    onTurnOffTravel: () -> Unit,
    onOpenStore: () -> Unit,
    onOpenAgeVerification: () -> Unit,
    onRefresh: () -> Unit,
    onProfileSelected: (GridProfileCardUi) -> Unit,
    onLoadMore: () -> Unit,
    onUseCurrentLocation: () -> Unit,
    onClearAllFilters: () -> Unit,
    onShowNewNearby: () -> Unit,
) {
    // Without this, someone who forgot Travel mode was on sees another city's people and
    // distances with nothing on the Grid saying why (the Swipe header already shows it).
    travelCityName?.let { city ->
        GridTravelRow(city = city, onTurnOff = onTurnOffTravel)
    }
    if (state.cards.isNotEmpty()) {
        GridContextStrip(cards = state.cards, distanceUnit = distanceUnit)
    }
    state.paywallMessage?.let { message ->
        GridPaywallRow(
            message = message,
            onOpenStore = onOpenStore,
        )
    }
    state.verificationMessage?.let { message ->
        GridVerificationRow(
            message = message,
            onOpenAgeVerification = onOpenAgeVerification,
        )
    }
    GridPullToRefresh(
        // Show the indicator for an explicit refresh, but not for the very first cold load
        // (which renders the body's own centered spinner) or background quiet refreshes.
        refreshing = state.isRefreshing && state.cards.isNotEmpty(),
        onRefresh = onRefresh,
        modifier = Modifier.weight(1f),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            GridBody(
                state = state,
                locationState = locationState,
                distanceUnit = distanceUnit,
                gridState = gridState,
                onProfileSelected = onProfileSelected,
                onLoadMore = onLoadMore,
                onUseCurrentLocation = onUseCurrentLocation,
                onRetry = onRefresh,
                onClearFilters = onClearAllFilters,
                modifier = Modifier.fillMaxSize(),
            )
            // Floats over the cards; they never move until it is tapped (or the Grid is refreshed).
            GridNewNearbyPill(
                count = state.newNearbyCount,
                onClick = onShowNewNearby,
                modifier = Modifier.align(Alignment.TopCenter),
            )
        }
    }
}
