package com.pynapli.app.swipe

import androidx.compose.runtime.Immutable

@Immutable
data class SwipeFilterUi(
    val type: SwipeFilterType,
    val label: String,
    val active: Boolean,
)

enum class SwipeFilterType {
    ForYou,
    Linked,
    Age,
}

internal val defaultSwipeFilters =
    listOf(
        SwipeFilterUi(SwipeFilterType.ForYou, "For You", active = true),
        SwipeFilterUi(SwipeFilterType.Linked, "Linked", active = false),
        SwipeFilterUi(SwipeFilterType.Age, "Age", active = false),
    )

internal fun List<SwipeFilterUi>.withActiveFilter(type: SwipeFilterType): List<SwipeFilterUi> = map { filter -> filter.copy(active = filter.type == type) }

internal fun SwipeFilterType.toPresetFilters(): SwipeFilters =
    when (this) {
        SwipeFilterType.ForYou -> SwipeFilters.forYou
        SwipeFilterType.Linked -> SwipeFilters.linkedOnly
        SwipeFilterType.Age -> SwipeFilters.ageRange
    }
