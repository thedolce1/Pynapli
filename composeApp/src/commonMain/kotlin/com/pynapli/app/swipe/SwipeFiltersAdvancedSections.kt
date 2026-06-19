package com.pynapli.app.swipe

import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.pynapli.app.design.PynapliSheetActionRow

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
    SectionTitle("Photos")
    PhotosSection(
        withPhotos = draft.withPhotos,
        onToggle = { onDraftChange(draft.copy(withPhotos = !draft.withPhotos)) },
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
    PynapliSheetActionRow(
        start = {
            TextButton(
                onClick = onClearAll,
                modifier = Modifier.testTag(SwipeFilterTags.CLEAR_ALL),
            ) { Text(text = "Clear all") }
        },
    ) {
        OutlinedButton(onClick = onDismiss) { Text(text = "Cancel") }
        Button(
            onClick = { onApply(draft) },
            modifier = Modifier.testTag(SwipeFilterTags.APPLY),
        ) { Text(text = "Apply") }
    }
}
