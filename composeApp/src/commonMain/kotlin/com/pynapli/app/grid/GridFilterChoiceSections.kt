package com.pynapli.app.grid

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pynapli.shared.profile.ProfileTaxonomies

@Composable
internal fun DistanceSection(
    radiusMeters: Int,
    onChange: (Int) -> Unit,
) {
    val km = (radiusMeters + 500) / 1_000
    Column {
        Text(
            text = "Within $km km",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Slider(
            value = radiusMeters.toFloat(),
            onValueChange = { value -> onChange(value.toInt()) },
            valueRange = GridFilters.MIN_RADIUS_METERS.toFloat()..GridFilters.MAX_RADIUS_METERS.toFloat(),
            steps = 0,
        )
    }
}

@Composable
internal fun AgeSection(
    minimumAge: Int?,
    maximumAge: Int?,
    onChange: (Int?, Int?) -> Unit,
) {
    val lo = (minimumAge ?: GridFilters.MIN_FILTER_AGE).coerceAtLeast(GridFilters.MIN_FILTER_AGE)
    val hi = (maximumAge ?: GridFilters.MAX_FILTER_AGE).coerceAtMost(GridFilters.MAX_FILTER_AGE)
    val isUnbounded = minimumAge == null && maximumAge == null
    Column {
        Text(
            text = if (isUnbounded) "Any age" else "$lo – $hi",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        RangeSlider(
            value = lo.toFloat()..hi.toFloat(),
            onValueChange = { range ->
                val newLo = range.start.toInt()
                val newHi = range.endInclusive.toInt()
                if (newLo == GridFilters.MIN_FILTER_AGE && newHi == GridFilters.MAX_FILTER_AGE) {
                    onChange(null, null)
                } else {
                    onChange(newLo, newHi)
                }
            },
            valueRange = GridFilters.MIN_FILTER_AGE.toFloat()..GridFilters.MAX_FILTER_AGE.toFloat(),
        )
    }
}

@Composable
internal fun PresenceSection(
    online: Boolean,
    tonightAvailable: Boolean,
    onOnlineToggle: () -> Unit,
    onTonightToggle: () -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SheetToggleChip(label = "Online now", active = online, onClick = onOnlineToggle)
        SheetToggleChip(label = "Available tonight", active = tonightAvailable, onClick = onTonightToggle)
    }
}

@Composable
internal fun PhotosSection(
    withPhotos: Boolean,
    onToggle: () -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SheetToggleChip(label = "Has photos", active = withPhotos, onClick = onToggle)
    }
}

@Composable
internal fun TonightAvailabilityRow(
    state: TonightAvailabilityUiState,
    onSet: () -> Unit,
    onClear: () -> Unit,
) {
    val label =
        when {
            state.isMutating -> "Updating…"
            state.isCurrentlyAvailable -> "You're marked available tonight"
            else -> "Mark me available tonight (12h)"
        }
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        if (state.isCurrentlyAvailable) {
            TextButton(onClick = onClear, enabled = !state.isMutating) { Text(text = "Clear") }
        } else {
            TextButton(onClick = onSet, enabled = !state.isMutating) { Text(text = "Set") }
        }
    }
}

@Composable
internal fun LogisticsSection(
    selected: Set<LogisticsTag>,
    onChange: (Set<LogisticsTag>) -> Unit,
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        LogisticsTag.entries.forEach { tag ->
            val active = tag in selected
            SheetToggleChip(label = tag.label, active = active) {
                onChange(if (active) selected - tag else selected + tag)
            }
        }
    }
}

@Composable
internal fun IntentExtendedSection(
    selected: Set<Intent>,
    onChange: (Set<Intent>) -> Unit,
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Intent.entries.forEach { intent ->
            val active = intent in selected
            SheetToggleChip(label = intent.label, active = active) {
                onChange(if (active) selected - intent else selected + intent)
            }
        }
    }
}

@Composable
internal fun LifestyleSectionHeader(onOpenGlossary: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "Lifestyle",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onOpenGlossary) { Text(text = "Glossary") }
    }
}

@Composable
internal fun LifestyleSection(
    selected: Set<LifestyleTag>,
    onChange: (Set<LifestyleTag>) -> Unit,
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        LifestyleTag.entries.forEach { tag ->
            val active = tag in selected
            SheetToggleChip(label = tag.label, active = active) {
                onChange(if (active) selected - tag else selected + tag)
            }
        }
    }
}

@Composable
internal fun IntentSection(
    selected: Set<DiscoveryStyle>,
    onChange: (Set<DiscoveryStyle>) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        DiscoveryStyle.entries.forEach { style ->
            val active = style in selected
            SheetToggleChip(label = style.label, active = active) {
                val next = if (active) selected - style else selected + style
                onChange(next)
            }
        }
    }
}

@Composable
internal fun TrustSection(
    verified: Boolean,
    facePhotoVerified: Boolean,
    ageVerified: Boolean,
    onTogglesChanged: (Boolean, Boolean, Boolean) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SheetToggleChip(label = "Verified", active = verified) {
            onTogglesChanged(!verified, facePhotoVerified, ageVerified)
        }
        SheetToggleChip(label = "Face photo", active = facePhotoVerified) {
            onTogglesChanged(verified, !facePhotoVerified, ageVerified)
        }
        SheetToggleChip(label = "Age verified", active = ageVerified) {
            onTogglesChanged(verified, facePhotoVerified, !ageVerified)
        }
    }
}

@Composable
internal fun WhoSection(
    singles: Boolean,
    linked: Boolean,
    partnered: Boolean,
    onTogglesChanged: (Boolean, Boolean, Boolean) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SheetToggleChip(label = "Singles", active = singles) {
            onTogglesChanged(!singles, linked, partnered)
        }
        SheetToggleChip(label = "Linked", active = linked) {
            onTogglesChanged(singles, !linked, partnered)
        }
        SheetToggleChip(label = "Partnered", active = partnered) {
            onTogglesChanged(singles, linked, !partnered)
        }
    }
}

@Composable
internal fun GenderSection(
    selected: Set<String>,
    onChange: (Set<String>) -> Unit,
) = IdentityChips(options = ProfileTaxonomies.genderOptions, selected = selected, onChange = onChange)

@Composable
internal fun OrientationSection(
    selected: Set<String>,
    onChange: (Set<String>) -> Unit,
) = IdentityChips(options = ProfileTaxonomies.orientationOptions, selected = selected, onChange = onChange)

/**
 * Multi-select chips for a structured identity taxonomy (gender / orientation). Self-describe is a
 * free-text profile value, not a structured filter option, so it is omitted (a self-describe profile
 * never matches a structured filter — see PROFILE_FILTER_SYNC_SPEC). Selected keys feed the grid query.
 */
@Composable
private fun IdentityChips(
    options: List<ProfileTaxonomies.Option>,
    selected: Set<String>,
    onChange: (Set<String>) -> Unit,
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options
            .filter { it.key != ProfileTaxonomies.SELF_DESCRIBE }
            .forEach { option ->
                val active = option.key in selected
                SheetToggleChip(label = option.label, active = active) {
                    onChange(if (active) selected - option.key else selected + option.key)
                }
            }
    }
}
