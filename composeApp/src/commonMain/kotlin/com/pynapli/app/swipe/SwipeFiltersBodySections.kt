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
import com.pynapli.shared.network.dto.SavedSwipeFilterDto
import com.pynapli.shared.network.dto.SwipeDealbreakerKey

@Composable
internal fun SwipeSavedFiltersControls(
    savedState: SavedSwipeFiltersUiState,
    onApplySavedPreset: (SavedSwipeFilterDto) -> Unit,
    onDeleteSavedPreset: (String) -> Unit
) {
    if (savedState.items.isNotEmpty() || savedState.entitlementRequired) {
        SectionTitle("Saved filters")
        SavedSwipePresetsSection(
            state = savedState,
            onApply = { dto -> onApplySavedPreset(dto) },
            onDelete = onDeleteSavedPreset
        )
    }
}

@Composable
internal fun SwipeWhoAgeDistanceFilters(
    draft: SwipeFilters,
    onDraftChange: (SwipeFilters) -> Unit
) {
    SectionHeader(
        title = "Who",
        dealbreakerKey = SwipeDealbreakerKey.LINKED_ONLY,
        eligible = draft.linked,
        locked = SwipeDealbreakerKey.LINKED_ONLY in draft.dealbreakers,
        onLockChange = { locked ->
            onDraftChange(
                draft.copy(
                    dealbreakers = toggleDealbreaker(
                        draft.dealbreakers,
                        SwipeDealbreakerKey.LINKED_ONLY,
                        locked
                    )
                )
            )
        }
    )
    WhoSection(
        singles = draft.singles,
        linked = draft.linked,
        partnered = draft.partnered,
        onTogglesChanged = { singles, linked, partnered ->
            val dealbreakers = if (!linked) {
                draft.dealbreakers - SwipeDealbreakerKey.LINKED_ONLY
            } else {
                draft.dealbreakers
            }
            onDraftChange(
                draft.copy(
                    singles = singles,
                    linked = linked,
                    partnered = partnered,
                    dealbreakers = dealbreakers
                )
            )
        }
    )

    SectionHeader(
        title = "Age",
        dealbreakerKey = SwipeDealbreakerKey.AGE,
        eligible = draft.minimumAge != null || draft.maximumAge != null,
        locked = SwipeDealbreakerKey.AGE in draft.dealbreakers,
        onLockChange = { locked ->
            onDraftChange(
                draft.copy(
                    dealbreakers = toggleDealbreaker(
                        draft.dealbreakers,
                        SwipeDealbreakerKey.AGE,
                        locked
                    )
                )
            )
        }
    )
    AgeSection(
        minimumAge = draft.minimumAge,
        maximumAge = draft.maximumAge,
        onChange = { lo, hi ->
            val unbounded = lo == null && hi == null
            val dealbreakers = if (unbounded) {
                draft.dealbreakers - SwipeDealbreakerKey.AGE
            } else {
                draft.dealbreakers
            }
            onDraftChange(draft.copy(minimumAge = lo, maximumAge = hi, dealbreakers = dealbreakers))
        }
    )

    SectionHeader(
        title = "Distance",
        dealbreakerKey = SwipeDealbreakerKey.DISTANCE,
        eligible = true,
        locked = SwipeDealbreakerKey.DISTANCE in draft.dealbreakers,
        onLockChange = { locked ->
            onDraftChange(
                draft.copy(
                    dealbreakers = toggleDealbreaker(
                        draft.dealbreakers,
                        SwipeDealbreakerKey.DISTANCE,
                        locked
                    )
                )
            )
        }
    )
    DistanceSection(
        radiusMeters = draft.radiusMeters,
        onChange = { meters -> onDraftChange(draft.copy(radiusMeters = meters)) }
    )
}

