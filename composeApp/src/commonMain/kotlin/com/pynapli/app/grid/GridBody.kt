package com.pynapli.app.grid

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.pynapli.app.design.PynapliEmptyState
import com.pynapli.app.design.PynapliErrorState
import com.pynapli.app.design.PynapliMotion
import com.pynapli.app.design.handCursor
import com.pynapli.app.design.icons.Icons
import com.pynapli.app.design.icons.filled.SearchOff
import com.pynapli.app.location.LOCATION_SKIP_COST_COPY
import com.pynapli.app.location.LocationAccessHelp
import com.pynapli.app.location.LocationUiState
import com.pynapli.app.perf.PynapliPerfMarks

internal enum class GridPhase { Loading, LocationRequired, Error, EmptyFiltered, Empty, Content }

internal const val GRID_EMPTY_TITLE: String = "No one nearby yet"

internal fun gridPhase(state: GridUiState): GridPhase =
    when {
        // Still reading the cold-start cache: the same skeleton as a first network load, as one
        // phase so the crossfade never runs skeleton-to-skeleton.
        !state.cacheChecked && state.cards.isEmpty() -> GridPhase.Loading
        state.isRefreshing && state.cards.isEmpty() -> GridPhase.Loading
        state.errorCode == "LOCATION_REQUIRED" && state.cards.isEmpty() -> GridPhase.LocationRequired
        state.errorMessage != null && state.cards.isEmpty() -> GridPhase.Error
        state.cards.isEmpty() && state.filters.isAnyActive -> GridPhase.EmptyFiltered
        // Nobody at all, even after the server widened to the maximum radius: say so, never a blank grid.
        state.cards.isEmpty() -> GridPhase.Empty
        else -> GridPhase.Content
    }

@Composable
internal fun GridBody(
    state: GridUiState,
    locationState: LocationUiState,
    distanceUnit: String,
    gridState: LazyGridState,
    onProfileSelected: (GridProfileCardUi) -> Unit,
    onLoadMore: () -> Unit,
    onUseCurrentLocation: () -> Unit,
    onRetry: () -> Unit,
    onClearFilters: () -> Unit,
    modifier: Modifier = Modifier,
    // The locked-card boundary banner's button: the plans sheet on the server's unlockTier.
    onOpenUnlockStore: (unlockTier: String?) -> Unit = {},
) {
    // Loading dissolves into the arriving content rather than snapping; the skeleton fades out while
    // the grid fades + staggers in (see ProfileGrid). Phase is stable across profile open/close (cards
    // stay present → Content), so the crossfade never disrupts grid scroll restoration.
    Crossfade(
        targetState = gridPhase(state),
        animationSpec = PynapliMotion.standardTween(),
        label = "gridPhase",
        modifier = modifier,
    ) { phase ->
        when (phase) {
            // Also covers the cold-start cache read: the shell is already up then (a returning
            // member skips the /me wait), so a skeleton rather than a blank pane; cached cards
            // crossfade in over it like the live feed does.
            GridPhase.Loading -> {
                GridLoadingSkeleton(modifier = Modifier.fillMaxSize())
            }

            GridPhase.LocationRequired -> {
                Column(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .padding(horizontal = 28.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = "Use current location to load nearby profiles.",
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                    )
                    Button(
                        onClick = onUseCurrentLocation,
                        enabled = !locationState.isUpdating,
                        modifier = Modifier.padding(top = 14.dp).handCursor(enabled = !locationState.isUpdating),
                    ) {
                        Text(text = if (locationState.isUpdating) "Updating..." else "Use current location")
                    }
                    locationState.errorMessage?.let { message ->
                        Text(
                            text = message,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 10.dp),
                        )
                    }
                    // Blocked for good or services off: the way to Settings (or the browser's switch).
                    LocationAccessHelp(locationState, modifier = Modifier.padding(top = 4.dp))
                    Text(
                        text = LOCATION_SKIP_COST_COPY,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }
            }

            GridPhase.Error -> {
                PynapliErrorState(
                    title = "Couldn't load nearby profiles",
                    message = state.errorMessage,
                    onRetry = onRetry,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            GridPhase.EmptyFiltered -> {
                PynapliEmptyState(
                    icon = Icons.Filled.SearchOff,
                    title = "No one matches these filters right now.",
                    subtitle = "Try widening distance or clearing one filter.",
                    actionLabel = "Clear filters",
                    onAction = onClearFilters,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            GridPhase.Empty -> {
                // A true empty Grid: no filters, no error, and the server already widened the search
                // as far as it goes (plan G10). The client only says so, honestly.
                PynapliEmptyState(
                    icon = Icons.Filled.SearchOff,
                    title = GRID_EMPTY_TITLE,
                    subtitle = "New people join every day. Pull down or tap Refresh to look again.",
                    actionLabel = "Refresh",
                    onAction = onRetry,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            GridPhase.Content -> {
                // Time-to-first-card marker for the QA scripts (once per process, no member data).
                if (state.cards.isNotEmpty()) {
                    SideEffect { PynapliPerfMarks.markOnce(PynapliPerfMarks.FIRST_GRID_CARD) }
                }
                ProfileGrid(
                    cards = state.cards,
                    distanceUnit = distanceUnit,
                    gridState = gridState,
                    onProfileSelected = onProfileSelected,
                    onLoadMore = onLoadMore,
                    canLoadMore = state.nextCursor != null,
                    isLoadingMore = state.isLoadingMore,
                    loadMoreFailed = state.errorMessage != null,
                    modifier = Modifier,
                    lockedBannerIndex = state.lockedBannerIndex,
                    unlockTier = state.unlockTier,
                    onOpenUnlockStore = onOpenUnlockStore,
                )
            }
        }
    }
}
