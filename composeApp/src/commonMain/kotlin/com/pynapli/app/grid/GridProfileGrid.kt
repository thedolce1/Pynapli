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
import com.pynapli.app.design.rememberReducedMotion
import com.pynapli.app.media.ImageLoadStats

internal const val GRID_LOAD_MORE_RETRY_TAG = "grid-load-more-retry"

private const val GRID_LOCKED_BANNER_KEY = "grid-locked-banner"

// How long after the cards change the debug image line is written: long enough for the visible
// tiles to load, short enough that the next 60 s live refresh gets its own line.
private const val GRID_IMAGE_STATS_SETTLE_MILLIS = 5_000L

/**
 * The card a lazy-grid item stands for when the boundary banner sits before card [bannerAt]: items
 * after the banner are one further along than their card. The banner itself counts as the card it
 * precedes. Keeps the load-more trigger and the thumbnail prefetch counting cards, not grid items.
 */
internal fun cardIndexForGridItem(
    itemIndex: Int,
    bannerAt: Int?,
): Int = if (bannerAt != null && itemIndex > bannerAt) itemIndex - 1 else itemIndex

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
    // Section 3: the full-width "See everyone nearby" banner goes before this card (the first locked
    // one); null draws no banner. [onOpenUnlockStore] opens the plans on [unlockTier].
    lockedBannerIndex: Int? = null,
    unlockTier: String? = null,
    onOpenUnlockStore: (unlockTier: String?) -> Unit = {},
) {
    val bannerAt = lockedBannerIndex?.takeIf { it in cards.indices }
    val shouldLoadMore by remember(cards.size, gridState, isLoadingMore, canLoadMore, loadMoreFailed, bannerAt) {
        derivedStateOf {
            val lastVisibleIndex =
                gridState.layoutInfo.visibleItemsInfo
                    .lastOrNull()
                    ?.index ?: return@derivedStateOf false
            val lastVisibleCard = cardIndexForGridItem(lastVisibleIndex, bannerAt)
            canLoadMore && !loadMoreFailed && !isLoadingMore && cards.isNotEmpty() && lastVisibleCard >= cards.lastIndex - 9
        }
    }

    LaunchedEffect(shouldLoadMore, cards.size, canLoadMore, isLoadingMore) {
        if (shouldLoadMore) onLoadMore()
    }

    val prefetchImages by remember(cards, gridState, bannerAt) {
        derivedStateOf {
            val lastVisibleIndex =
                gridState.layoutInfo.visibleItemsInfo
                    .lastOrNull()
                    ?.index ?: -1
            planThumbnailPrefetchImages(cards = cards, lastVisibleIndex = cardIndexForGridItem(lastVisibleIndex, bannerAt))
        }
    }
    val platformContext = LocalPlatformContext.current
    LaunchedEffect(prefetchImages) {
        if (prefetchImages.isEmpty()) return@LaunchedEffect
        val imageLoader = runCatching { SingletonImageLoader.get(platformContext) }.getOrNull() ?: return@LaunchedEffect
        prefetchImages.forEach { image ->
            // The tile's own stable key (G2-2): the prefetch fills the entry the tile reads.
            imageLoader.enqueue(
                ImageRequest
                    .Builder(platformContext)
                    .data(image.url)
                    .apply {
                        image.cacheKey?.let { key ->
                            memoryCacheKey(key)
                            diskCacheKey(key)
                        }
                    }.build(),
            )
        }
    }
    // G2-2 ticket 7 (debug builds only): one line per settled grid refresh with how its tiles were
    // served (memory / disk / network). Counts only; no URL, id or key.
    LaunchedEffect(cards) {
        kotlinx.coroutines.delay(GRID_IMAGE_STATS_SETTLE_MILLIS)
        ImageLoadStats.grid.report()
    }

    // First-load reveal: tiles fade + lift in (staggered). Flips true once per grid entry; tiles
    // scrolled in or paginated after that appear instantly (see pynapliEntrance).
    var revealed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { revealed = true }

    // A hide's backfill and a filter answer move tiles to their new places (keys are user ids, locked
    // cards an opaque per-viewer token, both unique) instead of snapping. Reduced motion: they jump.
    val reducedMotion = rememberReducedMotion()

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
            // Before the banner (all of them when there is none), then the banner, then the rest.
            val beforeBanner = bannerAt ?: cards.size
            items(
                count = beforeBanner,
                key = { index -> cards[index].id },
                contentType = { "grid-card" },
            ) { index ->
                val card = cards[index]
                GridProfileTile(
                    card = card,
                    distanceUnit = distanceUnit,
                    onClick = { onProfileSelected(card) },
                    modifier = (if (reducedMotion) Modifier else Modifier.animateItem()).pynapliEntrance(visible = revealed, index = index),
                )
            }
            if (bannerAt != null) {
                item(
                    key = GRID_LOCKED_BANNER_KEY,
                    span = { GridItemSpan(maxLineSpan) },
                    contentType = "grid-locked-banner",
                ) {
                    GridLockedBanner(unlockTier = unlockTier, onOpenStore = { onOpenUnlockStore(unlockTier) })
                }
                items(
                    count = cards.size - bannerAt,
                    key = { offset -> cards[bannerAt + offset].id },
                    contentType = { "grid-card" },
                ) { offset ->
                    val index = bannerAt + offset
                    val card = cards[index]
                    GridProfileTile(
                        card = card,
                        distanceUnit = distanceUnit,
                        onClick = { onProfileSelected(card) },
                        modifier = (if (reducedMotion) Modifier else Modifier.animateItem()).pynapliEntrance(visible = revealed, index = index),
                    )
                }
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
