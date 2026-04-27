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
        _state.update { current -> current.copy(isRefreshing = true, errorCode = null, errorMessage = null) }
        runCatching { repository.loadGrid() }
            .onSuccess { cards ->
                _state.update { current ->
                    current.copy(
                        cards = cards,
                        isRefreshing = false,
                        errorCode = null,
                        errorMessage = null
                    )
                }
            }
            .onFailure { error ->
                _state.update { current ->
                    current.copy(
                        isRefreshing = false,
                        errorCode = (error as? GridFeedException)?.code,
                        errorMessage = error.message ?: "Grid could not be loaded."
                    )
                }
            }
    }

    fun selectFilter(type: GridFilterType) {
        _state.update { current ->
            current.copy(filters = current.filters.withActiveFilter(type))
        }
    }
}

interface GridFeedRepository {
    suspend fun loadGrid(): List<GridProfileCardUi>
}

class ApiGridFeedRepository(
    private val apiClient: PynapliApiClient,
    private val request: GridQueryRequestDto = GridQueryRequestDto(limit = 60)
) : GridFeedRepository {
    override suspend fun loadGrid(): List<GridProfileCardUi> =
        when (val result = apiClient.queryGrid(request)) {
            is AppResult.Success -> result.value.items.mapIndexed { index, card ->
                card.toGridProfileCardUi(index)
            }

            is AppResult.Failure -> throw GridFeedException(
                code = result.error.code,
                message = result.error.message
            )
        }
}

class SampleGridFeedRepository : GridFeedRepository {
    override suspend fun loadGrid(): List<GridProfileCardUi> = sampleGridCards
}

private class GridFeedException(
    val code: String,
    override val message: String
) : RuntimeException(message)

private val sampleGridCards = listOf(
    GridProfileCardUi("1", "Maya", online = true, distanceLabel = "800 m", tone = GridCardTone.Warm, linkedPartnerTone = LinkedPartnerTone.Violet),
    GridProfileCardUi("2", "Alex", online = true, distanceLabel = "1.2 km", tone = GridCardTone.Slate),
    GridProfileCardUi("3", "Nico", online = false, distanceLabel = "2 km", tone = GridCardTone.Rust, linkedPartnerTone = LinkedPartnerTone.Gold),
    GridProfileCardUi("4", "Sam", online = true, distanceLabel = null, tone = GridCardTone.DeepGreen),
    GridProfileCardUi("5", "Ari", online = false, distanceLabel = "4.6 km", tone = GridCardTone.Bronze),
    GridProfileCardUi("6", "Drew", online = true, distanceLabel = "6 km", tone = GridCardTone.Graphite, linkedPartnerTone = LinkedPartnerTone.Pink),
    GridProfileCardUi("7", "Jules", online = false, distanceLabel = "7 km", tone = GridCardTone.Teal),
    GridProfileCardUi("8", "Kai", online = true, distanceLabel = "8.5 km", tone = GridCardTone.Rose, linkedPartnerTone = LinkedPartnerTone.Teal),
    GridProfileCardUi("9", "Rae", online = false, distanceLabel = "9 km", tone = GridCardTone.Cocoa),
    GridProfileCardUi("10", "Theo", online = true, distanceLabel = "10 km", tone = GridCardTone.Moss),
    GridProfileCardUi("11", "Noa", online = false, distanceLabel = "12 km", tone = GridCardTone.Stone, linkedPartnerTone = LinkedPartnerTone.Sun),
    GridProfileCardUi("12", "Lane", online = true, distanceLabel = "14 km", tone = GridCardTone.Midnight)
)
