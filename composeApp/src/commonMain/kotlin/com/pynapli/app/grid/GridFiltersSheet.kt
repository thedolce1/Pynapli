package com.pynapli.app.grid

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GridFiltersSheet(
    initial: GridFilters,
    savedState: SavedGridFiltersUiState = SavedGridFiltersUiState(),
    onDismiss: () -> Unit,
    onApply: (GridFilters) -> Unit,
    onClearAll: () -> Unit,
    onSavePreset: (name: String, GridFilters) -> Unit = { _, _ -> },
    onApplySavedPreset: (com.pynapli.shared.network.dto.SavedGridFilterDto) -> Unit = {},
    onDeleteSavedPreset: (id: String) -> Unit = {}
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var draft by remember(initial) { mutableStateOf(initial) }
    var saveName by remember { mutableStateOf("") }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = Modifier.testTag(GridFilterTags.SHEET)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            if (savedState.items.isNotEmpty() || savedState.entitlementRequired) {
                SectionTitle("Saved")
                SavedFiltersSection(
                    state = savedState,
                    onApply = { dto ->
                        draft = GridFilters.fromGridQueryRequest(dto.payload)
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

            SectionTitle("Lifestyle")
            LifestyleSection(
                selected = draft.lifestyleTags,
                onChange = { draft = draft.copy(lifestyleTags = it) }
            )

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
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                TextButton(
                    onClick = onClearAll,
                    modifier = Modifier.testTag(GridFilterTags.CLEAR_ALL)
                ) { Text(text = "Clear all") }
                Box(modifier = Modifier.weight(1f))
                OutlinedButton(onClick = onDismiss) { Text(text = "Cancel") }
                androidx.compose.material3.Button(
                    onClick = { onApply(draft) },
                    modifier = Modifier.testTag(GridFilterTags.APPLY)
                ) { Text(text = "Apply") }
            }
        }
    }
}

@Composable
private fun SavedFiltersSection(
    state: SavedGridFiltersUiState,
    onApply: (com.pynapli.shared.network.dto.SavedGridFilterDto) -> Unit,
    onDelete: (id: String) -> Unit
) {
    if (state.entitlementRequired) {
        ComingSoonSection(message = "Saved filter presets unlock with Social or Select.")
        return
    }
    androidx.compose.foundation.layout.FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        state.items.forEach { dto ->
            SheetToggleChip(label = dto.name, active = false) { onApply(dto) }
        }
    }
}

@Composable
private fun SavePresetRow(
    name: String,
    onNameChange: (String) -> Unit,
    isSaving: Boolean,
    onSave: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
    ) {
        androidx.compose.material3.OutlinedTextField(
            value = name,
            onValueChange = onNameChange,
            modifier = Modifier.weight(1f),
            singleLine = true,
            placeholder = { Text(text = "Name this filter") }
        )
        androidx.compose.material3.Button(
            onClick = onSave,
            enabled = !isSaving && name.isNotBlank()
        ) { Text(text = if (isSaving) "Saving" else "Save") }
    }
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
            valueRange = GridFilters.MIN_RADIUS_METERS.toFloat()..GridFilters.MAX_RADIUS_METERS.toFloat(),
            steps = 0
        )
    }
}

@Composable
private fun AgeSection(
    minimumAge: Int?,
    maximumAge: Int?,
    onChange: (Int?, Int?) -> Unit
) {
    val lo = (minimumAge ?: GridFilters.MIN_FILTER_AGE).coerceAtLeast(GridFilters.MIN_FILTER_AGE)
    val hi = (maximumAge ?: GridFilters.MAX_FILTER_AGE).coerceAtMost(GridFilters.MAX_FILTER_AGE)
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
                if (newLo == GridFilters.MIN_FILTER_AGE && newHi == GridFilters.MAX_FILTER_AGE) {
                    onChange(null, null)
                } else {
                    onChange(newLo, newHi)
                }
            },
            valueRange = GridFilters.MIN_FILTER_AGE.toFloat()..GridFilters.MAX_FILTER_AGE.toFloat()
        )
    }
}

@Composable
private fun PresenceSection(
    online: Boolean,
    tonightAvailable: Boolean,
    onOnlineToggle: () -> Unit,
    onTonightToggle: () -> Unit
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SheetToggleChip(label = "Online now", active = online, onClick = onOnlineToggle)
        SheetToggleChip(label = "Available tonight", active = tonightAvailable, onClick = onTonightToggle)
    }
}

@Composable
private fun LogisticsSection(
    selected: Set<LogisticsTag>,
    onChange: (Set<LogisticsTag>) -> Unit
) {
    androidx.compose.foundation.layout.FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
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
private fun LifestyleSection(
    selected: Set<LifestyleTag>,
    onChange: (Set<LifestyleTag>) -> Unit
) {
    androidx.compose.foundation.layout.FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
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
private fun IntentSection(
    selected: Set<DiscoveryStyle>,
    onChange: (Set<DiscoveryStyle>) -> Unit
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
private fun TrustSection(
    verified: Boolean,
    facePhotoVerified: Boolean,
    ageVerified: Boolean,
    onTogglesChanged: (Boolean, Boolean, Boolean) -> Unit
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
private fun WhoSection(
    singles: Boolean,
    linked: Boolean,
    partnered: Boolean,
    onTogglesChanged: (Boolean, Boolean, Boolean) -> Unit
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
private fun SheetToggleChip(label: String, active: Boolean, onClick: () -> Unit) {
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

@Composable
private fun ComingSoonSection(message: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            imageVector = Icons.Filled.Lock,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp)
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
