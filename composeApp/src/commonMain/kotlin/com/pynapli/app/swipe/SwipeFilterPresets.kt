package com.pynapli.app.swipe

import androidx.compose.runtime.Immutable

@Immutable
data class SwipeFilterUi(
    val type: SwipeFilterType,
    val label: String,
    val active: Boolean,
    val locked: Boolean = false,
)

enum class SwipeFilterType {
    ForYou,
    TopPicks,
    Linked,
    Age,
}

internal val defaultSwipeFilters =
    listOf(
        SwipeFilterUi(SwipeFilterType.ForYou, "For You", active = true),
        SwipeFilterUi(SwipeFilterType.TopPicks, "Top Picks", active = false),
        SwipeFilterUi(SwipeFilterType.Linked, "Linked", active = false),
        SwipeFilterUi(SwipeFilterType.Age, "Age", active = false),
    )

internal fun List<SwipeFilterUi>.withActiveFilter(type: SwipeFilterType): List<SwipeFilterUi> = map { filter -> filter.copy(active = filter.type == type) }

internal fun List<SwipeFilterUi>.withPaidLocks(adSupported: Boolean): List<SwipeFilterUi> =
    map { filter ->
        filter.copy(
            locked =
                when (filter.type) {
                    // Top Picks is free (all tiers get at least one daily pick); the
                    // server enforces the per-tier pick count, not the chip.
                    SwipeFilterType.ForYou,
                    SwipeFilterType.TopPicks,
                    -> false
                    SwipeFilterType.Linked,
                    SwipeFilterType.Age,
                    -> adSupported
                },
        )
    }

internal fun SwipeFilterType.toPresetFilters(): SwipeFilters =
    when (this) {
        // Top Picks is a deck SOURCE switch, not a filter set; the server curates the
        // picks, so the request filters are reset to defaults (unused in picks mode).
        SwipeFilterType.ForYou,
        SwipeFilterType.TopPicks,
        -> SwipeFilters.forYou
        SwipeFilterType.Linked -> SwipeFilters.linkedOnly
        SwipeFilterType.Age -> SwipeFilters.ageRange
    }
