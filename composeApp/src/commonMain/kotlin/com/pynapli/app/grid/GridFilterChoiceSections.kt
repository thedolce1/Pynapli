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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.pynapli.app.design.handCursor
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
            modifier = Modifier.handCursor(),
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
            modifier = Modifier.handCursor(),
        )
    }
}

@Composable
internal fun PresenceSection(
    online: Boolean,
    tonightAvailable: Boolean,
    activeToday: Boolean,
    fresh: Boolean,
    travelingHere: Boolean,
    onOnlineToggle: () -> Unit,
    onTonightToggle: () -> Unit,
    onActiveTodayToggle: () -> Unit,
    onFreshToggle: () -> Unit,
    onTravelingHereToggle: () -> Unit,
    visitingSoon: Boolean = false,
    onVisitingSoonToggle: () -> Unit = {},
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SheetToggleChip(label = "Online now", active = online, onClick = onOnlineToggle)
        SheetToggleChip(label = "Active today", active = activeToday, onClick = onActiveTodayToggle)
        SheetToggleChip(label = "Fresh", active = fresh, onClick = onFreshToggle)
        SheetToggleChip(label = "Traveling here", active = travelingHere, onClick = onTravelingHereToggle)
        SheetToggleChip(label = "Visiting soon", active = visitingSoon, onClick = onVisitingSoonToggle)
        SheetToggleChip(label = "Playing tonight", active = tonightAvailable, onClick = onTonightToggle)
    }
}

@Composable
internal fun PhotosSection(
    withPhotos: Boolean,
    hasPrivateAlbum: Boolean,
    onWithPhotosToggle: () -> Unit,
    onHasPrivateAlbumToggle: () -> Unit,
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SheetToggleChip(label = "Has photos", active = withPhotos, onClick = onWithPhotosToggle)
        SheetToggleChip(label = "Has private album", active = hasPrivateAlbum, onClick = onHasPrivateAlbumToggle)
    }
}

/**
 * Entry into Playing Tonight from the filter sheet (PLAYING_TONIGHT_SPEC): sessions are the only
 * Tonight model, so this opens the existing start/manage flow instead of toggling a profile flag.
 */
@Composable
internal fun TonightSessionEntryRow(
    sessionActive: Boolean,
    onOpenTonight: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (sessionActive) "You're playing tonight" else "Playing tonight? Start a session",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        TextButton(
            onClick = onOpenTonight,
            modifier = Modifier.testTag(GridFilterTags.OPEN_TONIGHT).handCursor(),
        ) { Text(text = if (sessionActive) "View" else "Start") }
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
        TextButton(onClick = onOpenGlossary, modifier = Modifier.handCursor()) { Text(text = "Glossary") }
    }
}

/**
 * Lifestyle filter, grouped the way the server matches it: any of the chips inside a group, all of
 * the groups that have a selection (Soft swap + Same room means both).
 */
@Composable
internal fun LifestyleSection(
    selected: Set<LifestyleTag>,
    onChange: (Set<LifestyleTag>) -> Unit,
    groupHeader: @Composable (LifestyleGroup) -> Unit = { group -> FilterGroupLabel(group.label) },
    chip: @Composable (label: String, active: Boolean, onClick: () -> Unit) -> Unit = { label, active, onClick ->
        SheetToggleChip(label = label, active = active, onClick = onClick)
    },
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        LifestyleGroup.entries.forEach { group ->
            groupHeader(group)
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                LifestyleTag.inGroup(group).forEach { tag ->
                    val active = tag in selected
                    chip(tag.label, active) { onChange(if (active) selected - tag else selected + tag) }
                }
            }
        }
    }
}

/** Boundaries filter: hides people who marked a chosen item "not for me". */
@Composable
internal fun BoundariesFilterSection(
    selected: Set<Boundary>,
    onChange: (Set<Boundary>) -> Unit,
    chip: @Composable (label: String, active: Boolean, onClick: () -> Unit) -> Unit = { label, active, onClick ->
        SheetToggleChip(label = label, active = active, onClick = onClick)
    },
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "Hide people who've said \"not for me\" to:",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Boundary.entries.forEach { boundary ->
                val active = boundary in selected
                chip(boundary.label, active) { onChange(if (active) selected - boundary else selected + boundary) }
            }
        }
    }
}

@Composable
internal fun FilterGroupLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
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
    verifiedCouples: Boolean = false,
    onVerifiedCouplesToggle: () -> Unit = {},
) {
    // "Verified" and "face photo verified" are the same reviewed-selfie signal (FILTERS_SPEC), so they
    // share one chip; turning it off also clears the face-photo flag an older saved filter may carry.
    val photoVerified = verified || facePhotoVerified
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SheetToggleChip(label = "Photo verified", active = photoVerified) {
            onTogglesChanged(!photoVerified, false, ageVerified)
        }
        SheetToggleChip(label = "Age verified", active = ageVerified) {
            onTogglesChanged(verified, facePhotoVerified, !ageVerified)
        }
        // Both linked partners selfie-verified: the couple-verified badge (independent of "Linked").
        SheetToggleChip(
            label = VERIFIED_COUPLES_ONLY_LABEL,
            active = verifiedCouples,
            onClick = onVerifiedCouplesToggle,
        )
    }
}

/** Shared label for the Grid and Swipe "Verified couples only" chips. */
internal const val VERIFIED_COUPLES_ONLY_LABEL = "Verified couples only"

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
