package com.pynapli.app.location

import androidx.compose.runtime.Immutable
import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.network.PynapliApiClient
import com.pynapli.shared.network.dto.SetTravelLocationRequestDto
import com.pynapli.shared.network.dto.TravelLocationDto
import com.pynapli.shared.network.dto.TravelPlaceDto
import com.pynapli.shared.network.dto.TravelPlaceSearchResponseDto
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

    suspend fun setActive(place: TravelPlaceDto) {
        _state.update { it.copy(isSaving = true, errorCode = null, errorMessage = null) }
        val request =
            SetTravelLocationRequestDto(
                latitude = place.latitude,
                longitude = place.longitude,
                city = place.city,
                region = place.region,
                country = place.country,
            )
        when (val result = repository.setActive(request)) {
            is AppResult.Success ->
                _state.update {
                    it.copy(
                        isSaving = false,
                        current = result.value,
                        searchQuery = "",
                        searchResults = emptyList(),
                    )
                }
            is AppResult.Failure ->
                _state.update {
                    val verification = result.error.code == "AGE_VERIFICATION_REQUIRED"
                    it.copy(
                        isSaving = false,
                        errorCode = result.error.code,
                        // Age-verification is surfaced via the sheet's verify prompt, not a raw error.
                        errorMessage = if (verification) null else result.error.message,
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
) {
    val entitlementRequired: Boolean get() = errorCode == "ENTITLEMENT_REQUIRED"
    val ageVerificationRequired: Boolean get() = errorCode == "AGE_VERIFICATION_REQUIRED"
    val isActive: Boolean get() = current != null
}

interface TravelLocationRepository {
    suspend fun getCurrent(): AppResult<TravelLocationDto?>

    suspend fun setActive(request: SetTravelLocationRequestDto): AppResult<TravelLocationDto>

    suspend fun clear(): AppResult<Unit>

    suspend fun searchPlaces(query: String): AppResult<TravelPlaceSearchResponseDto>
}

class ApiTravelLocationRepository(
    private val apiClient: PynapliApiClient,
) : TravelLocationRepository {
    override suspend fun getCurrent(): AppResult<TravelLocationDto?> = apiClient.getTravelLocation()

    override suspend fun setActive(request: SetTravelLocationRequestDto): AppResult<TravelLocationDto> = apiClient.setTravelLocation(request)

    override suspend fun clear(): AppResult<Unit> = apiClient.clearTravelLocation()

    override suspend fun searchPlaces(query: String): AppResult<TravelPlaceSearchResponseDto> = apiClient.searchTravelPlaces(query)
}
