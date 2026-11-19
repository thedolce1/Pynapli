package com.pynapli.app.location

import com.pynapli.app.grid.GridStateHolder
import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.location.snapToLocationCell
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

        // Location privacy: only the cell centre leaves the device, with no accuracy figure. The
        // precise fix stays in memory as the movement anchor so the 250 m / 1 km decisions above
        // behave exactly as before; it is never persisted or logged.
        return when (val result = repository.updateCurrentLocation(location.toCell())) {
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

/**
 * Submits [location], then refreshes [grid] the way the result asks: quietly for a small move (cards
 * stay on screen), fully for a big one, not at all when nothing changed. Every shell's location adapter
 * calls this instead of repeating the dispatch.
 */
suspend fun LocationStateHolder.submitAndRefresh(
    location: DeviceLocation,
    grid: GridStateHolder,
): LocationUpdateResult {
    val result = submitLocation(location)
    when (result.gridRefresh) {
        GridLocationRefresh.None -> Unit
        GridLocationRefresh.QuietRefresh -> grid.load(quiet = true)
        GridLocationRefresh.Refresh -> grid.load()
    }
    return result
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

/** The fix snapped to its 0.01-degree cell (the server's `pynapli_location_cell`), without accuracy. */
internal fun DeviceLocation.toCell(): DeviceLocation {
    val cell = snapToLocationCell(latitude = latitude, longitude = longitude)
    return DeviceLocation(latitude = cell.latitude, longitude = cell.longitude, accuracyMeters = null)
}

/** Receives only cell-snapped locations from [LocationStateHolder]. */
interface LocationUpdateRepository {
    suspend fun updateCurrentLocation(location: DeviceLocation): AppResult<CurrentLocationDto>
}

class ApiLocationUpdateRepository(
    private val apiClient: PynapliApiClient,
) : LocationUpdateRepository {
    override suspend fun updateCurrentLocation(location: DeviceLocation): AppResult<CurrentLocationDto> {
        // Snapping is idempotent, so snapping again here costs nothing and keeps the request
        // cell-only even if a future caller skips the state holder.
        val cell = location.toCell()
        return apiClient.updateCurrentLocation(
            UpdateCurrentLocationRequestDto(
                latitude = cell.latitude,
                longitude = cell.longitude,
                accuracyMeters = null,
                source = "gps",
            ),
        )
    }
}
