package com.pynapli.app.grid

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.pynapli.app.location.LocationUiState

@Composable
internal fun GridBody(
    state: GridUiState,
    locationState: LocationUiState,
    distanceUnit: String,
    gridState: LazyGridState,
    onProfileSelected: (GridProfileCardUi) -> Unit,
    onLoadMore: () -> Unit,
    onUseCurrentLocation: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when {
        state.isRefreshing && state.cards.isEmpty() -> {
            Box(
                modifier = modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
        }

        state.errorCode == "LOCATION_REQUIRED" && state.cards.isEmpty() -> {
            Column(
                modifier =
                    modifier
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
                    modifier = Modifier.padding(top = 14.dp),
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
            }
        }

        state.errorMessage != null && state.cards.isEmpty() -> {
            Column(
                modifier =
                    modifier
                        .fillMaxSize()
                        .padding(horizontal = 24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = state.errorMessage,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
            }
        }

        state.cards.isEmpty() && state.filters.isAnyActive -> {
            Column(
                modifier =
                    modifier
                        .fillMaxSize()
                        .padding(horizontal = 28.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "No one matches these filters right now.",
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = "Try widening distance or clearing one filter.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }

        else -> {
            ProfileGrid(
                cards = state.cards,
                distanceUnit = distanceUnit,
                gridState = gridState,
                onProfileSelected = onProfileSelected,
                onLoadMore = onLoadMore,
                canLoadMore = state.nextCursor != null,
                isLoadingMore = state.isLoadingMore,
                modifier = modifier,
            )
        }
    }
}
