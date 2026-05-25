package com.pynapli.app.swipe

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.pynapli.shared.network.dto.SavedSwipeFilterDto
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
    onOpenStore: () -> Unit = {},
    savedState: SavedSwipeFiltersUiState = SavedSwipeFiltersUiState(),
    onSavePreset: (name: String, SwipeFilters) -> Unit = { _, _ -> },
    onApplySavedPreset: (SavedSwipeFilterDto) -> Unit = {},
    onDeleteSavedPreset: (id: String) -> Unit = {}
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
            onOpenStore = onOpenStore,
            savedState = savedState,
            onSavePreset = onSavePreset,
            onApplySavedPreset = onApplySavedPreset,
            onDeleteSavedPreset = onDeleteSavedPreset
        )
    }
}

@Composable
internal fun SwipeFiltersSheetBody(
    initial: SwipeFilters,
    onDismiss: () -> Unit,
    onApply: (SwipeFilters) -> Unit,
    onClearAll: () -> Unit,
    onOpenStore: () -> Unit = {},
    savedState: SavedSwipeFiltersUiState = SavedSwipeFiltersUiState(),
    onSavePreset: (name: String, SwipeFilters) -> Unit = { _, _ -> },
    onApplySavedPreset: (SavedSwipeFilterDto) -> Unit = {},
    onDeleteSavedPreset: (id: String) -> Unit = {}
) {
    var draft by remember(initial) { mutableStateOf(initial) }
    var saveName by remember { mutableStateOf("") }
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

            // FILTERS_SPEC L463-477: saved presets. Free-tier viewers get a paywall
            // copy in place of the chip row; paid viewers see their presets and can
            // tap to recall (apply immediately + close sheet).
            if (savedState.items.isNotEmpty() || savedState.entitlementRequired) {
                SectionTitle("Saved filters")
                SavedSwipePresetsSection(
                    state = savedState,
                    onApply = { dto -> onApplySavedPreset(dto) },
                    onDelete = onDeleteSavedPreset
                )
            }

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

            // FILTERS_SPEC L463-477: save-current-preset row, hidden behind the same
            // entitlement gate as the saved-filters section (free-tier viewers see the
            // paywall copy there instead).
            if (!savedState.entitlementRequired) {
                SectionTitle("Save current filters")
                SaveSwipePresetRow(
                    name = saveName,
                    onNameChange = { saveName = it },
                    isSaving = savedState.isSaving,
                    onSave = {
                        if (saveName.isNotBlank()) {
                            onSavePreset(saveName, draft)
                            saveName = ""
                        }
                    }
                )
            }

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

private fun toggleDealbreaker(current: Set<String>, key: String, locked: Boolean): Set<String> =
    if (locked) current + key else current - key
