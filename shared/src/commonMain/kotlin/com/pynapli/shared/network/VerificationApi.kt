package com.pynapli.shared.network

import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.network.dto.AgeVerificationRequirementsDto
import com.pynapli.shared.network.dto.AgeVerificationStatusDto
import com.pynapli.shared.network.dto.StartAgeVerificationRequestDto

/**
 * Age-verification slice of the API surface, split out of the monolithic [PynapliApiClient]
 * (which now composes the per-feature interfaces). Covers the verification requirements,
 * status, and start flow. Signatures are unchanged.
 */
interface VerificationApi {
    suspend fun getAgeVerificationRequirements(): AppResult<AgeVerificationRequirementsDto>

    suspend fun getAgeVerificationStatus(): AppResult<AgeVerificationStatusDto>

    suspend fun startAgeVerification(
        request: StartAgeVerificationRequestDto,
    ): AppResult<AgeVerificationStatusDto>
}
