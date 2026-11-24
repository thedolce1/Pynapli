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

    /**
     * Bumped by [reset]. A submit that started under an older epoch belongs to the account that
     * signed out, so its answer must not write that account's anchor or location into the next one.
     */
    private var epoch = 0

    /**
     * Signed out (or the session died): forget the previous account's location, so the next account
     * on this device gets its own Location step and its first fix is always sent. Without this the
     * movement anchor would drop a fix under 250 m from where the last account stood, and the
     * non-null `currentLocation` would auto-continue the next account's Location step.
     */
    fun reset() {
        epoch += 1
        lastSubmittedDeviceLocation = null
        _state.value = LocationUiState()
    }

    fun beginUpdate() {
        _state.update { current ->
            current.copy(isUpdating = true, errorMessage = null, blockedReason = null)
        }
    }

    /**
     * The OS (or browser) said no. [permanently] when asking again would not show a prompt (Android
     * "Don't allow" twice or "Don't ask again", a browser that remembers the block): the screen then
     * offers the way to Settings instead of a button that can no longer ask.
     */
    fun markPermissionDenied(permanently: Boolean = false) {
        _state.update { current ->
            current.copy(
                isUpdating = false,
                errorMessage = "Location permission is required to load nearby profiles.",
                blockedReason = if (permanently) LocationBlockedReason.PermissionBlocked else null,
            )
        }
    }

    /** Device location services are switched off (no provider at all), so no app can get a fix. */
    fun markServicesOff() {
        _state.update { current ->
            current.copy(
                isUpdating = false,
                errorMessage = "Location services are turned off on this device.",
                blockedReason = LocationBlockedReason.ServicesOff,
            )
        }
    }

    fun markUnavailable() {
        _state.update { current ->
            current.copy(
                isUpdating = false,
                errorMessage = "Current location is not available yet.",
                blockedReason = null,
            )
        }
    }

    suspend fun submitLocation(location: DeviceLocation): LocationUpdateResult {
        _state.update { current ->
            current.copy(isUpdating = true, errorMessage = null, blockedReason = null)
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
        val startedEpoch = epoch
        val result = repository.updateCurrentLocation(location.toCell())
        if (startedEpoch != epoch) {
            // Signed out while this was in flight: the answer belongs to the previous account.
            return LocationUpdateResult(submitted = false, gridRefresh = GridLocationRefresh.None)
        }
        return when (result) {
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
