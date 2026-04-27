package com.pynapli.app.grid

import androidx.compose.runtime.Immutable
import com.pynapli.shared.network.dto.GridCardDto

@Immutable
data class GridUiState(
    val locationLabel: String = "Current location",
    val filters: List<GridFilterUi> = defaultGridFilters,
    val cards: List<GridProfileCardUi> = emptyList(),
    val isRefreshing: Boolean = false,
    val errorCode: String? = null,
    val errorMessage: String? = null
)

@Immutable
data class GridFilterUi(
    val type: GridFilterType,
    val label: String,
    val active: Boolean
)

enum class GridFilterType {
    Online,
    Nearby,
    Linked,
    Tonight,
    Age
}

@Immutable
data class GridProfileCardUi(
    val id: String,
    val displayName: String,
    val thumbnailUrl: String? = null,
    val online: Boolean,
    val distanceLabel: String?,
    val tone: GridCardTone,
    val linkedPartnerTone: LinkedPartnerTone? = null
)

enum class GridCardTone {
    Warm,
    Slate,
    Rust,
    DeepGreen,
    Bronze,
    Graphite,
    Teal,
    Rose,
    Cocoa,
    Moss,
    Stone,
    Midnight
}

enum class LinkedPartnerTone {
    Violet,
    Gold,
    Pink,
    Teal,
    Sun
}

internal val defaultGridFilters = listOf(
    GridFilterUi(GridFilterType.Online, "Online", active = true),
    GridFilterUi(GridFilterType.Nearby, "Nearby", active = false),
    GridFilterUi(GridFilterType.Linked, "Linked", active = false),
    GridFilterUi(GridFilterType.Tonight, "Tonight", active = false),
    GridFilterUi(GridFilterType.Age, "Age", active = false)
)

internal fun List<GridFilterUi>.withActiveFilter(type: GridFilterType): List<GridFilterUi> =
    map { filter -> filter.copy(active = filter.type == type) }

internal fun GridCardDto.toGridProfileCardUi(index: Int): GridProfileCardUi = GridProfileCardUi(
    id = userId,
    displayName = displayName,
    thumbnailUrl = thumbnailUrl,
    online = online,
    distanceLabel = distanceLabel,
    tone = GridCardTone.entries[index % GridCardTone.entries.size],
    linkedPartnerTone = linkedPartner?.let { LinkedPartnerTone.entries[index % LinkedPartnerTone.entries.size] }
)
