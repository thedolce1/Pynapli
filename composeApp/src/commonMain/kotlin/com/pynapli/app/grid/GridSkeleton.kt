package com.pynapli.app.grid

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pynapli.app.design.PynapliShape
import com.pynapli.app.design.PynapliSkeletonBlock
import com.pynapli.app.design.rememberPynapliShimmerBrush

private const val SkeletonRows = 5
private const val SkeletonColumns = 3

/**
 * Content-shaped loading state for the Grid: a screenful of shimmering square tiles laid out exactly
 * like [ProfileGrid] (3 columns, 1dp gaps, tile radius), so the first load "fills in" and dissolves
 * into the real cards via the [GridBody] crossfade instead of flashing a bare spinner.
 */
@Composable
internal fun GridLoadingSkeleton(modifier: Modifier = Modifier) {
    val brush = rememberPynapliShimmerBrush()
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .padding(horizontal = 1.dp),
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        repeat(SkeletonRows) {
            Row(horizontalArrangement = Arrangement.spacedBy(1.dp)) {
                repeat(SkeletonColumns) {
                    PynapliSkeletonBlock(
                        brush = brush,
                        shape = PynapliShape.SmallShape,
                        modifier =
                            Modifier
                                .weight(1f)
                                .aspectRatio(1f),
                    )
                }
            }
        }
    }
}
