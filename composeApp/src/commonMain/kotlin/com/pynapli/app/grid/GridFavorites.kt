package com.pynapli.app.grid

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pynapli.app.design.PynapliColors
import com.pynapli.app.design.PynapliEmptyState
import com.pynapli.app.design.PynapliErrorState
import com.pynapli.app.design.PynapliMotion
import com.pynapli.app.design.PynapliPill
import com.pynapli.app.design.icons.Icons
import com.pynapli.app.design.icons.filled.GridView
import com.pynapli.app.design.icons.filled.Star
import com.pynapli.app.design.icons.filled.StarBorder

internal object GridFavoritesTags {
    const val VIEW_NEARBY = "grid-view-nearby"
    const val VIEW_FAVORITES = "grid-view-favorites"
    const val EMPTY = "grid-favorites-empty"
}

/** Nearby / Favorites switch above the Grid (Grindr's "All" and "Favorites"). */
@Composable
internal fun GridViewSwitch(
    showingFavorites: Boolean,
    onShowNearby: () -> Unit,
    onShowFavorites: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        PynapliPill(
            text = "Nearby",
            selected = !showingFavorites,
            onClick = onShowNearby,
            leading = {
                Icon(
                    imageVector = Icons.Filled.GridView,
                    contentDescription = null,
                    tint = PynapliColors.Accent,
                    modifier = Modifier.size(16.dp),
                )
            },
            modifier = Modifier.testTag(GridFavoritesTags.VIEW_NEARBY),
        )
        PynapliPill(
            text = "Favorites",
            selected = showingFavorites,
            onClick = onShowFavorites,
            leading = {
                Icon(
                    imageVector = if (showingFavorites) Icons.Filled.Star else Icons.Filled.StarBorder,
                    contentDescription = null,
                    tint = PynapliColors.Accent,
                    modifier = Modifier.size(16.dp),
                )
            },
            modifier = Modifier.testTag(GridFavoritesTags.VIEW_FAVORITES),
        )
    }
}

private enum class FavoritesPhase { Loading, Error, Empty, Content }

/** The Favorites view body: the Grid's own card grid, pull-to-refresh, honest empty and error states. */
@Composable
internal fun FavoritesBody(
    state: FavoritesUiState,
    distanceUnit: String,
    gridState: LazyGridState,
    onProfileSelected: (GridProfileCardUi) -> Unit,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val phase =
        when {
            state.isLoading && state.cards.isEmpty() -> FavoritesPhase.Loading
            state.errorMessage != null && state.cards.isEmpty() -> FavoritesPhase.Error
            state.cards.isEmpty() -> FavoritesPhase.Empty
            else -> FavoritesPhase.Content
        }
    Column(modifier = modifier) {
        if (state.cards.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 2.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = state.cards.size.toString(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "FAVORITES",
                    color = PynapliColors.TextTertiary,
                    style = MaterialTheme.typography.labelSmall,
                    letterSpacing = 0.4.sp,
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = "Only you can see this",
                    color = PynapliColors.TextTertiary,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
        GridPullToRefresh(
            refreshing = state.isLoading && state.cards.isNotEmpty(),
            onRefresh = onRefresh,
            modifier = Modifier.weight(1f),
        ) {
            Crossfade(
                targetState = phase,
                animationSpec = PynapliMotion.standardTween(),
                label = "favoritesPhase",
                modifier = Modifier.fillMaxSize(),
            ) { current ->
                when (current) {
                    FavoritesPhase.Loading -> GridLoadingSkeleton(modifier = Modifier.fillMaxSize())
                    FavoritesPhase.Error ->
                        PynapliErrorState(
                            title = "Couldn't load your favorites",
                            message = state.errorMessage,
                            onRetry = onRefresh,
                            modifier = Modifier.fillMaxSize(),
                        )
                    FavoritesPhase.Empty ->
                        PynapliEmptyState(
                            icon = Icons.Filled.StarBorder,
                            title = "No favorites yet",
                            subtitle = "Tap the star on someone's profile to keep them here. They're never told.",
                            modifier = Modifier.fillMaxSize().padding(PaddingValues(horizontal = 12.dp)).testTag(GridFavoritesTags.EMPTY),
                        )
                    FavoritesPhase.Content ->
                        ProfileGrid(
                            cards = state.cards,
                            distanceUnit = distanceUnit,
                            gridState = gridState,
                            onProfileSelected = onProfileSelected,
                            onLoadMore = onLoadMore,
                            canLoadMore = state.nextCursor != null,
                            isLoadingMore = state.isLoadingMore,
                        )
                }
            }
        }
    }
}
