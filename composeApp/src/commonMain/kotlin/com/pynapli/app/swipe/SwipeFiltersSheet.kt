package com.pynapli.app.swipe

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pynapli.app.grid.DiscoveryStyle
import com.pynapli.app.grid.Intent
import com.pynapli.app.grid.LifestyleTag
import com.pynapli.app.grid.LogisticsTag
import com.pynapli.shared.network.dto.SwipeDealbreakerKey

/**
 * Swipe filter sheet (FILTERS_SPEC L263-415).
 *
 * Filter groups mirror the spec's recommended order: Who, Age, Distance, Intent,
 * Verified, Advanced. Dealbreaker toggles (FILTERS_SPEC L354-371) sit inline on
 * each dealbreaker-capable section header. The paywall hint for advanced filters
 * lands in C5; for now, if a free-tier user applies advanced filters the server
 * returns ENTITLEMENT_REQUIRED and `SwipeStateHolder` surfaces a paywall message.
 *
 * Spec L401: "client should request a new deck session for changes" — Apply
 * routes through `stateHolder.applyFilters(draft)` which triggers a fresh
 * `load()` and discards the current deck cursor.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SwipeFiltersSheet(
    initial: SwipeFilters,
    onDismiss: () -> Unit,
    onApply: (SwipeFilters) -> Unit,
    onClearAll: () -> Unit,
    onOpenStore: () -> Unit = {}
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = Modifier.testTag(SwipeFilterTags.SHEET)
    ) {
        // Body is extracted so tests can drive it without going through the
        // ModalBottomSheet popup window, which the desktop Compose test harness
        // does not surface into the same node tree.
        SwipeFiltersSheetBody(
            initial = initial,
            onDismiss = onDismiss,
            onApply = onApply,
            onClearAll = onClearAll,
            onOpenStore = onOpenStore
        )
    }
}

@Composable
internal fun SwipeFiltersSheetBody(
    initial: SwipeFilters,
    onDismiss: () -> Unit,
    onApply: (SwipeFilters) -> Unit,
    onClearAll: () -> Unit,
    onOpenStore: () -> Unit = {}
) {
    var draft by remember(initial) { mutableStateOf(initial) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(top = 12.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
            // Spec L13 + L267: "Swipe is for intent. Grid is for now." Set the tone so the
            // user knows these filters shape the deck, they aren't a live directory query.
            // Spec L370: explicitly call out that dealbreakers may reduce recommendations.
            SectionIntro(
                "These shape who shows up in your deck. Most filters are flexible; lock the " +
                    "ones that must apply. Locked filters may shrink your deck."
            )

            SectionHeader(
                title = "Who",
                dealbreakerKey = SwipeDealbreakerKey.LINKED_ONLY,
                eligible = draft.linked,
                locked = SwipeDealbreakerKey.LINKED_ONLY in draft.dealbreakers,
                onLockChange = { locked ->
                    draft = draft.copy(
                        dealbreakers = toggleDealbreaker(
                            draft.dealbreakers,
                            SwipeDealbreakerKey.LINKED_ONLY,
                            locked
                        )
                    )
                }
            )
            WhoSection(
                singles = draft.singles,
                linked = draft.linked,
                partnered = draft.partnered,
                onTogglesChanged = { singles, linked, partnered ->
                    // Spec L369: dealbreakers must be reversible — clear LINKED_ONLY when
                    // the underlying Linked toggle goes back off, so a stale lock can't
                    // silently constrain the deck.
                    val dealbreakers = if (!linked) {
                        draft.dealbreakers - SwipeDealbreakerKey.LINKED_ONLY
                    } else {
                        draft.dealbreakers
                    }
                    draft = draft.copy(
                        singles = singles,
                        linked = linked,
                        partnered = partnered,
                        dealbreakers = dealbreakers
                    )
                }
            )

            SectionHeader(
                title = "Age",
                dealbreakerKey = SwipeDealbreakerKey.AGE,
                eligible = draft.minimumAge != null || draft.maximumAge != null,
                locked = SwipeDealbreakerKey.AGE in draft.dealbreakers,
                onLockChange = { locked ->
                    draft = draft.copy(
                        dealbreakers = toggleDealbreaker(
                            draft.dealbreakers,
                            SwipeDealbreakerKey.AGE,
                            locked
                        )
                    )
                }
            )
            AgeSection(
                minimumAge = draft.minimumAge,
                maximumAge = draft.maximumAge,
                onChange = { lo, hi ->
                    val unbounded = lo == null && hi == null
                    val dealbreakers = if (unbounded) {
                        draft.dealbreakers - SwipeDealbreakerKey.AGE
                    } else {
                        draft.dealbreakers
                    }
                    draft = draft.copy(minimumAge = lo, maximumAge = hi, dealbreakers = dealbreakers)
                }
            )

            SectionHeader(
                title = "Distance",
                dealbreakerKey = SwipeDealbreakerKey.DISTANCE,
                // Spec L317-319: distance is flexible by default but can be locked at any
                // radius. Always lock-eligible.
                eligible = true,
                locked = SwipeDealbreakerKey.DISTANCE in draft.dealbreakers,
                onLockChange = { locked ->
                    draft = draft.copy(
                        dealbreakers = toggleDealbreaker(
                            draft.dealbreakers,
                            SwipeDealbreakerKey.DISTANCE,
                            locked
                        )
                    )
                }
            )
            DistanceSection(
                radiusMeters = draft.radiusMeters,
                onChange = { meters -> draft = draft.copy(radiusMeters = meters) }
            )

            SectionTitle("Intent")
            // FILTERS_SPEC L323-338. discoveryStyles covers Solo/Together/Both/Social/Ask;
            // intents covers Dating/Chat first/Events and the Open-to-X family. Each
            // sub-axis carries its own dealbreaker key so the user can lock style without
            // locking the Open-to-X intents (and vice versa).
            SubsectionHeader(
                title = "Style",
                dealbreakerKey = SwipeDealbreakerKey.DISCOVERY_STYLES,
                eligible = draft.discoveryStyles.isNotEmpty(),
                locked = SwipeDealbreakerKey.DISCOVERY_STYLES in draft.dealbreakers,
                onLockChange = { locked ->
                    draft = draft.copy(
                        dealbreakers = toggleDealbreaker(
                            draft.dealbreakers,
                            SwipeDealbreakerKey.DISCOVERY_STYLES,
                            locked
                        )
                    )
                }
            )
            IntentStylesSection(
                selected = draft.discoveryStyles,
                onChange = { styles ->
                    val dealbreakers = if (styles.isEmpty()) {
                        draft.dealbreakers - SwipeDealbreakerKey.DISCOVERY_STYLES
                    } else {
                        draft.dealbreakers
                    }
                    draft = draft.copy(discoveryStyles = styles, dealbreakers = dealbreakers)
                }
            )
            SubsectionHeader(
                title = "Looking for",
                dealbreakerKey = SwipeDealbreakerKey.INTENTS,
                eligible = draft.intents.isNotEmpty(),
                locked = SwipeDealbreakerKey.INTENTS in draft.dealbreakers,
                onLockChange = { locked ->
                    draft = draft.copy(
                        dealbreakers = toggleDealbreaker(
                            draft.dealbreakers,
                            SwipeDealbreakerKey.INTENTS,
                            locked
                        )
                    )
                }
            )
            IntentExtendedSection(
                selected = draft.intents,
                onChange = { intents ->
                    val dealbreakers = if (intents.isEmpty()) {
                        draft.dealbreakers - SwipeDealbreakerKey.INTENTS
                    } else {
                        draft.dealbreakers
                    }
                    draft = draft.copy(intents = intents, dealbreakers = dealbreakers)
                }
            )

            SectionHeader(
                title = "Verified",
                dealbreakerKey = SwipeDealbreakerKey.VERIFIED,
                // Spec L350-351: "Verified preferred" is a ranking weight, not a dealbreaker.
                // Only "Verified only" qualifies the section for the dealbreaker lock.
                eligible = draft.verifiedOnly,
                locked = SwipeDealbreakerKey.VERIFIED in draft.dealbreakers,
                onLockChange = { locked ->
                    draft = draft.copy(
                        dealbreakers = toggleDealbreaker(
                            draft.dealbreakers,
                            SwipeDealbreakerKey.VERIFIED,
                            locked
                        )
                    )
                }
            )
            // FILTERS_SPEC L344-352: three mutually-exclusive verified modes.
            VerifiedModeSection(
                verifiedOnly = draft.verifiedOnly,
                verifiedPreferred = draft.verifiedPreferred,
                onChange = { only, preferred ->
                    val dealbreakers = if (!only) {
                        draft.dealbreakers - SwipeDealbreakerKey.VERIFIED
                    } else {
                        draft.dealbreakers
                    }
                    draft = draft.copy(
                        verifiedOnly = only,
                        verifiedPreferred = preferred,
                        dealbreakers = dealbreakers
                    )
                }
            )

            SectionTitle("Advanced")
            // FILTERS_SPEC L373-391. Face-photo / age-verified are trust filters open to
            // all tiers; lifestyle tags and non-public-meet logistics tags are gated to
            // Pynapli+ (mirrors `DiscoveryFilters.requiresAdvancedEntitlement` server-side).
            // If a free-tier user selects a gated tag we surface this banner pre-emptively
            // instead of relying on a server `ENTITLEMENT_REQUIRED` round-trip — they can
            // tap Subscribe before paying the latency cost of a failed Apply. The server
            // remains authority on entitlement; the banner is advisory only.
            if (draft.requiresAdvancedEntitlement) {
                AdvancedPaywallBanner(
                    onSubscribe = {
                        onDismiss()
                        onOpenStore()
                    }
                )
            }
            FacePhotoAndAgeVerifiedSection(
                facePhotoVerified = draft.facePhotoVerified,
                ageVerified = draft.ageVerified,
                onChange = { face, ageV ->
                    draft = draft.copy(facePhotoVerified = face, ageVerified = ageV)
                }
            )
            LifestyleTagsSection(
                selected = draft.lifestyleTags,
                onChange = { draft = draft.copy(lifestyleTags = it) }
            )
            LogisticsTagsSection(
                selected = draft.logisticsTags,
                onChange = { draft = draft.copy(logisticsTags = it) }
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = onClearAll,
                    modifier = Modifier.testTag(SwipeFilterTags.CLEAR_ALL)
                ) { Text(text = "Clear all") }
                Box(modifier = Modifier.weight(1f))
                OutlinedButton(onClick = onDismiss) { Text(text = "Cancel") }
                Button(
                    onClick = { onApply(draft) },
                    modifier = Modifier.testTag(SwipeFilterTags.APPLY)
                ) { Text(text = "Apply") }
            }
    }
}

@Composable
private fun SectionIntro(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurface
    )
}

@Composable
private fun SectionHeader(
    title: String,
    dealbreakerKey: String,
    eligible: Boolean,
    locked: Boolean,
    onLockChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        DealbreakerLockChip(
            dealbreakerKey = dealbreakerKey,
            eligible = eligible,
            locked = locked,
            onClick = { onLockChange(!locked) }
        )
    }
}

@Composable
private fun SubsectionHeader(
    title: String,
    dealbreakerKey: String,
    eligible: Boolean,
    locked: Boolean,
    onLockChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        DealbreakerLockChip(
            dealbreakerKey = dealbreakerKey,
            eligible = eligible,
            locked = locked,
            onClick = { onLockChange(!locked) }
        )
    }
}

/**
 * Inline "Lock" toggle per FILTERS_SPEC L354-371. Disabled when the underlying
 * filter is inactive so the user can't lock something that isn't set — the
 * spec's "clear and reversible" rule.
 *
 * When locked, this filter is sent in `SwipeDeckRequestDto.dealbreakers` and the
 * server treats it as a hard gate (skips it from B3 relaxation).
 */
