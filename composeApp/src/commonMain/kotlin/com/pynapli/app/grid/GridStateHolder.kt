package com.pynapli.app.grid

import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.network.PynapliApiClient
import com.pynapli.shared.network.dto.GridQueryRequestDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class GridStateHolder(
    private val repository: GridFeedRepository
) {
    private val _state = MutableStateFlow(GridUiState(isRefreshing = true))
    val state: StateFlow<GridUiState> = _state.asStateFlow()

    suspend fun load() {
        val selectedFilter = _state.value.filters.firstOrNull { it.active }?.type ?: GridFilterType.Nearby
        _state.update { current -> current.copy(isRefreshing = true, errorCode = null, errorMessage = null) }
        runCatching { repository.loadGrid(selectedFilter) }
            .onSuccess { feed ->
                _state.update { current ->
                    current.copy(
                        cards = feed.cards,
                        adSupported = feed.adSupported,
                        filters = current.filters.withPaidLocks(feed.adSupported),
                        paywallMessage = null,
                        isRefreshing = false,
                        errorCode = null,
                        errorMessage = null
                    )
                }
            }
            .onFailure { error ->
                _state.update { current ->
                    val code = (error as? GridFeedException)?.code
                    val entitlementRequired = code == "ENTITLEMENT_REQUIRED"
                    current.copy(
                        isRefreshing = false,
                        filters = if (entitlementRequired) {
                            current.filters.withPaidLocks(adSupported = true).withActiveFilter(GridFilterType.Nearby)
                        } else {
                            current.filters
                        },
                        paywallMessage = if (entitlementRequired) {
                            "Social or Select unlocks this filter."
                        } else {
                            current.paywallMessage
                        },
                        errorCode = code,
                        errorMessage = if (entitlementRequired && current.cards.isNotEmpty()) {
                            null
                        } else {
                            error.message ?: "Grid could not be loaded."
                        }
                    )
                }
            }
    }

    suspend fun selectFilter(type: GridFilterType) {
        val selected = _state.value.filters.firstOrNull { it.type == type } ?: return
        if (selected.locked) {
            _state.update { current ->
                current.copy(
                    paywallMessage = "${selected.label} is included with Social or Select.",
                    errorCode = null,
                    errorMessage = null
                )
            }
            return
        }
        if (selected.active) {
            _state.update { current -> current.copy(paywallMessage = null) }
            return
        }
        _state.update { current ->
            current.copy(
                filters = current.filters.withActiveFilter(type),
                paywallMessage = null,
                errorCode = null,
                errorMessage = null
            )
        }
        load()
    }

    fun removeCardById(userId: String) {
        _state.update { current ->
            current.copy(cards = current.cards.filterNot { it.id == userId })
        }
    }
}

interface GridFeedRepository {
    suspend fun loadGrid(filter: GridFilterType): GridFeedUi
}

class ApiGridFeedRepository(
    private val apiClient: PynapliApiClient
) : GridFeedRepository {
    override suspend fun loadGrid(filter: GridFilterType): GridFeedUi =
        when (val result = apiClient.queryGrid(filter.toRequest())) {
            is AppResult.Success -> GridFeedUi(
                cards = result.value.items.mapIndexed { index, card ->
                    card.toGridProfileCardUi(index)
                },
                adSupported = result.value.adSupported,
                advancedFiltersApplied = result.value.advancedFiltersApplied
            )

            is AppResult.Failure -> throw GridFeedException(
                code = result.error.code,
                message = result.error.message
            )
        }
}

class SampleGridFeedRepository : GridFeedRepository {
    override suspend fun loadGrid(filter: GridFilterType): GridFeedUi =
        GridFeedUi(
            cards = sampleGridCards,
            adSupported = true,
            advancedFiltersApplied = filter != GridFilterType.Nearby
        )
}

data class GridFeedUi(
    val cards: List<GridProfileCardUi>,
    val adSupported: Boolean = false,
    val advancedFiltersApplied: Boolean = false
)

private class GridFeedException(
    val code: String,
    override val message: String
) : RuntimeException(message)

private fun GridFilterType.toRequest(): GridQueryRequestDto = when (this) {
    GridFilterType.Nearby -> GridQueryRequestDto(radiusMeters = 50_000, limit = 60)
    GridFilterType.Online -> GridQueryRequestDto(limit = 60, onlineOnly = true)
    GridFilterType.Linked -> GridQueryRequestDto(limit = 60, linkedOnly = true)
    GridFilterType.Age -> GridQueryRequestDto(limit = 60, minimumAge = 30, maximumAge = 55)
    GridFilterType.Tonight -> GridQueryRequestDto(limit = 60)
}

private val sampleGridCards = listOf(
    GridProfileCardUi(
        "1",
        "Maya",
        online = true,
        distanceLabel = "800 m",
        tone = GridCardTone.Warm,
        linkedPartner = GridLinkedPartnerUi(userId = "sample-linked-1", displayName = "Noa"),
        linkedPartnerTone = LinkedPartnerTone.Violet,
        distanceMeters = 800
    ),
    GridProfileCardUi("2", "Alex", online = true, distanceLabel = "1.2 km", tone = GridCardTone.Slate, distanceMeters = 1_200),
    GridProfileCardUi(
        "3",
        "Nico",
        online = false,
        distanceLabel = "2 km",
        tone = GridCardTone.Rust,
        linkedPartner = GridLinkedPartnerUi(userId = "sample-linked-3", displayName = "Lane"),
        linkedPartnerTone = LinkedPartnerTone.Gold,
        distanceMeters = 2_000
    ),
    GridProfileCardUi("4", "Sam", online = true, distanceLabel = null, tone = GridCardTone.DeepGreen),
    GridProfileCardUi("5", "Ari", online = false, distanceLabel = "4.6 km", tone = GridCardTone.Bronze, distanceMeters = 4_600),
    GridProfileCardUi(
        "6",
        "Drew",
        online = true,
        distanceLabel = "6 km",
        tone = GridCardTone.Graphite,
        linkedPartner = GridLinkedPartnerUi(userId = "sample-linked-6", displayName = "Rae"),
        linkedPartnerTone = LinkedPartnerTone.Pink,
        distanceMeters = 6_000
    ),
    GridProfileCardUi("7", "Jules", online = false, distanceLabel = "7 km", tone = GridCardTone.Teal, distanceMeters = 7_000),
    GridProfileCardUi(
        "8",
        "Kai",
        online = true,
        distanceLabel = "8.5 km",
        tone = GridCardTone.Rose,
        linkedPartner = GridLinkedPartnerUi(userId = "sample-linked-8", displayName = "Theo"),
        linkedPartnerTone = LinkedPartnerTone.Teal,
        distanceMeters = 8_500
    ),
    GridProfileCardUi("9", "Rae", online = false, distanceLabel = "9 km", tone = GridCardTone.Cocoa, distanceMeters = 9_000),
    GridProfileCardUi("10", "Theo", online = true, distanceLabel = "10 km", tone = GridCardTone.Moss, distanceMeters = 10_000),
    GridProfileCardUi(
        "11",
        "Noa",
        online = false,
        distanceLabel = "12 km",
        tone = GridCardTone.Stone,
        linkedPartner = GridLinkedPartnerUi(userId = "sample-linked-11", displayName = "Maya"),
        linkedPartnerTone = LinkedPartnerTone.Sun,
        distanceMeters = 12_000
    ),
    GridProfileCardUi("12", "Lane", online = true, distanceLabel = "14 km", tone = GridCardTone.Midnight, distanceMeters = 14_000)
)
