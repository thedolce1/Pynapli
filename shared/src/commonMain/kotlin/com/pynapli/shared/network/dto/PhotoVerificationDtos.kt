package com.pynapli.shared.network.dto

import kotlinx.serialization.Serializable

/**
 * Selfie photo verification (TRUST_AND_SAFETY_SPEC "Photo verification"). The server issues a pose
 * challenge, the user uploads one selfie doing that pose, and a reviewer decides. Status values are
 * [PhotoVerificationStatus].
 */
object PhotoVerificationStatus {
    /** Never tried, or the last challenge lapsed: the user can ask for a challenge. */
    const val NONE = "none"

    /** A challenge is open: take the selfie before [PhotoVerificationChallengeDto.expiresAt]. */
    const val CHALLENGE = "challenge"

    /** A selfie is waiting for review. */
    const val PENDING = "pending"

    /** Verified: the profile shows the verified badge. */
    const val VERIFIED = "verified"

    /** The last selfie was not accepted; see the reason and when a retry is allowed. */
    const val REJECTED = "rejected"
}

@Serializable
data class PhotoVerificationStatusDto(
    val status: String,
    val challenge: PhotoVerificationChallengeDto? = null,
    val submittedAt: String? = null,
    val verifiedAt: String? = null,
    // User-visible reason for the last rejection (or a removed verification).
    val rejectionReason: String? = null,
    // When a new challenge may be requested after a rejection (ISO-8601).
    val retryAvailableAt: String? = null,
)

@Serializable
data class PhotoVerificationChallengeDto(
    val challengeId: String,
    val poseKey: String,
    val instruction: String,
    val expiresAt: String,
)

@Serializable
data class CreatePhotoVerificationUploadRequestDto(
    val challengeId: String,
    val contentType: String,
    val byteSize: Long,
    val pixelWidth: Int? = null,
    val pixelHeight: Int? = null,
)

@Serializable
data class PhotoVerificationUploadSlotDto(
    val mediaAssetId: String,
    val uploadUrl: String,
    val uploadMethod: String,
    val uploadHeaders: Map<String, String> = emptyMap(),
    val expiresAt: String,
)

@Serializable
data class SubmitPhotoVerificationRequestDto(
    val challengeId: String,
    val mediaAssetId: String,
)
