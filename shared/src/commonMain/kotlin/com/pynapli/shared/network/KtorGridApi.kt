package com.pynapli.shared.network

import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.network.dto.CreateSavedGridFilterRequestDto
import com.pynapli.shared.network.dto.CreateSavedSwipeFilterRequestDto
import com.pynapli.shared.network.dto.GridQueryRequestDto
import com.pynapli.shared.network.dto.GridQueryResponseDto
import com.pynapli.shared.network.dto.SavedGridFilterDto
import com.pynapli.shared.network.dto.SavedGridFiltersListDto
import com.pynapli.shared.network.dto.SavedSwipeFilterDto
import com.pynapli.shared.network.dto.SavedSwipeFiltersListDto
import com.pynapli.shared.network.dto.SetTravelLocationRequestDto
import com.pynapli.shared.network.dto.TopPicksResponseDto
import com.pynapli.shared.network.dto.TravelLocationDto
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType

/** Ktor implementation of [GridApi], composed into `KtorPynapliApiClient` via delegation. */
internal class KtorGridApi(
    private val support: KtorApiSupport,
) : GridApi {
    override suspend fun queryGrid(request: GridQueryRequestDto): AppResult<GridQueryResponseDto> =
        support.authenticated { token ->
            support.httpClient
                .post(support.endpoint("/v1/grid/query")) {
                    bearerAuth(token)
                    contentType(ContentType.Application.Json)
                    setBody(request)
                }.toAppResult(support.json)
        }

    override suspend fun listSavedGridFilters(): AppResult<SavedGridFiltersListDto> =
        support.authenticated { token ->
            support.httpClient
                .get(support.endpoint("/v1/me/grid-filters")) {
                    bearerAuth(token)
                }.toAppResult(support.json)
        }

    override suspend fun createSavedGridFilter(request: CreateSavedGridFilterRequestDto): AppResult<SavedGridFilterDto> =
        support.authenticated { token ->
            support.httpClient
                .post(support.endpoint("/v1/me/grid-filters")) {
                    bearerAuth(token)
                    contentType(ContentType.Application.Json)
                    setBody(request)
                }.toAppResult(support.json)
        }

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

    override suspend fun listSavedSwipeFilters(): AppResult<SavedSwipeFiltersListDto> =
        support.authenticated { token ->
            support.httpClient
                .get(support.endpoint("/v1/me/swipe-filters")) {
                    bearerAuth(token)
                }.toAppResult(support.json)
        }

    override suspend fun createSavedSwipeFilter(request: CreateSavedSwipeFilterRequestDto): AppResult<SavedSwipeFilterDto> =
        support.authenticated { token ->
            support.httpClient
                .post(support.endpoint("/v1/me/swipe-filters")) {
                    bearerAuth(token)
                    contentType(ContentType.Application.Json)
                    setBody(request)
                }.toAppResult(support.json)
        }

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

    override suspend fun getTopPicks(): AppResult<TopPicksResponseDto> =
        support.authenticated { token ->
            support.httpClient
                .get(support.endpoint("/v1/swipe/picks")) {
                    bearerAuth(token)
                }.toAppResult(support.json)
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

    override suspend fun setTravelLocation(request: SetTravelLocationRequestDto): AppResult<TravelLocationDto> =
        support.authenticated { token ->
            support.httpClient
                .post(support.endpoint("/v1/location/travel")) {
                    bearerAuth(token)
                    contentType(ContentType.Application.Json)
                    setBody(request)
                }.toAppResult(support.json)
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
}
