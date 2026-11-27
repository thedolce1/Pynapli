package com.pynapli.app.location

import androidx.compose.runtime.Immutable
import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.network.PynapliApiClient
import com.pynapli.shared.network.dto.PlanTravelTripRequestDto
import com.pynapli.shared.network.dto.SetTravelLocationRequestDto
import com.pynapli.shared.network.dto.TravelLocationDto
import com.pynapli.shared.network.dto.TravelPlaceDto
import com.pynapli.shared.network.dto.TravelPlaceSearchResponseDto
import com.pynapli.shared.network.dto.TravelTripDto
import com.pynapli.shared.network.dto.TravelTripsDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Client-side store for passport/travel mode (SWIPE_IMPLEMENTATION_SPEC L339-354).
 * `load()` reads the server's view of the current active travel row (null = local
 * mode). The destination picker is free-text (founder UX decision 2026-06-09):
 * `updateSearchQuery` + `runSearch` resolve a typed city name or ZIP/postal code
 * against the server's place directory, and `setActive(place)` activates a picked
 * result. Free/Social-tier users see `ENTITLEMENT_REQUIRED` and the sheet shows a
 * paywall hint instead of the picker.
 */
class TravelLocationStateHolder(
    private val repository: TravelLocationRepository,
    /**
     * Travel mode moves both the Grid and the Swipe deck, whichever screen switched it. Called after
     * every change that moves the viewer's effective place (on, off, a trip starting today, a running
     * trip cancelled); the graph wires it to mark both stale.
     */
    private val onTravelChanged: () -> Unit = {},
) {
    private val _state = MutableStateFlow(TravelLocationUiState())
    val state: StateFlow<TravelLocationUiState> = _state.asStateFlow()

    /** Bumped by [reset]; a [load] that started before it belongs to the account that signed out. */
    private var epoch = 0

    /**
     * Signed out: forget this account's Travel state (current city, search, trips), so the next
     * account on the device never sees it, nor has its Grid's travel key compared against it.
     */
    fun reset() {
        epoch += 1
        _state.value = TravelLocationUiState()
    }

    suspend fun load() {
        _state.update { it.copy(isLoading = true, errorCode = null, errorMessage = null) }
        val startedEpoch = epoch
        val result = repository.getCurrent()
        if (startedEpoch != epoch) return
        when (result) {
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

    /** Updates the picker query immediately; the caller debounces before [runSearch]. */
    fun updateSearchQuery(query: String) {
        _state.update {
            it.copy(
                searchQuery = query,
                searchResults = if (query.isBlank()) emptyList() else it.searchResults,
            )
        }
    }

    suspend fun runSearch() {
        val query = _state.value.searchQuery.trim()
        if (query.length < MIN_SEARCH_QUERY_LENGTH) {
            _state.update { it.copy(searchResults = emptyList(), isSearching = false) }
            return
        }
        _state.update { it.copy(isSearching = true) }
        when (val result = repository.searchPlaces(query)) {
            is AppResult.Success ->
                _state.update { current ->
                    // Drop stale responses: only apply if the query hasn't moved on.
                    if (current.searchQuery.trim() == query) {
                        current.copy(isSearching = false, searchResults = result.value.items)
                    } else {
                        current.copy(isSearching = false)
                    }
                }
            is AppResult.Failure ->
                // A failed lookup keeps the sheet quiet (no banner) — the user just
                // sees no suggestions; retyping retries naturally.
                _state.update { it.copy(isSearching = false, searchResults = emptyList()) }
        }
    }

    /** Returns true when the trip is on; on failure the sheet stays open and says why. */
    suspend fun setActive(place: TravelPlaceDto): Boolean {
        _state.update { it.copy(isSaving = true, errorCode = null, errorMessage = null) }
        val request =
            SetTravelLocationRequestDto(
                latitude = place.latitude,
                longitude = place.longitude,
                city = place.city,
                region = place.region,
                country = place.country,
            )
        return when (val result = repository.setActive(request)) {
            is AppResult.Success -> {
                _state.update {
                    it.copy(
                        isSaving = false,
                        current = result.value,
                        searchQuery = "",
                        searchResults = emptyList(),
                    )
                }
                onTravelChanged()
                true
            }
            is AppResult.Failure -> {
                _state.update {
                    val bannerHandled = result.error.code in BANNER_CODES
                    it.copy(
                        isSaving = false,
                        errorCode = result.error.code,
                        // Verify and paywall cases get their own banner, not a raw error line.
                        errorMessage = if (bannerHandled) null else result.error.message,
                    )
                }
                false
            }
        }
    }

    /** Upcoming and running planned trips (Select); a refusal just leaves the list empty. */
    suspend fun loadTrips() {
        val result = repository.listTrips()
        if (result is AppResult.Success) _state.update { it.copy(trips = result.value.trips) }
    }

    /** Picked a place: the sheet asks "Go now" or "Plan dates" before anything switches. */
    fun selectPlace(place: TravelPlaceDto?) {
        _state.update { it.copy(selectedPlace = place, errorCode = null, errorMessage = null) }
    }

    /** Plans a trip to the selected place; true when it's saved (and on now, if it starts today). */
    suspend fun planTrip(
        place: TravelPlaceDto,
        startsOn: String,
        endsOn: String,
        timezone: String,
        announce: Boolean = false,
    ): Boolean {
        _state.update { it.copy(isSaving = true, errorCode = null, errorMessage = null) }
        val request =
            PlanTravelTripRequestDto(
                latitude = place.latitude,
                longitude = place.longitude,
                city = place.city,
                region = place.region,
                country = place.country,
                startsOn = startsOn,
                endsOn = endsOn,
                timezone = timezone,
                announce = announce,
            )
        return when (val result = repository.planTrip(request)) {
            is AppResult.Success -> {
                _state.update {
                    it.copy(isSaving = false, selectedPlace = null, searchQuery = "", searchResults = emptyList())
                }
                loadTrips()
                // A trip that starts today is already on: show it as the current destination.
                if (result.value.status == "active") {
                    load()
                    onTravelChanged()
                }
                true
            }
            is AppResult.Failure -> {
                _state.update {
                    val bannerHandled = result.error.code in BANNER_CODES
                    it.copy(isSaving = false, errorCode = result.error.code, errorMessage = if (bannerHandled) null else result.error.message)
                }
                false
            }
        }
    }

    suspend fun cancelTrip(tripId: String) {
        when (val result = repository.cancelTrip(tripId)) {
            is AppResult.Success -> {
                val wasRunning = _state.value.trips.any { it.tripId == tripId && it.status == "active" }
                _state.update { current -> current.copy(trips = current.trips.filterNot { it.tripId == tripId }) }
                if (wasRunning) {
                    load()
                    onTravelChanged()
                }
            }
            is AppResult.Failure -> _state.update { it.copy(errorMessage = result.error.message) }
        }
    }

    suspend fun clear() {
        _state.update { it.copy(isSaving = true, errorCode = null, errorMessage = null) }
        when (val result = repository.clear()) {
            is AppResult.Success -> {
                _state.update {
                    it.copy(isSaving = false, current = null)
                }
                onTravelChanged()
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

    companion object {
        /** Mirrors TravelPlaceSearchService.MIN_QUERY_LENGTH — saves a round-trip below it. */
        const val MIN_SEARCH_QUERY_LENGTH = 2
    }
}

@Immutable
data class TravelLocationUiState(
    val current: TravelLocationDto? = null,
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val searchQuery: String = "",
    val searchResults: List<TravelPlaceDto> = emptyList(),
    val isSearching: Boolean = false,
    val errorCode: String? = null,
    val errorMessage: String? = null,
    // Planned trips (upcoming and running) and the place picked but not yet confirmed.
    val trips: List<TravelTripDto> = emptyList(),
    val selectedPlace: TravelPlaceDto? = null,
) {
    // Free has no Travel; Social's monthly preview is used up. Both lead to the Select paywall.
    val entitlementRequired: Boolean get() = errorCode == "ENTITLEMENT_REQUIRED" || previewUsed
    val previewUsed: Boolean get() = errorCode == "TRAVEL_PREVIEW_RATE_LIMITED"
    val ageVerificationRequired: Boolean get() = errorCode == "AGE_VERIFICATION_REQUIRED"
    val isActive: Boolean get() = current != null
}

interface TravelLocationRepository {
    suspend fun getCurrent(): AppResult<TravelLocationDto?>

    suspend fun setActive(request: SetTravelLocationRequestDto): AppResult<TravelLocationDto>

    suspend fun clear(): AppResult<Unit>

    suspend fun searchPlaces(query: String): AppResult<TravelPlaceSearchResponseDto>

    suspend fun listTrips(): AppResult<TravelTripsDto> = AppResult.Success(TravelTripsDto(emptyList()))

    suspend fun planTrip(request: PlanTravelTripRequestDto): AppResult<TravelTripDto> =
        AppResult.Failure(
            com.pynapli.shared.core.result
                .AppError("NOT_SUPPORTED", "Not available."),
        )

    suspend fun cancelTrip(tripId: String): AppResult<Unit> = AppResult.Success(Unit)
}

class ApiTravelLocationRepository(
    private val apiClient: PynapliApiClient,
) : TravelLocationRepository {
    override suspend fun getCurrent(): AppResult<TravelLocationDto?> = apiClient.getTravelLocation()

    override suspend fun setActive(request: SetTravelLocationRequestDto): AppResult<TravelLocationDto> = apiClient.setTravelLocation(request)

    override suspend fun clear(): AppResult<Unit> = apiClient.clearTravelLocation()

    override suspend fun searchPlaces(query: String): AppResult<TravelPlaceSearchResponseDto> = apiClient.searchTravelPlaces(query)

    override suspend fun listTrips(): AppResult<TravelTripsDto> = apiClient.listTravelTrips()

    override suspend fun planTrip(request: PlanTravelTripRequestDto): AppResult<TravelTripDto> = apiClient.planTravelTrip(request)

    override suspend fun cancelTrip(tripId: String): AppResult<Unit> = apiClient.cancelTravelTrip(tripId)
}

private val BANNER_CODES = setOf("AGE_VERIFICATION_REQUIRED", "ENTITLEMENT_REQUIRED", "TRAVEL_PREVIEW_RATE_LIMITED")
