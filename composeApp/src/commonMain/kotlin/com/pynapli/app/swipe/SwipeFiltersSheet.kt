package com.pynapli.app.swipe

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.pynapli.app.design.PynapliBottomSheet
import com.pynapli.app.design.PynapliSheetColumn
import com.pynapli.shared.network.dto.SavedSwipeFilterDto

/**
 * Swipe filter sheet (FILTERS_SPEC L263-415).
 *
 * Spec L401: "client should request a new deck session for changes" - Apply
 * routes through `stateHolder.applyFilters(draft)` which triggers a fresh
 * `load()` and discards the current deck cursor.
 */
@Composable
internal fun SwipeFiltersSheet(
    initial: SwipeFilters,
    onDismiss: () -> Unit,
    onApply: (SwipeFilters) -> Unit,
    onClearAll: () -> Unit,
    // Age verified (from the viewer's profile); false hides the Lifestyle tags, as on the Grid.
    lifestyleFiltersAllowed: Boolean = true,
    onOpenAgeVerification: () -> Unit = {},
    savedState: SavedSwipeFiltersUiState = SavedSwipeFiltersUiState(),
    onSavePreset: (name: String, SwipeFilters) -> Unit = { _, _ -> },
    onApplySavedPreset: (SavedSwipeFilterDto) -> Unit = {},
    onDeleteSavedPreset: (id: String) -> Unit = {},
) {
    PynapliBottomSheet(
        onDismiss = onDismiss,
        modifier = Modifier.testTag(SwipeFilterTags.SHEET),
    ) {
        SwipeFiltersSheetBody(
            initial = initial,
            onDismiss = onDismiss,
            onApply = onApply,
            onClearAll = onClearAll,
            lifestyleFiltersAllowed = lifestyleFiltersAllowed,
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
    // Age verified (from the viewer's profile); false hides the Lifestyle tags, as on the Grid.
    lifestyleFiltersAllowed: Boolean = true,
    onOpenAgeVerification: () -> Unit = {},
    savedState: SavedSwipeFiltersUiState = SavedSwipeFiltersUiState(),
    onSavePreset: (name: String, SwipeFilters) -> Unit = { _, _ -> },
    onApplySavedPreset: (SavedSwipeFilterDto) -> Unit = {},
    onDeleteSavedPreset: (id: String) -> Unit = {},
) {
    var draft by remember(initial, lifestyleFiltersAllowed) {
        mutableStateOf(if (lifestyleFiltersAllowed) initial else initial.copy(lifestyleTags = emptySet()))
    }
    var saveName by remember { mutableStateOf("") }
    PynapliSheetColumn {
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
            lifestyleFiltersAllowed = lifestyleFiltersAllowed,
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
