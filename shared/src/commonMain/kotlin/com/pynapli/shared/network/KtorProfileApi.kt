package com.pynapli.shared.network

import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.network.dto.MeDto
import com.pynapli.shared.network.dto.PublicProfileDto
import com.pynapli.shared.network.dto.UpdateDiscoverySettingsRequestDto
import com.pynapli.shared.network.dto.UpdateProfileRequestDto
import io.ktor.client.request.get
import io.ktor.http.HttpMethod

/** Ktor implementation of [ProfileApi], composed into `KtorPynapliApiClient` via delegation. */
internal class KtorProfileApi(
    private val support: KtorApiSupport,
) : ProfileApi {
    override suspend fun getMe(): AppResult<MeDto> = support.authGet("/v1/me")

    override suspend fun updateProfile(request: UpdateProfileRequestDto): AppResult<MeDto> = support.authSend(HttpMethod.Patch, "/v1/me/profile", request)

    override suspend fun updateDiscoverySettings(request: UpdateDiscoverySettingsRequestDto): AppResult<MeDto> = support.authSend(HttpMethod.Patch, "/v1/me/discovery-settings", request)

    override suspend fun getPublicProfile(userId: String): AppResult<PublicProfileDto> = support.authGet("/v1/profiles/$userId")
}
