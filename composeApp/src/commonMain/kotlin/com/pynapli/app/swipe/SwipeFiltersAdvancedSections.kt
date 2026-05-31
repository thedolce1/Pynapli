package com.pynapli.app.swipe

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@Composable
internal fun SwipeAdvancedFilterControls(
    draft: SwipeFilters,
    onDraftChange: (SwipeFilters) -> Unit,
    onSubscribe: () -> Unit,
) {
    SectionTitle("Advanced")
    if (draft.requiresAdvancedEntitlement) {
        AdvancedPaywallBanner(onSubscribe = onSubscribe)
    }
    FacePhotoAndAgeVerifiedSection(
        facePhotoVerified = draft.facePhotoVerified,
        ageVerified = draft.ageVerified,
        onChange = { face, ageV ->
            onDraftChange(draft.copy(facePhotoVerified = face, ageVerified = ageV))
        },
    )
    LifestyleTagsSection(
        selected = draft.lifestyleTags,
        onChange = { onDraftChange(draft.copy(lifestyleTags = it)) },
    )
    LogisticsTagsSection(
        selected = draft.logisticsTags,
        onChange = { onDraftChange(draft.copy(logisticsTags = it)) },
    )
}

@Composable
internal fun SwipeSaveCurrentFilters(
    savedState: SavedSwipeFiltersUiState,
    saveName: String,
    draft: SwipeFilters,
    onNameChange: (String) -> Unit,
    onSavePreset: (String, SwipeFilters) -> Unit,
    onSaved: () -> Unit,
) {
    if (!savedState.entitlementRequired) {
        SectionTitle("Save current filters")
        SaveSwipePresetRow(
            name = saveName,
            onNameChange = onNameChange,
            isSaving = savedState.isSaving,
            onSave = {
                if (saveName.isNotBlank()) {
                    onSavePreset(saveName, draft)
                    onSaved()
                }
            },
        )
    }
}

@Composable
internal fun SwipeFiltersFooter(
    draft: SwipeFilters,
    onDismiss: () -> Unit,
    onApply: (SwipeFilters) -> Unit,
    onClearAll: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(
            onClick = onClearAll,
            modifier = Modifier.testTag(SwipeFilterTags.CLEAR_ALL),
        ) { Text(text = "Clear all") }
        Box(modifier = Modifier.weight(1f))
        OutlinedButton(onClick = onDismiss) { Text(text = "Cancel") }
        Button(
            onClick = { onApply(draft) },
            modifier = Modifier.testTag(SwipeFilterTags.APPLY),
        ) { Text(text = "Apply") }
    }
}
