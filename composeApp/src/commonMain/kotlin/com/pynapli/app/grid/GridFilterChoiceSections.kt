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
import com.pynapli.shared.distance.DiscoveryRadiusSteps
import com.pynapli.shared.profile.ProfileTaxonomies
import kotlin.math.roundToInt

/**
 * The Grid radius slider moves between the server's radius steps only (DiscoveryRadiusSteps: 2, 5,
 * 10, 25, 50, 100 km), and the label names the step, which is exactly the radius the server searches.
 */
@Composable
internal fun DistanceSection(
    radiusMeters: Int,
    onChange: (Int) -> Unit,
) {
    val steps = DiscoveryRadiusSteps.METERS
    Column {
        Text(
            text = distanceRadiusLabel(radiusMeters),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Slider(
            value = DiscoveryRadiusSteps.indexOf(GridFilters.snapRadius(radiusMeters)).toFloat(),
            onValueChange = { value -> onChange(radiusForSliderPosition(value)) },
            valueRange = 0f..(steps.size - 1).toFloat(),
            // Material counts the stops between the two ends.
            steps = steps.size - 2,
            modifier = Modifier.handCursor(),
        )
    }
}

/** "Within 25 km": the server's step for [radiusMeters] (whole km; every step is a whole km). */
internal fun distanceRadiusLabel(radiusMeters: Int): String = "Within ${GridFilters.snapRadius(radiusMeters) / 1_000} km"

/** The step at a slider position (an index into DiscoveryRadiusSteps.METERS). */
internal fun radiusForSliderPosition(position: Float): Int {
    val steps = DiscoveryRadiusSteps.METERS
    return steps[position.roundToInt().coerceIn(0, steps.lastIndex)]
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
    haventChatted: Boolean = false,
    onHaventChattedToggle: () -> Unit = {},
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
        SheetToggleChip(label = "Haven't chatted (24 h)", active = haventChatted, onClick = onHaventChattedToggle)
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

/**
 * Lifestyle match mode (plan G2-4, Grindr's tags): "Any" is the usual any-of within a group; "All of up
 * to 3" means every chosen tag must be on the profile.
 */
@Composable
internal fun LifestyleMatchModeRow(
    matchAll: Boolean,
    onChange: (Boolean) -> Unit,
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SheetToggleChip(label = "Any", active = !matchAll, onClick = { onChange(false) })
        SheetToggleChip(label = "All of (up to ${GridFilters.MAX_LIFESTYLE_ALL_OF})", active = matchAll, onClick = { onChange(true) })
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
        // "Partner photo verified" (Partner section) is the partner-only half of this.
        SheetToggleChip(
            label = VERIFIED_COUPLES_ONLY_LABEL,
            active = verifiedCouples,
            onClick = onVerifiedCouplesToggle,
        )
    }
}

/** Shared label for the Grid and Swipe "Verified couples only" chips. */
internal const val VERIFIED_COUPLES_ONLY_LABEL = "Verified couples only"

/**
 * FILTERS_SPEC "Who", from the visible partner link (server-decided): Singles = no partner shown to
 * the viewer, Linked = a partner shown. "Partnered" merged into Linked: the free-text relationship
 * context it used to match was never reliable. Both on is the union, everyone.
 */
@Composable
internal fun WhoSection(
    singles: Boolean,
    linked: Boolean,
    onTogglesChanged: (Boolean, Boolean) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SheetToggleChip(label = "Singles", active = singles) {
            onTogglesChanged(!singles, linked)
        }
        SheetToggleChip(label = "Linked", active = linked) {
            onTogglesChanged(singles, !linked)
        }
    }
}

/**
 * "Single women" / "Single men" (plan G2-3): one-tap shortcuts for Singles + that gender, i.e. the
 * person's own declared gender and no partner shown to the viewer. Nothing new on the server: they
 * set the same Who and Gender filters the sections below show. Tapping an active one clears it.
 */
