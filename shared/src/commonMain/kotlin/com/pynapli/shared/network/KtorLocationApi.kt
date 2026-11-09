package com.pynapli.shared.network

import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.network.dto.CurrentLocationDto
import com.pynapli.shared.network.dto.UpdateCurrentLocationRequestDto
import io.ktor.http.HttpMethod

/** Ktor implementation of [LocationApi], composed into `KtorPynapliApiClient` via delegation. */
internal class KtorLocationApi(
    private val support: KtorApiSupport,
) : LocationApi {
    override suspend fun updateCurrentLocation(request: UpdateCurrentLocationRequestDto): AppResult<CurrentLocationDto> = support.authSend(HttpMethod.Post, "/v1/location/current", request)
}
