package com.pynapli.app.grid

import androidx.compose.runtime.Immutable
import com.pynapli.shared.network.dto.GridCardDto
import com.pynapli.shared.network.dto.LinkedPartnerMiniDto

@Immutable
data class GridUiState(
    val locationLabel: String = "Current location",
    val filters: List<GridFilterUi> = defaultGridFilters,
    val cards: List<GridProfileCardUi> = emptyList(),
    val isRefreshing: Boolean = false,
    val adSupported: Boolean = false,
    val paywallMessage: String? = null,
    val errorCode: String? = null,
    val errorMessage: String? = null
)

@Immutable
data class GridFilterUi(
    val type: GridFilterType,
    val label: String,
    val active: Boolean,
    val locked: Boolean = false
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
    val linkedPartner: GridLinkedPartnerUi? = null,
    val linkedPartnerTone: LinkedPartnerTone? = null,
    val distanceMeters: Int? = null
)

@Immutable
data class GridLinkedPartnerUi(
    val userId: String,
    val displayName: String? = null,
    val thumbnailUrl: String? = null
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
    GridFilterUi(GridFilterType.Nearby, "Nearby", active = true),
    GridFilterUi(GridFilterType.Online, "Online", active = false),
    GridFilterUi(GridFilterType.Linked, "Linked", active = false),
    GridFilterUi(GridFilterType.Age, "Age", active = false),
    GridFilterUi(GridFilterType.Tonight, "Tonight", active = false, locked = true)
)

internal fun List<GridFilterUi>.withActiveFilter(type: GridFilterType): List<GridFilterUi> =
    map { filter -> filter.copy(active = filter.type == type) }

internal fun List<GridFilterUi>.withPaidLocks(adSupported: Boolean): List<GridFilterUi> =
    map { filter ->
        filter.copy(
            locked = when (filter.type) {
                GridFilterType.Nearby -> false
                GridFilterType.Tonight -> true
                GridFilterType.Online,
                GridFilterType.Linked,
                GridFilterType.Age -> adSupported
            }
        )
    }

internal fun GridCardDto.toGridProfileCardUi(index: Int): GridProfileCardUi = GridProfileCardUi(
    id = userId,
    displayName = displayName,
    thumbnailUrl = thumbnailUrl,
    online = online,
    distanceLabel = distanceLabel,
    tone = GridCardTone.entries[index % GridCardTone.entries.size],
    linkedPartner = linkedPartner?.toUi(),
    linkedPartnerTone = linkedPartner?.let { LinkedPartnerTone.entries[index % LinkedPartnerTone.entries.size] },
    distanceMeters = distanceMeters
)

internal fun LinkedPartnerMiniDto.toUi(): GridLinkedPartnerUi = GridLinkedPartnerUi(
    userId = userId,
    displayName = displayName,
    thumbnailUrl = thumbnailUrl
)