@Composable
internal fun SinglesShortcutRow(
    filters: GridFilters,
    onChange: (GridFilters) -> Unit,
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SinglesShortcut.entries.forEach { shortcut ->
            val active = shortcut.isActive(filters)
            SheetToggleChip(label = shortcut.label, active = active) {
                onChange(if (active) shortcut.cleared(filters) else shortcut.applied(filters))
            }
        }
        // The sheet twin of the quick-row chip, so everything the Filters count counts is here.
        SheetToggleChip(label = NO_SINGLE_MEN_LABEL, active = filters.noSingleMen) {
            onChange(filters.copy(noSingleMen = !filters.noSingleMen))
        }
    }
}

/**
 * The Who shortcuts: Singles plus the declared gender keys of one gender identity (founder decision
 * 2026-11-27): Single women = woman and trans woman, Single men = man and trans man. Non-binary and
 * other identities are reachable through the Gender section, never through a shortcut.
 */
internal enum class SinglesShortcut(
    val label: String,
    val genderKeys: Set<String>,
) {
    SingleWomen("Single women", setOf("woman", "trans_woman")),
    SingleMen("Single men", setOf("man", "trans_man")),
    ;

    fun isActive(filters: GridFilters): Boolean = filters.singles && !filters.linked && filters.genders == genderKeys

    // Singles rules the partner filters out, so the applied state drops them (they could never match).
    fun applied(filters: GridFilters): GridFilters = filters.copy(singles = true, linked = false, genders = genderKeys).withPartnerFiltersReconciled()

    fun cleared(filters: GridFilters): GridFilters = filters.copy(singles = false, genders = emptySet())
}

/**
 * FILTERS_SPEC "Partner" (plan G2-3): the visibly linked partner's own declared gender, age,
 * orientation and photo verification. The server only ever tests a partner it shows this viewer, so
 * these imply Linked. With Singles alone they cannot match anyone, so the section says so instead of
 * offering dead chips (and the filters are dropped on apply).
 */
@Composable
internal fun PartnerSection(
    filters: GridFilters,
    onChange: (GridFilters) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().testTag(GridFilterTags.PARTNER_SECTION),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (!filters.partnerFiltersApply) {
            Text(
                text = PARTNER_NEEDS_LINKED_HINT,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            PartnerChoices(filters = filters, onChange = onChange)
        }
    }
}

@Composable
private fun PartnerChoices(
    filters: GridFilters,
    onChange: (GridFilters) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = PARTNER_SECTION_HINT,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FilterGroupLabel("Partner's gender")
        GenderSection(
            selected = filters.partnerGenders,
            onChange = { onChange(filters.copy(partnerGenders = it)) },
        )
        FilterGroupLabel("Partner's age")
        AgeSection(
            minimumAge = filters.partnerMinimumAge,
            maximumAge = filters.partnerMaximumAge,
            onChange = { lo, hi -> onChange(filters.copy(partnerMinimumAge = lo, partnerMaximumAge = hi)) },
        )
        FilterGroupLabel("Partner's orientation")
        OrientationSection(
            selected = filters.partnerOrientations,
            onChange = { onChange(filters.copy(partnerOrientations = it)) },
        )
        FilterGroupLabel("Partner's trust")
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SheetToggleChip(label = PARTNER_VERIFIED_LABEL, active = filters.partnerVerified) {
                onChange(filters.copy(partnerVerified = !filters.partnerVerified))
            }
        }
        Text(
            text = PARTNER_VERIFIED_HINT,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

internal const val PARTNER_SECTION_HINT: String =
    "Matches the partner they show you, by what that partner put on their own profile. Using any of these shows only people with a visible linked partner."
internal const val PARTNER_NEEDS_LINKED_HINT: String =
    "Partner filters need a visible partner. Turn on Linked, or turn off Singles, to use them."
internal const val PARTNER_VERIFIED_LABEL: String = "Partner photo verified"
internal const val PARTNER_VERIFIED_HINT: String =
    "Only the partner needs to be verified. \"Verified couples only\" under Trust needs both of them."

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
