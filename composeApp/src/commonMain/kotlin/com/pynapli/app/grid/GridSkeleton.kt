package com.pynapli.app.grid

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pynapli.app.design.PynapliGridSkeleton
import com.pynapli.app.design.PynapliShape

/**
 * Content-shaped loading state for the Grid: a screenful of shimmering square tiles laid out exactly
 * like [ProfileGrid] (3 columns, 1dp gaps, tile radius), so the first load "fills in" and dissolves
 * into the real cards via the [GridBody] crossfade instead of flashing a bare spinner.
 */
@Composable
internal fun GridLoadingSkeleton(modifier: Modifier = Modifier) {
    PynapliGridSkeleton(
        columns = 3,
        rows = 5,
        modifier = modifier,
        tileAspectRatio = 1f,
        tileShape = PynapliShape.SmallShape,
        gap = 1.dp,
        contentPadding = PaddingValues(horizontal = 1.dp),
    )
}
