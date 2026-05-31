package com.pynapli.app.grid

import androidx.compose.runtime.Immutable
import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.network.PynapliApiClient
import com.pynapli.shared.network.dto.SetTonightAvailableRequestDto
import com.pynapli.shared.network.dto.TonightAvailabilityDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class TonightAvailabilityStateHolder(
    private val repository: TonightAvailabilityRepository,
) {
    private val _state = MutableStateFlow(TonightAvailabilityUiState())
    val state: StateFlow<TonightAvailabilityUiState> = _state.asStateFlow()

    suspend fun load() {
        _state.update { it.copy(isLoading = true, errorMessage = null) }
        when (val result = repository.current()) {
            is AppResult.Success -> _state.update { it.copy(isLoading = false, availableUntil = result.value.availableUntil) }
            is AppResult.Failure -> _state.update { it.copy(isLoading = false, errorMessage = result.error.message) }
        }
    }

    suspend fun setAvailable(durationHours: Int? = null) {
        _state.update { it.copy(isMutating = true, errorMessage = null) }
        when (val result = repository.set(SetTonightAvailableRequestDto(durationHours = durationHours))) {
            is AppResult.Success -> _state.update { it.copy(isMutating = false, availableUntil = result.value.availableUntil) }
            is AppResult.Failure -> _state.update { it.copy(isMutating = false, errorMessage = result.error.message) }
        }
    }

    suspend fun clear() {
        _state.update { it.copy(isMutating = true, errorMessage = null) }
        when (val result = repository.clear()) {
            is AppResult.Success -> _state.update { it.copy(isMutating = false, availableUntil = null) }
            is AppResult.Failure -> _state.update { it.copy(isMutating = false, errorMessage = result.error.message) }
        }
    }
}

@Immutable
data class TonightAvailabilityUiState(
    val availableUntil: String? = null,
    val isLoading: Boolean = false,
    val isMutating: Boolean = false,
    val errorMessage: String? = null,
) {
    val isCurrentlyAvailable: Boolean get() = availableUntil != null
}

interface TonightAvailabilityRepository {
    suspend fun current(): AppResult<TonightAvailabilityDto>

    suspend fun set(request: SetTonightAvailableRequestDto): AppResult<TonightAvailabilityDto>

    suspend fun clear(): AppResult<TonightAvailabilityDto>
}

class ApiTonightAvailabilityRepository(
    private val apiClient: PynapliApiClient,
) : TonightAvailabilityRepository {
    override suspend fun current(): AppResult<TonightAvailabilityDto> = apiClient.getTonightAvailability()

    override suspend fun set(request: SetTonightAvailableRequestDto): AppResult<TonightAvailabilityDto> = apiClient.setTonightAvailability(request)

    override suspend fun clear(): AppResult<TonightAvailabilityDto> = apiClient.clearTonightAvailability()
}
