package com.pynapli.app.location

import androidx.compose.runtime.Immutable
import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.network.PynapliApiClient
import com.pynapli.shared.network.dto.SetTravelLocationRequestDto
import com.pynapli.shared.network.dto.TravelLocationDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Client-side store for passport/travel mode (SWIPE_IMPLEMENTATION_SPEC L339-354).
 * `load()` reads the server's view of the current active travel row (null = local
 * mode). `setActive(city)` and `clear()` flip the server state and the local copy
 * reflects the result. Free/Social-tier users see `ENTITLEMENT_REQUIRED` and the
 * sheet shows a paywall hint instead of the picker.
 */
class TravelLocationStateHolder(
    private val repository: TravelLocationRepository,
) {
    private val _state = MutableStateFlow(TravelLocationUiState())
    val state: StateFlow<TravelLocationUiState> = _state.asStateFlow()

    suspend fun load() {
        _state.update { it.copy(isLoading = true, errorCode = null, errorMessage = null) }
        when (val result = repository.getCurrent()) {
            is AppResult.Success ->
                _state.update {
                    it.copy(isLoading = false, current = result.value)
                }
            is AppResult.Failure ->
                _state.update {
                    it.copy(
                        isLoading = false,
                        errorCode = result.error.code,
                        errorMessage = result.error.message,
                    )
                }
        }
    }

    suspend fun setActive(city: TravelCityPreset) {
        _state.update { it.copy(isSaving = true, errorCode = null, errorMessage = null) }
        val request =
            SetTravelLocationRequestDto(
                latitude = city.latitude,
                longitude = city.longitude,
                city = city.city,
                region = city.region,
                country = city.country,
            )
        when (val result = repository.setActive(request)) {
            is AppResult.Success ->
                _state.update {
                    it.copy(isSaving = false, current = result.value)
                }
            is AppResult.Failure ->
                _state.update {
                    it.copy(
                        isSaving = false,
                        errorCode = result.error.code,
                        errorMessage = result.error.message,
                    )
                }
        }
    }

    suspend fun clear() {
        _state.update { it.copy(isSaving = true, errorCode = null, errorMessage = null) }
        when (val result = repository.clear()) {
            is AppResult.Success ->
                _state.update {
                    it.copy(isSaving = false, current = null)
                }
            is AppResult.Failure ->
                _state.update {
                    it.copy(
                        isSaving = false,
                        errorCode = result.error.code,
                        errorMessage = result.error.message,
                    )
                }
        }
    }

    fun consumeError() {
        _state.update { it.copy(errorCode = null, errorMessage = null) }
    }
}

@Immutable
data class TravelLocationUiState(
    val current: TravelLocationDto? = null,
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val errorCode: String? = null,
    val errorMessage: String? = null,
) {
    val entitlementRequired: Boolean get() = errorCode == "ENTITLEMENT_REQUIRED"
    val isActive: Boolean get() = current != null
}

interface TravelLocationRepository {
    suspend fun getCurrent(): AppResult<TravelLocationDto?>

    suspend fun setActive(request: SetTravelLocationRequestDto): AppResult<TravelLocationDto>

    suspend fun clear(): AppResult<Unit>
}

class ApiTravelLocationRepository(
    private val apiClient: PynapliApiClient,
) : TravelLocationRepository {
    override suspend fun getCurrent(): AppResult<TravelLocationDto?> = apiClient.getTravelLocation()

    override suspend fun setActive(request: SetTravelLocationRequestDto): AppResult<TravelLocationDto> = apiClient.setTravelLocation(request)

    override suspend fun clear(): AppResult<Unit> = apiClient.clearTravelLocation()
}

/**
 * Preset destinations for the MVP picker. We deliberately ship a small curated
 * list instead of free-text + geocoding so the slice has zero external service
 * dependencies. Real geocoding lands when the picker UX warrants it.
 *
 * Coordinates are city-center approximations sourced from public records;
 * they're used as the deck's center point, not as a high-precision pin.
 */
@Immutable
data class TravelCityPreset(
    val id: String,
    val city: String,
    val region: String?,
    val country: String?,
    val latitude: Double,
    val longitude: Double,
) {
    val displayName: String
        get() = listOfNotNull(city, country).joinToString(", ")
}

object TravelCityPresets {
    val ALL: List<TravelCityPreset> =
        listOf(
            TravelCityPreset("nyc", "New York", "NY", "United States", 40.7128, -74.0060),
            TravelCityPreset("la", "Los Angeles", "CA", "United States", 34.0522, -118.2437),
            TravelCityPreset("sf", "San Francisco", "CA", "United States", 37.7749, -122.4194),
            TravelCityPreset("mia", "Miami", "FL", "United States", 25.7617, -80.1918),
            TravelCityPreset("yyz", "Toronto", "ON", "Canada", 43.6532, -79.3832),
            TravelCityPreset("lon", "London", null, "United Kingdom", 51.5074, -0.1278),
            TravelCityPreset("ber", "Berlin", null, "Germany", 52.5200, 13.4050),
            TravelCityPreset("par", "Paris", null, "France", 48.8566, 2.3522),
            TravelCityPreset("tok", "Tokyo", null, "Japan", 35.6762, 139.6503),
            TravelCityPreset("syd", "Sydney", "NSW", "Australia", -33.8688, 151.2093),
        )

    fun matchPreset(dto: TravelLocationDto): TravelCityPreset? = ALL.firstOrNull { it.city.equals(dto.city, ignoreCase = true) }
}
