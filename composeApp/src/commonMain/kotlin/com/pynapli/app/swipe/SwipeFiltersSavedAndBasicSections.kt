package com.pynapli.app.swipe

import androidx.compose.runtime.Composable
import com.pynapli.app.grid.GridFilters
import com.pynapli.app.grid.PartnerSection
import com.pynapli.app.grid.SinglesShortcutRow
import com.pynapli.shared.network.dto.SavedSwipeFilterDto
import com.pynapli.shared.network.dto.SwipeDealbreakerKey

@Composable
internal fun SwipeSavedFiltersControls(
    savedState: SavedSwipeFiltersUiState,
    onApplySavedPreset: (SavedSwipeFilterDto) -> Unit,
    onDeleteSavedPreset: (String) -> Unit,
    onOpenAgeVerification: () -> Unit = {},
) {
    if (savedState.items.isNotEmpty() ||
        savedState.entitlementRequired ||
        savedState.ageVerificationRequired
    ) {
        SectionTitle("Saved filters")
        SavedSwipePresetsSection(
            state = savedState,
            onApply = { dto -> onApplySavedPreset(dto) },
            onDelete = onDeleteSavedPreset,
            onOpenAgeVerification = onOpenAgeVerification,
        )
    }
}

@Composable
internal fun SwipeWhoAgeDistanceFilters(
    draft: SwipeFilters,
    onDraftChange: (SwipeFilters) -> Unit,
) {
    // Age and Distance first, like the top of the Grid sheet: the filters people change most (plan G2-4).
    SectionHeader(
        title = "Age",
        dealbreakerKey = SwipeDealbreakerKey.AGE,
        eligible = draft.minimumAge != null || draft.maximumAge != null,
        locked = SwipeDealbreakerKey.AGE in draft.dealbreakers,
        onLockChange = { locked ->
            onDraftChange(
                draft.copy(
                    dealbreakers =
                        toggleDealbreaker(
                            draft.dealbreakers,
                            SwipeDealbreakerKey.AGE,
                            locked,
                        ),
                ),
            )
        },
    )
    AgeSection(
        minimumAge = draft.minimumAge,
        maximumAge = draft.maximumAge,
        onChange = { lo, hi ->
            val unbounded = lo == null && hi == null
            val dealbreakers =
                if (unbounded) {
                    draft.dealbreakers - SwipeDealbreakerKey.AGE
                } else {
                    draft.dealbreakers
                }
            onDraftChange(draft.copy(minimumAge = lo, maximumAge = hi, dealbreakers = dealbreakers))
        },
    )

    SectionHeader(
        title = "Distance",
        dealbreakerKey = SwipeDealbreakerKey.DISTANCE,
        eligible = true,
        locked = SwipeDealbreakerKey.DISTANCE in draft.dealbreakers,
        onLockChange = { locked ->
            onDraftChange(
                draft.copy(
                    dealbreakers =
                        toggleDealbreaker(
                            draft.dealbreakers,
                            SwipeDealbreakerKey.DISTANCE,
                            locked,
                        ),
                ),
            )
        },
    )
    DistanceSection(
        radiusMeters = draft.radiusMeters,
        onChange = { meters -> onDraftChange(draft.copy(radiusMeters = meters)) },
    )

    SectionHeader(
        title = "Who",
        dealbreakerKey = SwipeDealbreakerKey.LINKED_ONLY,
        eligible = draft.linked,
        locked = SwipeDealbreakerKey.LINKED_ONLY in draft.dealbreakers,
        onLockChange = { locked ->
            onDraftChange(
                draft.copy(
                    dealbreakers =
                        toggleDealbreaker(
                            draft.dealbreakers,
                            SwipeDealbreakerKey.LINKED_ONLY,
                            locked,
                        ),
                ),
            )
        },
    )
    WhoSection(
        singles = draft.singles,
        linked = draft.linked,
        partnered = draft.partnered,
        onTogglesChanged = { singles, linked, partnered ->
            val dealbreakers =
                if (!linked) {
                    draft.dealbreakers - SwipeDealbreakerKey.LINKED_ONLY
                } else {
                    draft.dealbreakers
                }
            onDraftChange(
                draft.copy(
                    singles = singles,
                    linked = linked,
                    partnered = partnered,
                    dealbreakers = dealbreakers,
                ),
            )
        },
    )
    // Plan G2-4: the Grid's one-tap shortcuts (Single women, Single men, No single men).
    SinglesShortcutRow(
        filters = draft.asGridWhoAndPartner(),
        onChange = { onDraftChange(draft.withGridWhoAndPartner(it)) },
    )

    SectionTitle("Gender")
    GenderSection(
        selected = draft.genders,
        onChange = { onDraftChange(draft.copy(genders = it)) },
    )

    SectionTitle("Orientation")
    OrientationSection(
        selected = draft.orientations,
        onChange = { onDraftChange(draft.copy(orientations = it)) },
    )

    // Plan G2-4: after the viewer's own Gender and Orientation (Who, Gender, Orientation, Partner), as on
    // the Grid. The same section and rules as the Grid's (FILTERS_SPEC "Partner").
    SectionTitle("Partner")
    PartnerSection(
        filters = draft.asGridWhoAndPartner(),
        onChange = { onDraftChange(draft.withGridWhoAndPartner(it)) },
    )
}

internal fun toggleDealbreaker(
    current: Set<String>,
    key: String,
    locked: Boolean,
): Set<String> = if (locked) current + key else current - key

/**
 * The Swipe draft as the Grid's Who + Partner model, so Swipe reuses the Grid's Partner section and
 * shortcuts as they are. Swipe's Linked or Partnered both read as Grid "Linked" (the server folds both
 * onto the visible link).
 */
internal fun SwipeFilters.asGridWhoAndPartner(): GridFilters =
    GridFilters(
        singles = singles,
        linked = linked || partnered,
        genders = genders,
        partnerGenders = partnerGenders,
        partnerMinimumAge = partnerMinimumAge,
        partnerMaximumAge = partnerMaximumAge,
        partnerOrientations = partnerOrientations,
        partnerVerified = partnerVerified,
        noSingleMen = noSingleMen,
    )

/** Writes the Grid model's Who + Partner choices back onto the Swipe draft. */
internal fun SwipeFilters.withGridWhoAndPartner(grid: GridFilters): SwipeFilters {
    val wasLinked = linked || partnered
    val keepLinked = grid.linked == wasLinked
    val newLinked = if (keepLinked) linked else grid.linked
    val newPartnered = if (keepLinked) partnered else false
    return copy(
        singles = grid.singles,
        linked = newLinked,
        partnered = newPartnered,
        genders = grid.genders,
        partnerGenders = grid.partnerGenders,
        partnerMinimumAge = grid.partnerMinimumAge,
        partnerMaximumAge = grid.partnerMaximumAge,
        partnerOrientations = grid.partnerOrientations,
        partnerVerified = grid.partnerVerified,
        noSingleMen = grid.noSingleMen,
        // A Linked dealbreaker cannot outlive Linked.
        dealbreakers = if (newLinked) dealbreakers else dealbreakers - SwipeDealbreakerKey.LINKED_ONLY,
    ).withPartnerFiltersReconciled()
}
