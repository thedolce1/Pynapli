package com.pynapli.app.swipe

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pynapli.app.grid.DiscoveryStyle
import com.pynapli.app.grid.Intent
import com.pynapli.app.grid.LifestyleTag
import com.pynapli.app.grid.LogisticsTag

@Composable
internal fun WhoSection(
    singles: Boolean,
    linked: Boolean,
    partnered: Boolean,
    onTogglesChanged: (Boolean, Boolean, Boolean) -> Unit
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SwipeSheetChip(label = "Singles", active = singles) {
            onTogglesChanged(!singles, linked, partnered)
        }
        SwipeSheetChip(label = "Linked", active = linked) {
            onTogglesChanged(singles, !linked, partnered)
        }
        SwipeSheetChip(label = "Partnered", active = partnered) {
            onTogglesChanged(singles, linked, !partnered)
        }
    }
}

@Composable
internal fun AgeSection(
    minimumAge: Int?,
    maximumAge: Int?,
    onChange: (Int?, Int?) -> Unit
) {
    val lo = (minimumAge ?: SwipeFilters.MIN_FILTER_AGE).coerceAtLeast(SwipeFilters.MIN_FILTER_AGE)
    val hi = (maximumAge ?: SwipeFilters.MAX_FILTER_AGE).coerceAtMost(SwipeFilters.MAX_FILTER_AGE)
    val isUnbounded = minimumAge == null && maximumAge == null
    Column {
        Text(
            text = if (isUnbounded) "Any age" else "$lo – $hi",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        RangeSlider(
            value = lo.toFloat()..hi.toFloat(),
            onValueChange = { range ->
                val newLo = range.start.toInt()
                val newHi = range.endInclusive.toInt()
                if (newLo == SwipeFilters.MIN_FILTER_AGE && newHi == SwipeFilters.MAX_FILTER_AGE) {
                    onChange(null, null)
                } else {
                    onChange(newLo, newHi)
                }
            },
            valueRange = SwipeFilters.MIN_FILTER_AGE.toFloat()..SwipeFilters.MAX_FILTER_AGE.toFloat()
        )
    }
}

@Composable
internal fun DistanceSection(radiusMeters: Int, onChange: (Int) -> Unit) {
    val km = (radiusMeters + 500) / 1_000
    Column {
        Text(
            text = "Within $km km",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Slider(
            value = radiusMeters.toFloat(),
            onValueChange = { value -> onChange(value.toInt()) },
            valueRange = SwipeFilters.MIN_RADIUS_METERS.toFloat()..SwipeFilters.MAX_RADIUS_METERS.toFloat()
        )
    }
}

@Composable
internal fun IntentStylesSection(
    selected: Set<DiscoveryStyle>,
    onChange: (Set<DiscoveryStyle>) -> Unit
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        DiscoveryStyle.entries.forEach { style ->
            val active = style in selected
            SwipeSheetChip(label = style.label, active = active) {
                onChange(if (active) selected - style else selected + style)
            }
        }
    }
}

@Composable
internal fun IntentExtendedSection(
    selected: Set<Intent>,
    onChange: (Set<Intent>) -> Unit
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Intent.entries.forEach { intent ->
            val active = intent in selected
            SwipeSheetChip(label = intent.label, active = active) {
                onChange(if (active) selected - intent else selected + intent)
            }
        }
    }
}

@Composable
internal fun VerifiedModeSection(
    verifiedOnly: Boolean,
    verifiedPreferred: Boolean,
    onChange: (only: Boolean, preferred: Boolean) -> Unit
) {
    // Three mutually exclusive options per FILTERS_SPEC L344-352.
    val noPreference = !verifiedOnly && !verifiedPreferred
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SwipeSheetChip(label = "Verified only", active = verifiedOnly) {
            onChange(true, false)
        }
        SwipeSheetChip(label = "Prefer verified", active = verifiedPreferred && !verifiedOnly) {
            onChange(false, true)
        }
        SwipeSheetChip(label = "Any", active = noPreference) {
            onChange(false, false)
        }
    }
}

@Composable
internal fun FacePhotoAndAgeVerifiedSection(
    facePhotoVerified: Boolean,
    ageVerified: Boolean,
    onChange: (face: Boolean, ageVerified: Boolean) -> Unit
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SwipeSheetChip(label = "Face photo verified", active = facePhotoVerified) {
            onChange(!facePhotoVerified, ageVerified)
        }
        SwipeSheetChip(label = "Age verified", active = ageVerified) {
            onChange(facePhotoVerified, !ageVerified)
        }
    }
}

@Composable
internal fun LifestyleTagsSection(
    selected: Set<LifestyleTag>,
    onChange: (Set<LifestyleTag>) -> Unit
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        LifestyleTag.entries.forEach { tag ->
            val active = tag in selected
            SwipeSheetChip(label = tag.label, active = active) {
                onChange(if (active) selected - tag else selected + tag)
            }
        }
    }
}

@Composable
internal fun LogisticsTagsSection(
    selected: Set<LogisticsTag>,
    onChange: (Set<LogisticsTag>) -> Unit
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        LogisticsTag.entries.forEach { tag ->
            val active = tag in selected
            SwipeSheetChip(label = tag.label, active = active) {
                onChange(if (active) selected - tag else selected + tag)
            }
        }
    }
}
