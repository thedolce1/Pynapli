package com.pynapli.app.grid

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GridFiltersSheet(
    initial: GridFilters,
    savedState: SavedGridFiltersUiState = SavedGridFiltersUiState(),
    tonightState: TonightAvailabilityUiState = TonightAvailabilityUiState(),
    lifestyleFiltersAllowed: Boolean = true,
    onDismiss: () -> Unit,
    onApply: (GridFilters) -> Unit,
    onClearAll: () -> Unit,
    onSavePreset: (name: String, GridFilters) -> Unit = { _, _ -> },
    onApplySavedPreset: (com.pynapli.shared.network.dto.SavedGridFilterDto) -> Unit = {},
    onDeleteSavedPreset: (id: String) -> Unit = {},
    onSetTonightAvailable: () -> Unit = {},
    onClearTonightAvailable: () -> Unit = {}
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val initialDraft = remember(initial, lifestyleFiltersAllowed) {
        initial.withLifestyleAccess(lifestyleFiltersAllowed)
    }
    var draft by remember(initialDraft) { mutableStateOf(initialDraft) }
    var saveName by remember { mutableStateOf("") }
    var glossaryVisible by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = Modifier.testTag(GridFilterTags.SHEET)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(top = 12.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            if (savedState.items.isNotEmpty() || savedState.entitlementRequired) {
                SectionTitle("Saved")
                SavedFiltersSection(
                    state = savedState,
                    onApply = { dto ->
                        draft = GridFilters.fromGridQueryRequest(dto.payload)
                            .withLifestyleAccess(lifestyleFiltersAllowed)
                        onApplySavedPreset(dto)
                    },
                    onDelete = onDeleteSavedPreset
                )
            }

            SectionTitle("Distance")
            DistanceSection(
                radiusMeters = draft.radiusMeters,
                onChange = { meters -> draft = draft.copy(radiusMeters = meters) }
            )

            SectionTitle("Age")
            AgeSection(
                minimumAge = draft.minimumAge,
                maximumAge = draft.maximumAge,
                onChange = { lo, hi -> draft = draft.copy(minimumAge = lo, maximumAge = hi) }
            )

            SectionTitle("Presence")
            PresenceSection(
                online = draft.online,
                tonightAvailable = draft.tonightAvailable,
                onOnlineToggle = { draft = draft.copy(online = !draft.online) },
                onTonightToggle = { draft = draft.copy(tonightAvailable = !draft.tonightAvailable) }
            )
            TonightAvailabilityRow(
                state = tonightState,
                onSet = onSetTonightAvailable,
                onClear = onClearTonightAvailable
            )

            SectionTitle("Who")
            WhoSection(
                singles = draft.singles,
                linked = draft.linked,
                partnered = draft.partnered,
                onTogglesChanged = { singles, linked, partnered ->
                    draft = draft.copy(singles = singles, linked = linked, partnered = partnered)
                }
            )

            SectionTitle("Trust")
            TrustSection(
                verified = draft.verified,
                facePhotoVerified = draft.facePhotoVerified,
                ageVerified = draft.ageVerified,
                onTogglesChanged = { v, fp, av ->
                    draft = draft.copy(verified = v, facePhotoVerified = fp, ageVerified = av)
                }
            )

            SectionTitle("Intent")
            IntentSection(
                selected = draft.discoveryStyles,
                onChange = { draft = draft.copy(discoveryStyles = it) }
            )
            IntentExtendedSection(
                selected = draft.intents,
                onChange = { draft = draft.copy(intents = it) }
            )

            if (lifestyleFiltersAllowed) {
                LifestyleSectionHeader(onOpenGlossary = { glossaryVisible = true })
                LifestyleSection(
                    selected = draft.lifestyleTags,
                    onChange = { draft = draft.copy(lifestyleTags = it) }
                )
            } else {
                SectionTitle("Lifestyle")
                ComingSoonSection(
                    message = "Verify your age in profile settings to access lifestyle filters."
                )
            }

            SectionTitle("Logistics")
            LogisticsSection(
                selected = draft.logisticsTags,
                onChange = { draft = draft.copy(logisticsTags = it) }
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
                    }
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                TextButton(
                    onClick = onClearAll,
                    modifier = Modifier.testTag(GridFilterTags.CLEAR_ALL)
                ) { Text(text = "Clear all") }
                Box(modifier = Modifier.weight(1f))
                OutlinedButton(onClick = onDismiss) { Text(text = "Cancel") }
                androidx.compose.material3.Button(
                    onClick = { onApply(draft.withLifestyleAccess(lifestyleFiltersAllowed)) },
                    modifier = Modifier.testTag(GridFilterTags.APPLY)
                ) { Text(text = "Apply") }
            }
        }
    }

    if (glossaryVisible) {
        LifestyleGlossaryDialog(onDismiss = { glossaryVisible = false })
    }
}

private fun GridFilters.withLifestyleAccess(allowed: Boolean): GridFilters =
    if (allowed) this else copy(lifestyleTags = emptySet())
