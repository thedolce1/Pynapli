package com.pynapli.shared.network

import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.network.dto.AgeVerificationRequirementsDto
import com.pynapli.shared.network.dto.AgeVerificationStatusDto
import com.pynapli.shared.network.dto.CreatePhotoVerificationUploadRequestDto
import com.pynapli.shared.network.dto.PhotoVerificationStatusDto
import com.pynapli.shared.network.dto.PhotoVerificationUploadSlotDto
import com.pynapli.shared.network.dto.StartAgeVerificationRequestDto
import com.pynapli.shared.network.dto.SubmitPhotoVerificationRequestDto

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

    /** Selfie photo verification status (the verified badge). */
    suspend fun getPhotoVerificationStatus(): AppResult<PhotoVerificationStatusDto>

    /** Asks the server for a pose challenge (valid 10 minutes). */
    suspend fun requestPhotoVerificationChallenge(): AppResult<PhotoVerificationStatusDto>

    /** Opens a signed upload slot for the selfie; upload the bytes with [MediaByteUploader]. */
    suspend fun createPhotoVerificationUpload(
        request: CreatePhotoVerificationUploadRequestDto,
    ): AppResult<PhotoVerificationUploadSlotDto>

    /** Submits the uploaded selfie for review. Not idempotent: on failure refetch the status. */
    suspend fun submitPhotoVerification(
        request: SubmitPhotoVerificationRequestDto,
    ): AppResult<PhotoVerificationStatusDto>
}