@Composable
private fun DealbreakerLockChip(
    dealbreakerKey: String,
    eligible: Boolean,
    locked: Boolean,
    onClick: () -> Unit
) {
    FilterChip(
        selected = locked,
        onClick = onClick,
        enabled = eligible,
        label = {
            Text(
                text = if (locked) "Locked" else "Lock",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelSmall
            )
        },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.tertiaryContainer,
            selectedLabelColor = MaterialTheme.colorScheme.onTertiaryContainer
        ),
        modifier = Modifier.testTag(SwipeFilterTags.dealbreakerTag(dealbreakerKey))
    )
}

private fun toggleDealbreaker(current: Set<String>, key: String, locked: Boolean): Set<String> =
    if (locked) current + key else current - key

/**
 * Pre-emptive paywall hint surfaced in-sheet when the draft selects any gated
 * advanced filter (FILTERS_SPEC L373-391, mirrors `requiresAdvancedEntitlement`).
 * Tapping Subscribe closes the sheet and hands off to the host's onOpenStore.
 * Server remains the entitlement authority — this banner just shortens the path
 * to the store for the user.
 */
@Composable
private fun AdvancedPaywallBanner(onSubscribe: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .testTag(SwipeFilterTags.ADVANCED_PAYWALL),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(
            imageVector = Icons.Filled.Lock,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp)
        )
        Text(
            text = "Advanced filters require Pynapli+.",
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        TextButton(
            onClick = onSubscribe,
            modifier = Modifier.testTag(SwipeFilterTags.ADVANCED_PAYWALL_SUBSCRIBE)
        ) {
            Text(text = "Subscribe", maxLines = 1)
        }
    }
}

