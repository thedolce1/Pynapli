package com.pynapli.app.grid

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import com.pynapli.app.design.PynapliGridSkeleton
import com.pynapli.app.design.PynapliShape
import kotlin.math.ceil

/**
 * Content-shaped loading state for the Grid: a screenful of shimmering square tiles laid out like
 * [ProfileGrid], so the first load "fills in" and dissolves into the real cards via the [GridBody]
 * crossfade instead of flashing a bare spinner.
 *
 * Answers its own pane width through [gridColumnCountFor] — the same function the real grid lays out
 * from. It previously hardcoded 3 columns and 5 rows, which was right while the grid was phone-only
 * and silently wrong from the moment W2 made the grid adaptive: on a wide pane that produced three
 * full-height bars (each tile a third of the pane wide and, being square, taller than the viewport)
 * instead of a preview of the grid. Web feels it worst — with `NoOpGridSnapshotStore` there is no
 * cached first paint, so this is what every desktop visitor sees before their first cards arrive.
 */
@Composable
internal fun GridLoadingSkeleton(modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier = modifier) {
        val columns = gridColumnCountFor(maxWidth, LocalDensity.current.fontScale)
        // Tiles are square, so a row is about as tall as a tile is wide. Fill the pane and round up
        // so the shimmer reaches the bottom edge rather than stopping short on a tall window.
        val tileSide = (maxWidth - GridTileGap * (columns - 1)) / columns
        val rows =
            if (tileSide.value <= 0f) {
                MinSkeletonRows
            } else {
                ceil(maxHeight / tileSide).toInt().coerceIn(MinSkeletonRows, MaxSkeletonRows)
            }

        PynapliGridSkeleton(
            columns = columns,
            rows = rows,
            tileAspectRatio = 1f,
            tileShape = PynapliShape.SmallShape,
            gap = GridTileGap,
            contentPadding = PaddingValues(horizontal = GridTileGap),
        )
    }
}

private const val MinSkeletonRows = 1

/**
 * Cap on skeleton rows. Nothing below the fold is ever seen — the crossfade to real cards lands
 * before anyone could scroll — so this bounds the composable count on a very tall or very narrow
 * pane instead of shimmering hundreds of off-screen tiles.
 */
private const val MaxSkeletonRows = 12
