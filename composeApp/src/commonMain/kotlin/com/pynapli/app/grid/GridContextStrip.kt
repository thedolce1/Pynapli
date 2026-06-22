package com.pynapli.app.grid

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pynapli.app.design.PynapliColors
import com.pynapli.shared.distance.formatDistanceForUnit

/**
 * Density / context strip under the quick-filter row (handoff `Grid.jsx`): a bold nearby count on the
 * left and the distance range of the loaded cards on the right. Quiet, restrained meta (tertiary
 * text) that gives the dense grid a scannable header without competing with the photos.
 */
@Composable
internal fun GridContextStrip(
    cards: List<GridProfileCardUi>,
    distanceUnit: String,
    modifier: Modifier = Modifier,
) {
    val rangeLabel = remember(cards, distanceUnit) { gridDistanceRangeLabel(cards, distanceUnit) }
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = cards.size.toString(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "NEARBY",
            color = PynapliColors.TextTertiary,
            style = MaterialTheme.typography.labelSmall,
            letterSpacing = 0.4.sp,
        )
        Spacer(modifier = Modifier.weight(1f))
        rangeLabel?.let {
            Text(
                text = it,
                color = PynapliColors.TextTertiary,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

/** Min–max distance of the loaded cards, e.g. "100 m – 7.8 km" (or a single value if all equal). */
private fun gridDistanceRangeLabel(
    cards: List<GridProfileCardUi>,
    distanceUnit: String,
): String? {
    val meters = cards.mapNotNull { it.distanceMeters }
    if (meters.isEmpty()) return null
    val min = meters.min()
    val max = meters.max()
    val minLabel =
        formatDistanceForUnit(distanceMeters = min, fallbackLabel = null, distanceUnit = distanceUnit)
            ?: return null
    if (min == max) return minLabel
    val maxLabel =
        formatDistanceForUnit(distanceMeters = max, fallbackLabel = null, distanceUnit = distanceUnit)
            ?: return minLabel
    return "$minLabel – $maxLabel"
}