@Composable
private fun WhoSection(
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
private fun AgeSection(
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
private fun DistanceSection(radiusMeters: Int, onChange: (Int) -> Unit) {
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
private fun IntentStylesSection(
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
private fun IntentExtendedSection(
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
private fun VerifiedModeSection(
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
private fun FacePhotoAndAgeVerifiedSection(
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
private fun LifestyleTagsSection(
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
private fun LogisticsTagsSection(
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

@Composable
private fun SwipeSheetChip(label: String, active: Boolean, onClick: () -> Unit) {
    AssistChip(
        onClick = onClick,
        label = { Text(text = label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        colors = AssistChipDefaults.assistChipColors(
            containerColor = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
            labelColor = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
        ),
        border = AssistChipDefaults.assistChipBorder(
            enabled = true,
            borderColor = MaterialTheme.colorScheme.outline
        )
    )
}

internal object SwipeFilterTags {
    const val SHEET = "swipe-filters-sheet"
    const val APPLY = "swipe-filters-sheet-apply"
    const val CLEAR_ALL = "swipe-filters-sheet-clear-all"
    const val OPEN_SHEET = "swipe-filters-open-sheet"
    const val ADVANCED_PAYWALL = "swipe-filters-advanced-paywall"
    const val ADVANCED_PAYWALL_SUBSCRIBE = "swipe-filters-advanced-paywall-subscribe"

    fun dealbreakerTag(key: String): String = "swipe-filters-dealbreaker-$key"
}
