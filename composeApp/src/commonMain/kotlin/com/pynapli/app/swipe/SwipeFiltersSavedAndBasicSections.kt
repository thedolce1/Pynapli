package com.pynapli.app.swipe

import androidx.compose.runtime.Composable
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

internal fun toggleDealbreaker(current: Set<String>, key: String, locked: Boolean): Set<String> =
    if (locked) current + key else current - key
