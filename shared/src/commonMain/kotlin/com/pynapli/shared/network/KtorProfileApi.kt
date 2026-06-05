package com.pynapli.shared.network

import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.network.dto.MeDto
import com.pynapli.shared.network.dto.PublicProfileDto
import com.pynapli.shared.network.dto.UpdateDiscoverySettingsRequestDto
import com.pynapli.shared.network.dto.UpdateProfileRequestDto
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType

/** Ktor implementation of [ProfileApi], composed into `KtorPynapliApiClient` via delegation. */
internal class KtorProfileApi(
    private val support: KtorApiSupport,
) : ProfileApi {
    override suspend fun getMe(): AppResult<MeDto> =
        support.authenticated { token ->
            support.httpClient
                .get(support.endpoint("/v1/me")) {
                    bearerAuth(token)
                }.toAppResult(support.json)
        }

    override suspend fun updateProfile(request: UpdateProfileRequestDto): AppResult<MeDto> =
        support.authenticated { token ->
            support.httpClient
                .patch(support.endpoint("/v1/me/profile")) {
                    bearerAuth(token)
                    contentType(ContentType.Application.Json)
                    setBody(request)
                }.toAppResult(support.json)
        }

    override suspend fun updateDiscoverySettings(request: UpdateDiscoverySettingsRequestDto): AppResult<MeDto> =
        support.authenticated { token ->
            support.httpClient
                .patch(support.endpoint("/v1/me/discovery-settings")) {
                    bearerAuth(token)
                    contentType(ContentType.Application.Json)
                    setBody(request)
                }.toAppResult(support.json)
        }

    override suspend fun getPublicProfile(userId: String): AppResult<PublicProfileDto> =
        support.authenticated { token ->
            support.httpClient
                .get(support.endpoint("/v1/profiles/$userId")) {
                    bearerAuth(token)
                }.toAppResult(support.json)
        }
}
