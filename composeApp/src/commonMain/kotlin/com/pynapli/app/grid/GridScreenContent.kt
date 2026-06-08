package com.pynapli.app.grid

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.pynapli.app.location.LocationUiState
import com.pynapli.shared.network.dto.SavedGridFilterDto

@Composable
internal fun GridScreenContent(
    state: GridUiState,
    savedFiltersState: SavedGridFiltersUiState,
    tonightState: TonightAvailabilityUiState,
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
    onSetTonightAvailable: () -> Unit,
    onClearTonightAvailable: () -> Unit,
    onLoadMore: () -> Unit,
    onRefresh: () -> Unit,
    onProfileSelected: (GridProfileCardUi) -> Unit,
    onUseCurrentLocation: () -> Unit,
    onProfileIconClick: () -> Unit,
    onOpenStore: () -> Unit,
    onOpenAgeVerification: () -> Unit = {},
    onOpenTonight: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var sheetVisible by remember { mutableStateOf(false) }

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
    ) {
        GridHeader(
            locationLabel = state.locationLabel,
            profileImageUrl = profileImageUrl,
            onProfileIconClick = onProfileIconClick,
            onOpenTonight = onOpenTonight,
        )
        QuickFilterRow(
            filters = state.filters,
            onToggled = onQuickFilterToggled,
            onOpenSheet = { sheetVisible = true },
        )
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
        if (state.adSupported) {
            GridAdSlot()
        }
        GridPullToRefresh(
            // Show the indicator for an explicit refresh, but not for the very first cold load
            // (which renders the body's own centered spinner) or background quiet refreshes.
            refreshing = state.isRefreshing && state.cards.isNotEmpty(),
            onRefresh = onRefresh,
            modifier = Modifier.weight(1f),
        ) {
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
        }
    }

    if (sheetVisible) {
        GridFiltersSheet(
            initial = state.filters,
            savedState = savedFiltersState,
            tonightState = tonightState,
            lifestyleFiltersAllowed = lifestyleFiltersAllowed,
            onDismiss = { sheetVisible = false },
            onApply = { applied ->
                sheetVisible = false
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
            onSetTonightAvailable = onSetTonightAvailable,
            onClearTonightAvailable = onClearTonightAvailable,
        )
    }
}
