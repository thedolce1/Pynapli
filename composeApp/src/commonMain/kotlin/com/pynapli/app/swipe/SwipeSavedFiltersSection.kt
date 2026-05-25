package com.pynapli.app.swipe

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.pynapli.shared.network.dto.SavedSwipeFilterDto

/**
 * Saved-preset chip row (FILTERS_SPEC L463-477). One AssistChip per saved preset;
 * tap to apply (the host closes the sheet and routes the payload through
 * `stateHolder.applyFilters`). Free-tier viewers see `ComingSoonSection` copy
 * instead — the entitlement gate is server-authoritative but the client respects
 * it for UX so a free-tier viewer never sees an empty "no presets" state.
 */
@Composable
internal fun SavedSwipePresetsSection(
    state: SavedSwipeFiltersUiState,
    onApply: (SavedSwipeFilterDto) -> Unit,
    @Suppress("UNUSED_PARAMETER") onDelete: (id: String) -> Unit
) {
    if (state.entitlementRequired) {
        ComingSoonSection(message = "Saved swipe filter presets unlock with Social or Select.")
        return
    }
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(SwipeFilterTags.SAVED_PRESETS),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        state.items.forEach { dto ->
            SwipeSheetChip(label = dto.name, active = false) { onApply(dto) }
        }
    }
}

@Composable
internal fun SaveSwipePresetRow(
    name: String,
    onNameChange: (String) -> Unit,
    isSaving: Boolean,
    onSave: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedTextField(
            value = name,
            onValueChange = onNameChange,
            modifier = Modifier
                .weight(1f)
                .testTag(SwipeFilterTags.SAVE_PRESET_NAME),
            singleLine = true,
            placeholder = { Text(text = "Name this filter") }
        )
        Button(
            onClick = onSave,
            enabled = !isSaving && name.isNotBlank(),
            modifier = Modifier.testTag(SwipeFilterTags.SAVE_PRESET_SUBMIT)
        ) { Text(text = if (isSaving) "Saving" else "Save") }
    }
}
