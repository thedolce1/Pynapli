package com.pynapli.server.verification

import kotlinx.serialization.Serializable

@Serializable
data class AdminPhotoVerificationQueueDto(
    val items: List<AdminPhotoVerificationItemDto>,
    val nextCursor: String? = null,
)

/**
 * One selfie waiting for review. [selfieUrl] and [profilePhotoUrls] are short-lived signed URLs for
 * the reviewer only; compare the selfie's pose with [instruction] and its face with the profile photos.
 */
@Serializable
data class AdminPhotoVerificationItemDto(
    val verificationId: String,
    val userId: String,
    val displayName: String? = null,
    val poseKey: String,
    val instruction: String,
    val submittedAt: String,
    val faceCount: Int? = null,
    val selfieUrl: String? = null,
    val profilePhotoUrls: List<String> = emptyList(),
)

@Serializable
data class AdminPhotoVerificationDecisionRequestDto(
    // Required to reject or revoke (the user sees it); optional note when approving.
    val reason: String? = null,
)

@Serializable
data class AdminPhotoVerificationDecisionDto(
    val userId: String,
    val status: String,
    val decidedAt: String,
    // Null only when revoking a verification that no recorded attempt approved (e.g. set by support).
    val verificationId: String? = null,
)
