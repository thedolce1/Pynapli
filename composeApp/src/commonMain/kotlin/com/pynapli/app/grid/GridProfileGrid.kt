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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.SingletonImageLoader
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import com.pynapli.app.design.handCursor
import com.pynapli.app.design.pynapliEntrance

internal const val GRID_LOAD_MORE_RETRY_TAG = "grid-load-more-retry"

/**
 * Minimum tile side once the grid's own pane grows past phone width. Keeps wide-window
 * tiles close to the phone's (~130dp at 390dp width), so more width means MORE people
 * on screen, never a blown-up phone grid — density is this surface's identity
 * (WEB_PWA_GAMEPLAN.md W2.1).
 *
 * `internal`, not private: [GridLoadingSkeleton] must lay out with the SAME numbers. It used to
 * hardcode 3 columns — correct when the grid was phone-only, silently wrong once W2 made the grid
 * adaptive, which left every wide-pane first load showing 3 full-height bars instead of a preview
 * of the grid. Sharing the constants is what stops that drifting apart again.
 */
internal val GridWideTileMinSize = 168.dp

/** Panes narrower than this keep the locked 3-up phone grid. */
internal val GridWidePaneMinWidth = 600.dp

/** Gap between tiles, shared with the skeleton for the same reason. */
internal val GridTileGap = 1.dp

/**
 * Column count for a pane of [paneWidth] — the single definition both the real grid and its
 * skeleton lay out from.
 *
 * Mirrors `GridCells.Adaptive`'s own arithmetic (`(available + gap) / (minSize + gap)`, floored,
 * at least 1) so the skeleton predicts the same number of columns the LazyVerticalGrid will
 * actually produce, rather than approximating it.
 */
internal fun gridColumnCountFor(
    paneWidth: Dp,
    fontScale: Float = 1f,
): Int =
    if (paneWidth < GridWidePaneMinWidth) {
        3
    } else {
        (((paneWidth + GridTileGap) / (gridWideTileMinSize(fontScale) + GridTileGap)).toInt()).coerceAtLeast(1)
    }

/**
 * Wide-pane tile minimum, grown with the text scale. A member who enlarged text (the browser's font
 * setting on the web, the system font size elsewhere) did it to read names, and at 1.5x a 168dp tile
 * cut most of them off. Width still means more tiles; large text just means fewer, larger ones.
 * Capped at 1.5x, the largest text scale the web applies.
 */
internal fun gridWideTileMinSize(fontScale: Float): Dp = GridWideTileMinSize * fontScale.coerceIn(1f, 1.5f)

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
    // The last page request failed: stop auto-paging (it would retry in a tight loop) and offer a
    // Retry at the end of the list instead.
    loadMoreFailed: Boolean = false,
) {
    val shouldLoadMore by remember(cards.size, gridState, isLoadingMore, canLoadMore, loadMoreFailed) {
        derivedStateOf {
            val lastVisibleIndex =
                gridState.layoutInfo.visibleItemsInfo
                    .lastOrNull()
                    ?.index ?: return@derivedStateOf false
            canLoadMore && !loadMoreFailed && !isLoadingMore && cards.isNotEmpty() && lastVisibleIndex >= cards.lastIndex - 9
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
            if (maxWidth < GridWidePaneMinWidth) {
                GridCells.Fixed(3)
            } else {
                GridCells.Adaptive(minSize = gridWideTileMinSize(LocalDensity.current.fontScale))
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
            } else if (loadMoreFailed && canLoadMore) {
                item(
                    key = "grid-load-more-failed",
                    span = { GridItemSpan(maxLineSpan) },
                    contentType = "grid-loading",
                ) {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        TextButton(
                            onClick = onLoadMore,
                            modifier = Modifier.handCursor().testTag(GRID_LOAD_MORE_RETRY_TAG),
                        ) {
                            Text(text = "Couldn't load more. Retry")
                        }
                    }
                }
            }
        }
    }
}
