package com.pynapli.app.grid

import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.pynapli.app.design.PynapliBottomSheet
import com.pynapli.app.design.PynapliSheetActionRow
import com.pynapli.app.design.PynapliSheetColumn
import com.pynapli.app.design.handCursor
import com.pynapli.shared.network.dto.GridCountResponseDto
import kotlinx.coroutines.delay

@Composable
internal fun GridFiltersSheet(
    initial: GridFilters,
    savedState: SavedGridFiltersUiState = SavedGridFiltersUiState(),
    tonightSessionActive: Boolean = false,
    lifestyleFiltersAllowed: Boolean = true,
    // Plan G2-4: "Show N people" for the draft, from the server (debounced here while editing).
    countFor: suspend (GridFilters) -> GridCountResponseDto? = { null },
    onDismiss: () -> Unit,
    onApply: (GridFilters) -> Unit,
    onClearAll: () -> Unit,
    onSavePreset: (name: String, GridFilters) -> Unit = { _, _ -> },
    onApplySavedPreset: (com.pynapli.shared.network.dto.SavedGridFilterDto) -> Unit = {},
    onDeleteSavedPreset: (id: String) -> Unit = {},
    onOpenTonight: () -> Unit = {},
) {
    val initialDraft =
        remember(initial, lifestyleFiltersAllowed) {
            initial.withLifestyleAccess(lifestyleFiltersAllowed)
        }
    var draft by remember(initialDraft) { mutableStateOf(initialDraft) }
    var saveName by remember { mutableStateOf("") }
    var glossaryVisible by remember { mutableStateOf(false) }
    var count by remember { mutableStateOf<GridCountResponseDto?>(null) }
    LaunchedEffect(draft) {
        // Debounced: a run of taps asks once, and a newer draft cancels the older wait or request.
        // The old number never stands for a changed draft: the button says "Apply" until the new one.
        count = null
        delay(COUNT_DEBOUNCE_MILLIS)
        count = countFor(draft.withLifestyleAccess(lifestyleFiltersAllowed).withPartnerFiltersReconciled())
    }

    PynapliBottomSheet(
        onDismiss = onDismiss,
        modifier = Modifier.testTag(GridFilterTags.SHEET),
    ) {
        PynapliSheetColumn {
            if (savedState.items.isNotEmpty() || savedState.entitlementRequired) {
                SectionTitle("Saved")
                SavedFiltersSection(
                    state = savedState,
                    onApply = { dto ->
                        draft =
                            GridFilters
                                .fromGridQueryRequest(dto.payload)
                                .withLifestyleAccess(lifestyleFiltersAllowed)
                        onApplySavedPreset(dto)
                    },
                    onDelete = onDeleteSavedPreset,
                )
            }

            SectionTitle("Distance")
            DistanceSection(
                radiusMeters = draft.radiusMeters,
                onChange = { meters -> draft = draft.copy(radiusMeters = meters) },
            )

            SectionTitle("Age")
            AgeSection(
                minimumAge = draft.minimumAge,
                maximumAge = draft.maximumAge,
                onChange = { lo, hi -> draft = draft.copy(minimumAge = lo, maximumAge = hi) },
            )

            SectionTitle("Presence")
            PresenceSection(
                online = draft.online,
                tonightAvailable = draft.tonightAvailable,
                activeToday = draft.activeToday,
                fresh = draft.fresh,
                travelingHere = draft.travelingHere,
                visitingSoon = draft.visitingSoon,
                onVisitingSoonToggle = { draft = draft.copy(visitingSoon = !draft.visitingSoon) },
                onOnlineToggle = { draft = draft.copy(online = !draft.online) },
                onTonightToggle = { draft = draft.copy(tonightAvailable = !draft.tonightAvailable) },
                onActiveTodayToggle = { draft = draft.copy(activeToday = !draft.activeToday) },
                onFreshToggle = { draft = draft.copy(fresh = !draft.fresh) },
                onTravelingHereToggle = { draft = draft.copy(travelingHere = !draft.travelingHere) },
                haventChatted = draft.haventChatted,
                onHaventChattedToggle = { draft = draft.copy(haventChatted = !draft.haventChatted) },
            )
            TonightSessionEntryRow(
                sessionActive = tonightSessionActive,
                onOpenTonight = onOpenTonight,
            )

            SectionTitle("Who")
            WhoSection(
                singles = draft.singles,
                linked = draft.linked,
                onTogglesChanged = { singles, linked ->
                    draft = draft.copy(singles = singles, linked = linked)
                },
            )
            SinglesShortcutRow(filters = draft, onChange = { draft = it })

            SectionTitle("Gender")
            GenderSection(
                selected = draft.genders,
                onChange = { draft = draft.copy(genders = it) },
            )

            SectionTitle("Orientation")
            OrientationSection(
                selected = draft.orientations,
                onChange = { draft = draft.copy(orientations = it) },
            )

            // Plan G2-4: Who, then the viewer's own Gender and Orientation, then Partner (which narrows
            // "Linked" by the partner's own profile). Swipe's sheet has the same order.
            SectionTitle("Partner")
            PartnerSection(filters = draft, onChange = { draft = it })

            SectionTitle("Trust")
            TrustSection(
                verified = draft.verified,
                facePhotoVerified = draft.facePhotoVerified,
                ageVerified = draft.ageVerified,
                onTogglesChanged = { v, fp, av ->
                    draft = draft.copy(verified = v, facePhotoVerified = fp, ageVerified = av)
                },
                verifiedCouples = draft.verifiedCouples,
                onVerifiedCouplesToggle = { draft = draft.copy(verifiedCouples = !draft.verifiedCouples) },
            )

            SectionTitle("Photos")
            PhotosSection(
                withPhotos = draft.withPhotos,
                hasPrivateAlbum = draft.hasPrivateAlbum,
                onWithPhotosToggle = { draft = draft.copy(withPhotos = !draft.withPhotos) },
                onHasPrivateAlbumToggle = { draft = draft.copy(hasPrivateAlbum = !draft.hasPrivateAlbum) },
            )

            SectionTitle("Intent")
            IntentSection(
                selected = draft.discoveryStyles,
                onChange = { draft = draft.copy(discoveryStyles = it) },
            )
            IntentExtendedSection(
                selected = draft.intents,
                onChange = { draft = draft.copy(intents = it) },
            )

            if (lifestyleFiltersAllowed) {
                LifestyleSectionHeader(onOpenGlossary = { glossaryVisible = true })
                LifestyleMatchModeRow(
                    matchAll = draft.lifestyleMatchAll,
                    onChange = { on -> draft = draft.withLifestyleMatchAll(on) },
                )
                LifestyleSection(
                    selected = draft.lifestyleTags,
                    onChange = { chosen ->
                        // One chip per tap: route it through the mode's rules (All: up to 3).
                        val tapped = (chosen - draft.lifestyleTags) + (draft.lifestyleTags - chosen)
                        tapped.firstOrNull()?.let { tag -> draft = draft.withLifestyleTagToggled(tag) }
                    },
                )
            } else {
                SectionTitle("Lifestyle")
                ComingSoonSection(
                    message = "Verify your age in profile settings to access lifestyle filters.",
                )
            }

            SectionTitle("Boundaries")
            BoundariesFilterSection(
                selected = draft.boundaryItems,
                onChange = { draft = draft.copy(boundaryItems = it) },
            )

            SectionTitle("Logistics")
            LogisticsSection(
                selected = draft.logisticsTags,
                onChange = { draft = draft.copy(logisticsTags = it) },
            )

            if (!savedState.entitlementRequired) {
                SectionTitle("Save current filters")
                SavePresetRow(
                    name = saveName,
                    onNameChange = { saveName = it },
                    isSaving = savedState.isSaving,
                    onSave = {
                        if (saveName.isNotBlank()) {
                            onSavePreset(saveName, draft.withLifestyleAccess(lifestyleFiltersAllowed).withPartnerFiltersReconciled())
                            saveName = ""
                        }
                    },
                )
            }

            PynapliSheetActionRow(
                start = {
                    TextButton(
                        onClick = onClearAll,
                        modifier = Modifier.testTag(GridFilterTags.CLEAR_ALL).handCursor(),
                    ) { Text(text = "Clear all") }
                },
            ) {
                OutlinedButton(onClick = onDismiss, modifier = Modifier.handCursor()) { Text(text = "Cancel") }
                Button(
                    onClick = { onApply(draft.withLifestyleAccess(lifestyleFiltersAllowed).withPartnerFiltersReconciled()) },
                    modifier = Modifier.testTag(GridFilterTags.APPLY).handCursor(),
                ) { Text(text = gridApplyLabel(count)) }
            }
        }
    }

    if (glossaryVisible) {
        LifestyleGlossaryDialog(onDismiss = { glossaryVisible = false })
    }
}

private fun GridFilters.withLifestyleAccess(allowed: Boolean): GridFilters = if (allowed) this else copy(lifestyleTags = emptySet())

private const val COUNT_DEBOUNCE_MILLIS = 400L

/** The apply button: "Show 12 people", "Show 500+ people", "Show 1 person", or "Apply" before the count lands. */
internal fun gridApplyLabel(count: GridCountResponseDto?): String {
    if (count == null) return "Apply"
    val number = if (count.atLeast) "${count.count}+" else count.count.toString()
    return if (count.count == 1 && !count.atLeast) "Show 1 person" else "Show $number people"
}
