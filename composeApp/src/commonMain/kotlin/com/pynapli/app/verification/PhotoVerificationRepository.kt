package com.pynapli.app.verification

import com.pynapli.app.media.PickedMediaForUpload
import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.network.MediaByteUploader
import com.pynapli.shared.network.PynapliApiClient
import com.pynapli.shared.network.dto.CreatePhotoVerificationUploadRequestDto
import com.pynapli.shared.network.dto.PhotoVerificationStatusDto
import com.pynapli.shared.network.dto.SubmitPhotoVerificationRequestDto

/** Selfie photo verification, server-owned end to end (TRUST_AND_SAFETY_SPEC "Photo Verification"). */
interface PhotoVerificationRepository {
    suspend fun loadStatus(): AppResult<PhotoVerificationStatusDto>

    suspend fun requestChallenge(): AppResult<PhotoVerificationStatusDto>

    /** Opens the selfie upload slot, uploads the bytes, then submits it for the [challengeId] pose. */
    suspend fun submitSelfie(
        challengeId: String,
        media: PickedMediaForUpload,
    ): AppResult<PhotoVerificationStatusDto>
}

class ApiPhotoVerificationRepository(
    private val apiClient: PynapliApiClient,
    private val mediaByteUploader: MediaByteUploader,
) : PhotoVerificationRepository {
    override suspend fun loadStatus(): AppResult<PhotoVerificationStatusDto> = apiClient.getPhotoVerificationStatus()

    override suspend fun requestChallenge(): AppResult<PhotoVerificationStatusDto> = apiClient.requestPhotoVerificationChallenge()

    override suspend fun submitSelfie(
        challengeId: String,
        media: PickedMediaForUpload,
    ): AppResult<PhotoVerificationStatusDto> {
        val slot =
            when (
                val result =
                    apiClient.createPhotoVerificationUpload(
                        CreatePhotoVerificationUploadRequestDto(
                            challengeId = challengeId,
                            contentType = media.contentType,
                            byteSize = media.byteSize,
                            pixelWidth = media.pixelWidth,
                            pixelHeight = media.pixelHeight,
                        ),
                    )
            ) {
                is AppResult.Success -> result.value
                is AppResult.Failure -> return result
            }
        when (val uploaded = mediaByteUploader.upload(slot.uploadUrl, slot.uploadHeaders, media.bytes)) {
            is AppResult.Success -> Unit
            is AppResult.Failure -> return uploaded
        }
        return apiClient.submitPhotoVerification(
            SubmitPhotoVerificationRequestDto(challengeId = challengeId, mediaAssetId = slot.mediaAssetId),
        )
    }
}
