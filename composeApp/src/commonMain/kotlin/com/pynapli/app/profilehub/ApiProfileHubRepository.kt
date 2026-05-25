package com.pynapli.app.profilehub

import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.network.PynapliApiClient
import com.pynapli.shared.network.dto.AgeVerificationStatusDto
import com.pynapli.shared.network.dto.MeDto
import com.pynapli.shared.network.dto.StartAgeVerificationRequestDto
import com.pynapli.shared.network.dto.UpdateDiscoverySettingsRequestDto
import com.pynapli.shared.network.dto.UpdateProfileRequestDto

class ApiProfileHubRepository(
    private val apiClient: PynapliApiClient
) : ProfileHubRepository {
    override suspend fun loadMe(): AppResult<MeDto> =
        apiClient.getMe()

    override suspend fun loadAgeVerificationStatus(): AppResult<AgeVerificationStatusDto> =
        apiClient.getAgeVerificationStatus()

    override suspend fun startAgeVerification(
        request: StartAgeVerificationRequestDto
    ): AppResult<AgeVerificationStatusDto> =
        apiClient.startAgeVerification(request)

    override suspend fun updateProfile(request: UpdateProfileRequestDto): AppResult<MeDto> =
        apiClient.updateProfile(request)

    override suspend fun updateDiscoverySettings(request: UpdateDiscoverySettingsRequestDto): AppResult<MeDto> =
        apiClient.updateDiscoverySettings(request)
}
