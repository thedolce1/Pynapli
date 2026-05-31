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
    private val repository: LocationUpdateRepository,
) {
    private val _state = MutableStateFlow(LocationUiState())
    val state: StateFlow<LocationUiState> = _state.asStateFlow()
    private var lastSubmittedDeviceLocation: DeviceLocation? = null

    fun beginUpdate() {
        _state.update { current ->
            current.copy(isUpdating = true, errorMessage = null)
        }
    }

    fun markPermissionDenied() {
        _state.update { current ->
            current.copy(
                isUpdating = false,
                errorMessage = "Location permission is required to load nearby profiles.",
            )
        }
    }

    fun markUnavailable() {
        _state.update { current ->
            current.copy(
                isUpdating = false,
                errorMessage = "Current location is not available yet.",
            )
        }
    }

    suspend fun submitLocation(location: DeviceLocation): LocationUpdateResult {
        _state.update { current ->
            current.copy(isUpdating = true, errorMessage = null)
        }

        val gridRefresh = gridRefreshFor(location)
        if (gridRefresh == GridLocationRefresh.None) {
            _state.update { current ->
                current.copy(isUpdating = false, errorMessage = null)
            }
            return LocationUpdateResult(submitted = false, gridRefresh = GridLocationRefresh.None)
        }

        return when (val result = repository.updateCurrentLocation(location)) {
            is AppResult.Success -> {
                lastSubmittedDeviceLocation = location
                _state.update { current ->
                    current.copy(
                        isUpdating = false,
                        currentLocation = result.value,
                        errorMessage = null,
                    )
                }
                LocationUpdateResult(submitted = true, gridRefresh = gridRefresh)
            }

            is AppResult.Failure -> {
                _state.update { current ->
                    current.copy(
                        isUpdating = false,
                        errorMessage = result.error.message,
                    )
                }
                LocationUpdateResult(submitted = false, gridRefresh = GridLocationRefresh.None)
            }
        }
    }

    private fun gridRefreshFor(location: DeviceLocation): GridLocationRefresh {
        val previous = lastSubmittedDeviceLocation ?: return GridLocationRefresh.Refresh
        val movementMeters = previous.distanceMetersTo(location)
        return when {
            movementMeters < SMALL_MOVEMENT_THRESHOLD_METERS -> GridLocationRefresh.None
            movementMeters < LARGE_MOVEMENT_THRESHOLD_METERS -> GridLocationRefresh.QuietRefresh
            else -> GridLocationRefresh.Refresh
        }
    }

    companion object {
        private const val SMALL_MOVEMENT_THRESHOLD_METERS = 250.0
        private const val LARGE_MOVEMENT_THRESHOLD_METERS = 1_000.0
    }
}

data class LocationUpdateResult(
    val submitted: Boolean,
    val gridRefresh: GridLocationRefresh,
)

enum class GridLocationRefresh {
    None,
    QuietRefresh,
    Refresh,
}

interface LocationUpdateRepository {
    suspend fun updateCurrentLocation(location: DeviceLocation): AppResult<CurrentLocationDto>
}

class ApiLocationUpdateRepository(
    private val apiClient: PynapliApiClient,
) : LocationUpdateRepository {
    override suspend fun updateCurrentLocation(location: DeviceLocation): AppResult<CurrentLocationDto> =
        apiClient.updateCurrentLocation(
            UpdateCurrentLocationRequestDto(
                latitude = location.latitude,
                longitude = location.longitude,
                accuracyMeters = location.accuracyMeters,
                source = "gps",
            ),
        )
}
