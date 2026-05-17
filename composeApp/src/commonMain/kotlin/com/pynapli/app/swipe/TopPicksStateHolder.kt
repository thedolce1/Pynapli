package com.pynapli.app.swipe

import androidx.compose.runtime.Immutable
import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.network.PynapliApiClient
import com.pynapli.shared.network.dto.TopPickCardDto
import com.pynapli.shared.network.dto.TopPicksResponseDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Client-side store for the daily Top Picks set (SWIPE_IMPLEMENTATION_SPEC L293-313).
 * Repeat reads inside the 24h window return the same server session, so we just
 * cache the latest response in StateFlow. `refresh()` re-fetches without clearing
 * the previous items so the row doesn't flicker between loads.
 */
class TopPicksStateHolder(
    private val repository: TopPicksRepository
) {
    private val _state = MutableStateFlow(TopPicksUiState())
    val state: StateFlow<TopPicksUiState> = _state.asStateFlow()

    suspend fun load() {
        _state.update { it.copy(isLoading = true, errorMessage = null, errorCode = null) }
        when (val result = repository.getCurrentPicks()) {
            is AppResult.Success -> _state.update {
                val response = result.value
                it.copy(
                    isLoading = false,
                    sessionId = response.sessionId,
                    expiresAt = response.expiresAt,
                    generatedAt = response.generatedAt,
                    tierLimit = response.tierLimit,
                    items = response.picks
                )
            }
            is AppResult.Failure -> _state.update {
                it.copy(
                    isLoading = false,
                    errorCode = result.error.code,
                    errorMessage = result.error.message
                )
            }
        }
    }

    fun consumeError() {
        _state.update { it.copy(errorCode = null, errorMessage = null) }
    }
}

@Immutable
data class TopPicksUiState(
    val items: List<TopPickCardDto> = emptyList(),
    val sessionId: String? = null,
    val generatedAt: String? = null,
    val expiresAt: String? = null,
    val tierLimit: Int = 0,
    val isLoading: Boolean = false,
    val errorCode: String? = null,
    val errorMessage: String? = null
) {
    val locationRequired: Boolean get() = errorCode == "LOCATION_REQUIRED"
}

interface TopPicksRepository {
    suspend fun getCurrentPicks(): AppResult<TopPicksResponseDto>
}

class ApiTopPicksRepository(
    private val apiClient: PynapliApiClient
) : TopPicksRepository {
    override suspend fun getCurrentPicks(): AppResult<TopPicksResponseDto> =
        apiClient.getTopPicks()
}
