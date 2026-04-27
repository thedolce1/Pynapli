package com.pynapli.app.location

import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.network.PynapliApiClient
import com.pynapli.shared.network.dto.CurrentLocationDto
import com.pynapli.shared.network.dto.UpdateCurrentLocationRequestDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class LocationStateHolder(
    private val repository: LocationUpdateRepository
) {
    private val _state = MutableStateFlow(LocationUiState())
    val state: StateFlow<LocationUiState> = _state.asStateFlow()

    fun beginUpdate() {
        _state.update { current ->
            current.copy(isUpdating = true, errorMessage = null)
        }
    }

    fun markPermissionDenied() {
        _state.update { current ->
            current.copy(
                isUpdating = false,
                errorMessage = "Location permission is required to load nearby profiles."
            )
        }
    }

    fun markUnavailable() {
        _state.update { current ->
            current.copy(
                isUpdating = false,
                errorMessage = "Current location is not available yet."
            )
        }
    }

    suspend fun submitLocation(location: DeviceLocation): Boolean {
        _state.update { current ->
            current.copy(isUpdating = true, errorMessage = null)
        }

        return when (val result = repository.updateCurrentLocation(location)) {
            is AppResult.Success -> {
                _state.update { current ->
                    current.copy(
                        isUpdating = false,
                        currentLocation = result.value,
                        errorMessage = null
                    )
                }
                true
            }

            is AppResult.Failure -> {
                _state.update { current ->
                    current.copy(
                        isUpdating = false,
                        errorMessage = result.error.message
                    )
                }
                false
            }
        }
    }
}

interface LocationUpdateRepository {
    suspend fun updateCurrentLocation(location: DeviceLocation): AppResult<CurrentLocationDto>
}

class ApiLocationUpdateRepository(
    private val apiClient: PynapliApiClient
) : LocationUpdateRepository {
    override suspend fun updateCurrentLocation(location: DeviceLocation): AppResult<CurrentLocationDto> =
        apiClient.updateCurrentLocation(
            UpdateCurrentLocationRequestDto(
                latitude = location.latitude,
                longitude = location.longitude,
                accuracyMeters = location.accuracyMeters,
                source = "gps"
            )
        )
}
