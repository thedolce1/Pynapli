package com.pynapli.app.grid

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
import androidx.compose.ui.unit.dp
import com.pynapli.app.design.handCursor
import com.pynapli.shared.network.dto.SavedGridFilterDto

@Composable
internal fun SavedFiltersSection(
    state: SavedGridFiltersUiState,
    onApply: (SavedGridFilterDto) -> Unit,
    onDelete: (id: String) -> Unit,
) {
    if (state.entitlementRequired) {
        ComingSoonSection(message = "Saved filter presets unlock with Social or Select.")
        return
    }
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        state.items.forEach { dto ->
            SheetToggleChip(label = dto.name, active = false) { onApply(dto) }
        }
    }
}

@Composable
internal fun SavePresetRow(
    name: String,
    onNameChange: (String) -> Unit,
    isSaving: Boolean,
    onSave: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = name,
            onValueChange = onNameChange,
            modifier = Modifier.weight(1f),
            singleLine = true,
            placeholder = { Text(text = "Name this filter") },
        )
        Button(
            onClick = onSave,
            enabled = !isSaving && name.isNotBlank(),
            modifier = Modifier.handCursor(enabled = !isSaving && name.isNotBlank()),
        ) { Text(text = if (isSaving) "Saving" else "Save") }
    }
}
