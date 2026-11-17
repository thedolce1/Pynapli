package com.pynapli.app.grid

import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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

@Composable
internal fun GridFiltersSheet(
    initial: GridFilters,
    savedState: SavedGridFiltersUiState = SavedGridFiltersUiState(),
    tonightSessionActive: Boolean = false,
    lifestyleFiltersAllowed: Boolean = true,
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
                recentlyJoined = draft.recentlyJoined,
                travelingHere = draft.travelingHere,
                visitingSoon = draft.visitingSoon,
                onVisitingSoonToggle = { draft = draft.copy(visitingSoon = !draft.visitingSoon) },
                onOnlineToggle = { draft = draft.copy(online = !draft.online) },
                onTonightToggle = { draft = draft.copy(tonightAvailable = !draft.tonightAvailable) },
                onActiveTodayToggle = { draft = draft.copy(activeToday = !draft.activeToday) },
                onRecentlyJoinedToggle = { draft = draft.copy(recentlyJoined = !draft.recentlyJoined) },
                onTravelingHereToggle = { draft = draft.copy(travelingHere = !draft.travelingHere) },
            )
            TonightSessionEntryRow(
                sessionActive = tonightSessionActive,
                onOpenTonight = onOpenTonight,
            )

            SectionTitle("Who")
            WhoSection(
                singles = draft.singles,
                linked = draft.linked,
                partnered = draft.partnered,
                onTogglesChanged = { singles, linked, partnered ->
                    draft = draft.copy(singles = singles, linked = linked, partnered = partnered)
                },
            )

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

            SectionTitle("Trust")
            TrustSection(
                verified = draft.verified,
                facePhotoVerified = draft.facePhotoVerified,
                ageVerified = draft.ageVerified,
                onTogglesChanged = { v, fp, av ->
                    draft = draft.copy(verified = v, facePhotoVerified = fp, ageVerified = av)
                },
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
                LifestyleSection(
                    selected = draft.lifestyleTags,
                    onChange = { draft = draft.copy(lifestyleTags = it) },
                )
            } else {
                SectionTitle("Lifestyle")
                ComingSoonSection(
                    message = "Verify your age in profile settings to access lifestyle filters.",
                )
            }

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
                            onSavePreset(saveName, draft.withLifestyleAccess(lifestyleFiltersAllowed))
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
                    onClick = { onApply(draft.withLifestyleAccess(lifestyleFiltersAllowed)) },
                    modifier = Modifier.testTag(GridFilterTags.APPLY).handCursor(),
                ) { Text(text = "Apply") }
            }
        }
    }

    if (glossaryVisible) {
        LifestyleGlossaryDialog(onDismiss = { glossaryVisible = false })
    }
}

private fun GridFilters.withLifestyleAccess(allowed: Boolean): GridFilters = if (allowed) this else copy(lifestyleTags = emptySet())
