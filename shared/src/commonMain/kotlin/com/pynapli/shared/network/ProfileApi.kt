package com.pynapli.shared.network

import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.network.dto.MeDto
import com.pynapli.shared.network.dto.PublicProfileDto
import com.pynapli.shared.network.dto.UpdateDiscoverySettingsRequestDto
import com.pynapli.shared.network.dto.UpdateProfileRequestDto

/**
 * Profile slice of the API surface, split out of the monolithic [PynapliApiClient]
 * (which now composes the per-feature interfaces). Covers the current user (`me`),
 * profile + discovery-settings updates, and public profile lookup. Signatures are unchanged.
 */
interface ProfileApi {
    suspend fun getMe(): AppResult<MeDto>

    suspend fun updateProfile(request: UpdateProfileRequestDto): AppResult<MeDto>

    suspend fun updateDiscoverySettings(request: UpdateDiscoverySettingsRequestDto): AppResult<MeDto>

    suspend fun getPublicProfile(userId: String): AppResult<PublicProfileDto>
}
