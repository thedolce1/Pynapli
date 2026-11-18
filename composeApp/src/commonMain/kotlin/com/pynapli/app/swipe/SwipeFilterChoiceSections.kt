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
import com.pynapli.app.design.handCursor
import com.pynapli.app.grid.DiscoveryStyle
import com.pynapli.app.grid.Intent
import com.pynapli.app.grid.BoundariesFilterSection
import com.pynapli.app.grid.FilterGroupLabel
import com.pynapli.app.grid.LifestyleGroup
import com.pynapli.app.grid.LifestyleSection
import com.pynapli.shared.network.dto.SwipeDealbreakerKey
import com.pynapli.app.grid.LogisticsTag
import com.pynapli.shared.profile.ProfileTaxonomies

@Composable
internal fun WhoSection(
    singles: Boolean,
    linked: Boolean,
    partnered: Boolean,
    onTogglesChanged: (Boolean, Boolean, Boolean) -> Unit,
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
 * never matches a structured filter — PROFILE_FILTER_SYNC_SPEC).
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
                SwipeSheetChip(label = option.label, active = active) {
                    onChange(if (active) selected - option.key else selected + option.key)
                }
            }
    }
}

@Composable
internal fun AgeSection(
    minimumAge: Int?,
    maximumAge: Int?,
    onChange: (Int?, Int?) -> Unit,
) {
    val lo = (minimumAge ?: SwipeFilters.MIN_FILTER_AGE).coerceAtLeast(SwipeFilters.MIN_FILTER_AGE)
    val hi = (maximumAge ?: SwipeFilters.MAX_FILTER_AGE).coerceAtMost(SwipeFilters.MAX_FILTER_AGE)
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
                if (newLo == SwipeFilters.MIN_FILTER_AGE && newHi == SwipeFilters.MAX_FILTER_AGE) {
                    onChange(null, null)
                } else {
                    onChange(newLo, newHi)
                }
            },
            valueRange = SwipeFilters.MIN_FILTER_AGE.toFloat()..SwipeFilters.MAX_FILTER_AGE.toFloat(),
            modifier = Modifier.handCursor(),
        )
    }
}

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
            valueRange = SwipeFilters.MIN_RADIUS_METERS.toFloat()..SwipeFilters.MAX_RADIUS_METERS.toFloat(),
            modifier = Modifier.handCursor(),
        )
    }
}

@Composable
internal fun IntentStylesSection(
    selected: Set<DiscoveryStyle>,
    onChange: (Set<DiscoveryStyle>) -> Unit,
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
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
    onChange: (Set<Intent>) -> Unit,
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
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
    onChange: (only: Boolean, preferred: Boolean) -> Unit,
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
    onChange: (face: Boolean, ageVerified: Boolean) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        // "Verified only" already means a reviewed selfie (FILTERS_SPEC), the same signal as face photo
        // verified, so this chip only appears to clear an older saved filter that still has it on.
        if (facePhotoVerified) {
            SwipeSheetChip(label = "Photo verified", active = true) {
                onChange(false, ageVerified)
            }
        }
        SwipeSheetChip(label = "Age verified", active = ageVerified) {
            onChange(facePhotoVerified, !ageVerified)
        }
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
        SwipeSheetChip(label = "Has photos", active = withPhotos, onClick = onWithPhotosToggle)
        SwipeSheetChip(label = "Has private album", active = hasPrivateAlbum, onClick = onHasPrivateAlbumToggle)
    }
}

/**
 * Swipe lifestyle filter: the Grid's grouped section with a dealbreaker Lock on Swap, Room and
 * Experience, so those survive deck relaxation when the viewer says they must.
 */
@Composable
internal fun LifestyleTagsSection(
    draft: SwipeFilters,
    onDraftChange: (SwipeFilters) -> Unit,
) {
    LifestyleSection(
        selected = draft.lifestyleTags,
        onChange = { tags ->
            // A group emptied of selections cannot stay locked.
            val emptied = LifestyleGroup.entries.filter { g -> tags.none { it.group == g } }.mapNotNull { it.dealbreakerKey }
            onDraftChange(draft.copy(lifestyleTags = tags, dealbreakers = draft.dealbreakers - emptied.toSet()))
        },
        groupHeader = { group ->
            val key = group.dealbreakerKey
            if (key == null) {
                FilterGroupLabel(group.label)
            } else {
                SubsectionHeader(
                    title = group.label,
                    dealbreakerKey = key,
                    eligible = draft.lifestyleTags.any { it.group == group },
                    locked = key in draft.dealbreakers,
                    onLockChange = { locked ->
                        onDraftChange(draft.copy(dealbreakers = toggleDealbreaker(draft.dealbreakers, key, locked)))
                    },
                )
            }
        },
        chip = { label, active, onClick -> SwipeSheetChip(label = label, active = active, onClick = onClick) },
    )
}

/** Swipe boundaries filter with its own dealbreaker Lock. */
@Composable
internal fun BoundariesSection(
    draft: SwipeFilters,
    onDraftChange: (SwipeFilters) -> Unit,
) {
    SubsectionHeader(
        title = "Boundaries",
        dealbreakerKey = SwipeDealbreakerKey.BOUNDARIES,
        eligible = draft.boundaryItems.isNotEmpty(),
        locked = SwipeDealbreakerKey.BOUNDARIES in draft.dealbreakers,
        onLockChange = { locked ->
            onDraftChange(draft.copy(dealbreakers = toggleDealbreaker(draft.dealbreakers, SwipeDealbreakerKey.BOUNDARIES, locked)))
        },
    )
    BoundariesFilterSection(
        selected = draft.boundaryItems,
        onChange = { items ->
            val dealbreakers = if (items.isEmpty()) draft.dealbreakers - SwipeDealbreakerKey.BOUNDARIES else draft.dealbreakers
            onDraftChange(draft.copy(boundaryItems = items, dealbreakers = dealbreakers))
        },
        chip = { label, active, onClick -> SwipeSheetChip(label = label, active = active, onClick = onClick) },
    )
}

private val LifestyleGroup.dealbreakerKey: String?
    get() =
        when (this) {
            LifestyleGroup.Swap -> SwipeDealbreakerKey.SWAP
            LifestyleGroup.Room -> SwipeDealbreakerKey.ROOM
            LifestyleGroup.Experience -> SwipeDealbreakerKey.EXPERIENCE
            LifestyleGroup.Other -> null
        }

@Composable
internal fun LogisticsTagsSection(
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
            SwipeSheetChip(label = tag.label, active = active) {
                onChange(if (active) selected - tag else selected + tag)
            }
        }
    }
}