@Composable
internal fun SwipeIntentAndVerifiedFilters(
    draft: SwipeFilters,
    onDraftChange: (SwipeFilters) -> Unit
) {
    SectionTitle("Intent")
    SubsectionHeader(
        title = "Style",
        dealbreakerKey = SwipeDealbreakerKey.DISCOVERY_STYLES,
        eligible = draft.discoveryStyles.isNotEmpty(),
        locked = SwipeDealbreakerKey.DISCOVERY_STYLES in draft.dealbreakers,
        onLockChange = { locked ->
            onDraftChange(
                draft.copy(
                    dealbreakers = toggleDealbreaker(
                        draft.dealbreakers,
                        SwipeDealbreakerKey.DISCOVERY_STYLES,
                        locked
                    )
                )
            )
        }
    )
    IntentStylesSection(
        selected = draft.discoveryStyles,
        onChange = { styles ->
            val dealbreakers = if (styles.isEmpty()) {
                draft.dealbreakers - SwipeDealbreakerKey.DISCOVERY_STYLES
            } else {
                draft.dealbreakers
            }
            onDraftChange(draft.copy(discoveryStyles = styles, dealbreakers = dealbreakers))
        }
    )
    SubsectionHeader(
        title = "Looking for",
        dealbreakerKey = SwipeDealbreakerKey.INTENTS,
        eligible = draft.intents.isNotEmpty(),
        locked = SwipeDealbreakerKey.INTENTS in draft.dealbreakers,
        onLockChange = { locked ->
            onDraftChange(
                draft.copy(
                    dealbreakers = toggleDealbreaker(
                        draft.dealbreakers,
                        SwipeDealbreakerKey.INTENTS,
                        locked
                    )
                )
            )
        }
    )
    IntentExtendedSection(
        selected = draft.intents,
        onChange = { intents ->
            val dealbreakers = if (intents.isEmpty()) {
                draft.dealbreakers - SwipeDealbreakerKey.INTENTS
            } else {
                draft.dealbreakers
            }
            onDraftChange(draft.copy(intents = intents, dealbreakers = dealbreakers))
        }
    )

    SectionHeader(
        title = "Verified",
        dealbreakerKey = SwipeDealbreakerKey.VERIFIED,
        eligible = draft.verifiedOnly,
        locked = SwipeDealbreakerKey.VERIFIED in draft.dealbreakers,
        onLockChange = { locked ->
            onDraftChange(
                draft.copy(
                    dealbreakers = toggleDealbreaker(
                        draft.dealbreakers,
                        SwipeDealbreakerKey.VERIFIED,
                        locked
                    )
                )
            )
        }
    )
    VerifiedModeSection(
        verifiedOnly = draft.verifiedOnly,
        verifiedPreferred = draft.verifiedPreferred,
        onChange = { only, preferred ->
            val dealbreakers = if (!only) {
                draft.dealbreakers - SwipeDealbreakerKey.VERIFIED
            } else {
                draft.dealbreakers
            }
            onDraftChange(
                draft.copy(
                    verifiedOnly = only,
                    verifiedPreferred = preferred,
                    dealbreakers = dealbreakers
                )
            )
        }
    )
}

@Composable
internal fun SwipeAdvancedFilterControls(
    draft: SwipeFilters,
    onDraftChange: (SwipeFilters) -> Unit,
    onSubscribe: () -> Unit
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
        }
    )
    LifestyleTagsSection(
        selected = draft.lifestyleTags,
        onChange = { onDraftChange(draft.copy(lifestyleTags = it)) }
    )
    LogisticsTagsSection(
        selected = draft.logisticsTags,
        onChange = { onDraftChange(draft.copy(logisticsTags = it)) }
    )
}

@Composable
internal fun SwipeSaveCurrentFilters(
    savedState: SavedSwipeFiltersUiState,
    saveName: String,
    draft: SwipeFilters,
    onNameChange: (String) -> Unit,
    onSavePreset: (String, SwipeFilters) -> Unit,
    onSaved: () -> Unit
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
            }
        )
    }
}

@Composable
internal fun SwipeFiltersFooter(
    draft: SwipeFilters,
    onDismiss: () -> Unit,
    onApply: (SwipeFilters) -> Unit,
    onClearAll: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TextButton(
            onClick = onClearAll,
            modifier = Modifier.testTag(SwipeFilterTags.CLEAR_ALL)
        ) { Text(text = "Clear all") }
        Box(modifier = Modifier.weight(1f))
        OutlinedButton(onClick = onDismiss) { Text(text = "Cancel") }
        Button(
            onClick = { onApply(draft) },
            modifier = Modifier.testTag(SwipeFilterTags.APPLY)
        ) { Text(text = "Apply") }
    }
}

internal fun toggleDealbreaker(current: Set<String>, key: String, locked: Boolean): Set<String> =
    if (locked) current + key else current - key
