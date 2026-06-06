package com.pynapli.shared.network

import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.network.dto.CurrentLocationDto
import com.pynapli.shared.network.dto.UpdateCurrentLocationRequestDto
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType

/** Ktor implementation of [LocationApi], composed into `KtorPynapliApiClient` via delegation. */
internal class KtorLocationApi(
    private val support: KtorApiSupport,
) : LocationApi {
    override suspend fun updateCurrentLocation(request: UpdateCurrentLocationRequestDto): AppResult<CurrentLocationDto> =
        support.authenticated { token ->
            support.httpClient
                .post(support.endpoint("/v1/location/current")) {
                    bearerAuth(token)
                    contentType(ContentType.Application.Json)
                    setBody(request)
                }.toAppResult(support.json)
        }
}
