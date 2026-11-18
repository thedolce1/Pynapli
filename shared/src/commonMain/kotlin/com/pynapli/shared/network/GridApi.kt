package com.pynapli.shared.network

import com.pynapli.shared.core.result.AppError
import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.network.dto.CreateSavedGridFilterRequestDto
import com.pynapli.shared.network.dto.CreateSavedSwipeFilterRequestDto
import com.pynapli.shared.network.dto.FavoriteStatusDto
import com.pynapli.shared.network.dto.GridQueryRequestDto
import com.pynapli.shared.network.dto.GridQueryResponseDto
import com.pynapli.shared.network.dto.SavedGridFilterDto
import com.pynapli.shared.network.dto.SavedGridFiltersListDto
import com.pynapli.shared.network.dto.SavedSwipeFilterDto
import com.pynapli.shared.network.dto.SavedSwipeFiltersListDto
import com.pynapli.shared.network.dto.SetTravelLocationRequestDto
import com.pynapli.shared.network.dto.TopPicksResponseDto
import com.pynapli.shared.network.dto.TravelLocationDto
import com.pynapli.shared.network.dto.TravelPlaceSearchResponseDto

/**
 * Grid/discovery slice of the API surface, split out of the monolithic [PynapliApiClient]
 * (which now composes the per-feature interfaces). Covers grid queries, saved grid/swipe
 * filters, top picks, and travel location. Signatures are unchanged.
 */
interface GridApi {
    suspend fun queryGrid(request: GridQueryRequestDto = GridQueryRequestDto()): AppResult<GridQueryResponseDto>

    /** The viewer's Favorites as Grid cards: online first, then coarse distance. */
    suspend fun listFavorites(cursor: String? = null): AppResult<GridQueryResponseDto> = notSupported()

    /** Stars [userId]. Idempotent, and private: the other person is never told. */
    suspend fun addFavorite(userId: String): AppResult<FavoriteStatusDto> = notSupported()

    /** Un-stars [userId]. Idempotent. */
    suspend fun removeFavorite(userId: String): AppResult<FavoriteStatusDto> = notSupported()

    suspend fun listSavedGridFilters(): AppResult<SavedGridFiltersListDto>

    suspend fun createSavedGridFilter(request: CreateSavedGridFilterRequestDto): AppResult<SavedGridFilterDto>

    suspend fun deleteSavedGridFilter(id: String): AppResult<Unit>

    suspend fun listSavedSwipeFilters(): AppResult<SavedSwipeFiltersListDto>

    suspend fun createSavedSwipeFilter(request: CreateSavedSwipeFilterRequestDto): AppResult<SavedSwipeFilterDto>

    suspend fun deleteSavedSwipeFilter(id: String): AppResult<Unit>

    suspend fun getTopPicks(): AppResult<TopPicksResponseDto>

    suspend fun getTravelLocation(): AppResult<TravelLocationDto?>

    suspend fun setTravelLocation(request: SetTravelLocationRequestDto): AppResult<TravelLocationDto>

    suspend fun clearTravelLocation(): AppResult<Unit>

    /** Planned trips (Select): Travel mode scheduled ahead. */
    suspend fun listTravelTrips(): AppResult<com.pynapli.shared.network.dto.TravelTripsDto> =
        AppResult.Failure(
            com.pynapli.shared.core.result
                .AppError("NOT_SUPPORTED", "Not available."),
        )

    suspend fun planTravelTrip(request: com.pynapli.shared.network.dto.PlanTravelTripRequestDto): AppResult<com.pynapli.shared.network.dto.TravelTripDto> =
        AppResult.Failure(
            com.pynapli.shared.core.result
                .AppError("NOT_SUPPORTED", "Not available."),
        )

    suspend fun cancelTravelTrip(tripId: String): AppResult<Unit> =
        AppResult.Failure(
            com.pynapli.shared.core.result
                .AppError("NOT_SUPPORTED", "Not available."),
        )

    /** Type-ahead place search for the free-text travel picker (city name or ZIP/postal code). */
    suspend fun searchTravelPlaces(query: String): AppResult<TravelPlaceSearchResponseDto>
}

// Default for the Favorites calls so hand-written test fakes of the whole client keep compiling.
private fun notSupported(): AppResult.Failure = AppResult.Failure(AppError(code = "NOT_SUPPORTED", message = "Not available."))
