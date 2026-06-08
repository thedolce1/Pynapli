package com.pynapli.app.swipe

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.pynapli.shared.network.dto.SavedSwipeFilterDto

/**
 * Swipe filter sheet (FILTERS_SPEC L263-415).
 *
 * Spec L401: "client should request a new deck session for changes" - Apply
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
    onOpenAgeVerification: () -> Unit = {},
    savedState: SavedSwipeFiltersUiState = SavedSwipeFiltersUiState(),
    onSavePreset: (name: String, SwipeFilters) -> Unit = { _, _ -> },
    onApplySavedPreset: (SavedSwipeFilterDto) -> Unit = {},
    onDeleteSavedPreset: (id: String) -> Unit = {},
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = Modifier.testTag(SwipeFilterTags.SHEET),
    ) {
        SwipeFiltersSheetBody(
            initial = initial,
            onDismiss = onDismiss,
            onApply = onApply,
            onClearAll = onClearAll,
            onOpenStore = onOpenStore,
            onOpenAgeVerification = onOpenAgeVerification,
            savedState = savedState,
            onSavePreset = onSavePreset,
            onApplySavedPreset = onApplySavedPreset,
            onDeleteSavedPreset = onDeleteSavedPreset,
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
    onOpenAgeVerification: () -> Unit = {},
    savedState: SavedSwipeFiltersUiState = SavedSwipeFiltersUiState(),
    onSavePreset: (name: String, SwipeFilters) -> Unit = { _, _ -> },
    onApplySavedPreset: (SavedSwipeFilterDto) -> Unit = {},
    onDeleteSavedPreset: (id: String) -> Unit = {},
) {
    var draft by remember(initial) { mutableStateOf(initial) }
    var saveName by remember { mutableStateOf("") }
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(top = 12.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        SectionIntro(
            "These shape who shows up in your deck. Most filters are flexible; lock the " +
                "ones that must apply. Locked filters may shrink your deck.",
        )

        SwipeSavedFiltersControls(
            savedState = savedState,
            onApplySavedPreset = onApplySavedPreset,
            onDeleteSavedPreset = onDeleteSavedPreset,
            onOpenAgeVerification = {
                onDismiss()
                onOpenAgeVerification()
            },
        )
        SwipeWhoAgeDistanceFilters(
            draft = draft,
            onDraftChange = { draft = it },
        )
        SwipeIntentAndVerifiedFilters(
            draft = draft,
            onDraftChange = { draft = it },
        )
        SwipeAdvancedFilterControls(
            draft = draft,
            onDraftChange = { draft = it },
            onSubscribe = {
                onDismiss()
                onOpenStore()
            },
        )
        SwipeSaveCurrentFilters(
            savedState = savedState,
            saveName = saveName,
            draft = draft,
            onNameChange = { saveName = it },
            onSavePreset = onSavePreset,
            onSaved = { saveName = "" },
        )
        SwipeFiltersFooter(
            draft = draft,
            onDismiss = onDismiss,
            onApply = onApply,
            onClearAll = onClearAll,
        )
    }
}
