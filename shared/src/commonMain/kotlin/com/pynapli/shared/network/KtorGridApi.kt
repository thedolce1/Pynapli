package com.pynapli.shared.network

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
import com.pynapli.shared.network.dto.TravelLocationDto
import com.pynapli.shared.network.dto.TravelPlaceSearchResponseDto
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.http.HttpMethod

/** Ktor implementation of [GridApi], composed into `KtorPynapliApiClient` via delegation. */
internal class KtorGridApi(
    private val support: KtorApiSupport,
) : GridApi {
    override suspend fun queryGrid(request: GridQueryRequestDto): AppResult<GridQueryResponseDto> = support.authSend(HttpMethod.Post, "/v1/grid/query", request)

    override suspend fun listFavorites(cursor: String?): AppResult<GridQueryResponseDto> =
        support.authGet("/v1/favorites") {
            cursor?.let { parameter("cursor", it) }
        }

    override suspend fun addFavorite(userId: String): AppResult<FavoriteStatusDto> = support.authRequest(HttpMethod.Put, "/v1/favorites/$userId")

    override suspend fun removeFavorite(userId: String): AppResult<FavoriteStatusDto> = support.authDelete("/v1/favorites/$userId")

    override suspend fun listSavedGridFilters(): AppResult<SavedGridFiltersListDto> = support.authGet("/v1/me/grid-filters")

    override suspend fun createSavedGridFilter(request: CreateSavedGridFilterRequestDto): AppResult<SavedGridFilterDto> = support.authSend(HttpMethod.Post, "/v1/me/grid-filters", request)

    override suspend fun deleteSavedGridFilter(id: String): AppResult<Unit> =
        support.authenticated { token ->
            val response =
                support.httpClient.delete(support.endpoint("/v1/me/grid-filters/$id")) {
                    bearerAuth(token)
                }
            if (response.status.value in 200..299) {
                AppResult.Success(Unit)
            } else {
                response.toAppResult<Unit>(support.json)
            }
        }

    override suspend fun listSavedSwipeFilters(): AppResult<SavedSwipeFiltersListDto> = support.authGet("/v1/me/swipe-filters")

    override suspend fun createSavedSwipeFilter(request: CreateSavedSwipeFilterRequestDto): AppResult<SavedSwipeFilterDto> = support.authSend(HttpMethod.Post, "/v1/me/swipe-filters", request)

    override suspend fun deleteSavedSwipeFilter(id: String): AppResult<Unit> =
        support.authenticated { token ->
            val response =
                support.httpClient.delete(support.endpoint("/v1/me/swipe-filters/$id")) {
                    bearerAuth(token)
                }
            if (response.status.value in 200..299) {
                AppResult.Success(Unit)
            } else {
                response.toAppResult<Unit>(support.json)
            }
        }

    override suspend fun getTravelLocation(): AppResult<TravelLocationDto?> =
        support.authenticated { token ->
            val response =
                support.httpClient.get(support.endpoint("/v1/location/travel")) {
                    bearerAuth(token)
                }
            // 204 No Content means no active travel row — fold to Success(null)
            // so callers don't have to special-case the empty state.
            if (response.status.value == 204) {
                AppResult.Success(null)
            } else {
                @Suppress("UNCHECKED_CAST")
                response.toAppResult<TravelLocationDto>(support.json) as AppResult<TravelLocationDto?>
            }
        }

    override suspend fun setTravelLocation(request: SetTravelLocationRequestDto): AppResult<TravelLocationDto> = support.authSend(HttpMethod.Post, "/v1/location/travel", request)

    override suspend fun listTravelTrips(): AppResult<com.pynapli.shared.network.dto.TravelTripsDto> = support.authGet("/v1/location/travel/trips")

    override suspend fun planTravelTrip(request: com.pynapli.shared.network.dto.PlanTravelTripRequestDto): AppResult<com.pynapli.shared.network.dto.TravelTripDto> = support.authSend(HttpMethod.Post, "/v1/location/travel/trips", request)

    override suspend fun cancelTravelTrip(tripId: String): AppResult<Unit> =
        support.authenticated { token ->
            val response =
                support.httpClient.delete(support.endpoint("/v1/location/travel/trips/$tripId")) {
                    bearerAuth(token)
                }
            if (response.status.value in 200..299) AppResult.Success(Unit) else response.toAppResult<Unit>(support.json)
        }

    override suspend fun clearTravelLocation(): AppResult<Unit> =
        support.authenticated { token ->
            val response =
                support.httpClient.delete(support.endpoint("/v1/location/travel")) {
                    bearerAuth(token)
                }
            if (response.status.value in 200..299) {
                AppResult.Success(Unit)
            } else {
                response.toAppResult<Unit>(support.json)
            }
        }

    override suspend fun searchTravelPlaces(query: String): AppResult<TravelPlaceSearchResponseDto> =
        support.authGet("/v1/location/travel/search") {
            url.parameters.append("q", query)
        }
}
