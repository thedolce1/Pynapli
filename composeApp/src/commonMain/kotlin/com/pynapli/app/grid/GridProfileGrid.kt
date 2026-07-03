package com.pynapli.app.grid

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import coil3.SingletonImageLoader
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import com.pynapli.app.design.pynapliEntrance

/**
 * Minimum tile side once the grid's own pane grows past phone width. Keeps wide-window
 * tiles close to the phone's (~130dp at 390dp width), so more width means MORE people
 * on screen, never a blown-up phone grid — density is this surface's identity
 * (WEB_PWA_GAMEPLAN.md W2.1).
 */
private val WideTileMinSize = 168.dp

/** Panes narrower than this keep the locked 3-up phone grid. */
private val WidePaneMinWidth = 600.dp

@Composable
internal fun ProfileGrid(
    cards: List<GridProfileCardUi>,
    distanceUnit: String,
    gridState: LazyGridState,
    onProfileSelected: (GridProfileCardUi) -> Unit,
    onLoadMore: () -> Unit,
    canLoadMore: Boolean,
    isLoadingMore: Boolean,
    modifier: Modifier = Modifier,
) {
    val shouldLoadMore by remember(cards.size, gridState, isLoadingMore, canLoadMore) {
        derivedStateOf {
            val lastVisibleIndex =
                gridState.layoutInfo.visibleItemsInfo
                    .lastOrNull()
                    ?.index ?: return@derivedStateOf false
            canLoadMore && !isLoadingMore && cards.isNotEmpty() && lastVisibleIndex >= cards.lastIndex - 9
        }
    }

    LaunchedEffect(shouldLoadMore, cards.size, canLoadMore, isLoadingMore) {
        if (shouldLoadMore) onLoadMore()
    }

    val prefetchUrls by remember(cards, gridState) {
        derivedStateOf {
            val lastVisibleIndex =
                gridState.layoutInfo.visibleItemsInfo
                    .lastOrNull()
                    ?.index ?: -1
            planThumbnailPrefetch(cards = cards, lastVisibleIndex = lastVisibleIndex)
        }
    }
    val platformContext = LocalPlatformContext.current
    LaunchedEffect(prefetchUrls) {
        if (prefetchUrls.isEmpty()) return@LaunchedEffect
        val imageLoader = runCatching { SingletonImageLoader.get(platformContext) }.getOrNull() ?: return@LaunchedEffect
        prefetchUrls.forEach { url ->
            imageLoader.enqueue(
                ImageRequest
                    .Builder(platformContext)
                    .data(url)
                    .build(),
            )
        }
    }

    // First-load reveal: tiles fade + lift in (staggered). Flips true once per grid entry; tiles
    // scrolled in or paginated after that appear instantly (see pynapliEntrance).
    var revealed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { revealed = true }

    // The grid answers its OWN pane width (not the window): beside a nav rail or a
    // side panel the pane is what determines honest density, and phone-width panes
    // keep the locked 3-up layout byte-for-byte.
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val columns =
            if (maxWidth < WidePaneMinWidth) {
                GridCells.Fixed(3)
            } else {
                GridCells.Adaptive(minSize = WideTileMinSize)
            }
        LazyVerticalGrid(
            columns = columns,
            state = gridState,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(start = 1.dp, end = 1.dp, bottom = 1.dp),
            horizontalArrangement = Arrangement.spacedBy(1.dp),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            itemsIndexed(
                items = cards,
                key = { _, card -> card.id },
                contentType = { _, _ -> "grid-card" },
            ) { index, card ->
                GridProfileTile(
                    card = card,
                    distanceUnit = distanceUnit,
                    onClick = { onProfileSelected(card) },
                    modifier = Modifier.pynapliEntrance(visible = revealed, index = index),
                )
            }

            if (isLoadingMore) {
                item(
                    key = "grid-loading-more",
                    span = { GridItemSpan(maxLineSpan) },
                    contentType = "grid-loading",
                ) {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 18.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }
            }
        }
    }
}
