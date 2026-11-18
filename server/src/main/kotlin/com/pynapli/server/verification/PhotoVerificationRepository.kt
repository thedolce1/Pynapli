package com.pynapli.server.verification

import java.time.Instant
import java.util.UUID

interface PhotoVerificationRepository {
    /** `profiles.face_photo_verified_at`, or null when the user is not photo-verified. */
    suspend fun facePhotoVerifiedAt(userId: UUID): Instant?

    suspend fun find(verificationId: UUID): PhotoVerificationRecord?

    /** The user's most recent attempt (any status), or null. */
    suspend fun latest(userId: UUID): PhotoVerificationRecord?

    /**
     * Closes any open challenge and records a new one. Throws `PHOTO_VERIFICATION_PENDING` when a
     * submission is already waiting for review (a concurrent submit can win the race).
     */
    suspend fun issueChallenge(
        verificationId: UUID,
        userId: UUID,
        poseKey: String,
        challengeExpiresAt: Instant,
    ): PhotoVerificationRecord

    /** The user's attempt [verificationId] while it is still an open (not yet submitted) challenge. */
    suspend fun findOpenChallenge(
        userId: UUID,
        verificationId: UUID,
    ): PhotoVerificationRecord?

    /** A selfie asset the user uploaded for verification and has not submitted with any attempt. */
    suspend fun findUnusedSelfie(
        userId: UUID,
        mediaAssetId: UUID,
    ): StoredSelfie?

    /** Moves an open challenge to `pending` with its selfie. Null when the challenge is no longer open. */
    suspend fun submit(
        userId: UUID,
        verificationId: UUID,
        mediaAssetId: UUID,
        submittedAt: Instant,
    ): PhotoVerificationRecord?

    suspend fun recordFaceCount(
        verificationId: UUID,
        faceCount: Int,
    )

    /** Rejects a pending attempt automatically (face check). Null when it is no longer pending. */
    suspend fun rejectAutomatically(
        verificationId: UUID,
        faceCount: Int,
        reason: String,
        decidedAt: Instant,
        retryAvailableAt: Instant,
    ): PhotoVerificationRecord?

    /** Oldest-first page of attempts waiting for review, strictly after [after] when given. */
    suspend fun listPending(
        limit: Int,
        after: PhotoVerificationQueueCursor?,
    ): List<PendingPhotoVerification>

    suspend fun recordQueueView(
        actorId: String,
        reason: String,
        itemCount: Int,
    )

    /**
     * Approves a pending attempt and marks the profile photo-verified (face_photo_verified_at and
     * verified_at), with an audit row, in one transaction. Null when it is not pending.
     */
    suspend fun approve(
        verificationId: UUID,
        reviewerId: String,
        reason: String?,
        decidedAt: Instant,
    ): PhotoVerificationRecord?

    /** Rejects a pending attempt with a user-visible reason and an audit row. Null when not pending. */
    suspend fun reject(
        verificationId: UUID,
        reviewerId: String,
        reason: String,
        decidedAt: Instant,
        retryAvailableAt: Instant,
    ): PhotoVerificationRecord?

    /**
     * Removes a user's photo verification (clears both profile timestamps), marks the approving attempt
     * revoked with a user-visible reason, and audits it. Null when the user is not photo-verified.
     */
    suspend fun revoke(
        userId: UUID,
        reviewerId: String,
        reason: String,
        revokedAt: Instant,
        retryAvailableAt: Instant,
    ): PhotoVerificationRevocation?

    /**
     * Selfies to delete: those of attempts decided before [decidedBefore], and verification uploads
     * created before [abandonedBefore] that were never submitted.
     */
    suspend fun selfiesDueForPurge(
        decidedBefore: Instant,
        abandonedBefore: Instant,
        limit: Int,
    ): List<StoredSelfie>

    /** Deletes the selfie's asset row and stamps `selfie_purged_at` on the attempt that used it. */
    suspend fun deleteSelfie(
        mediaAssetId: UUID,
        purgedAt: Instant,
    )
}

data class PhotoVerificationRecord(
    val verificationId: UUID,
    val userId: UUID,
    val poseKey: String,
    val status: String,
    val challengeExpiresAt: Instant,
    val mediaAssetId: UUID?,
    val faceCount: Int?,
    val submittedAt: Instant?,
    val decidedAt: Instant?,
    val decisionSource: String?,
    val reviewerId: String?,
    val decisionReason: String?,
    val retryAvailableAt: Instant?,
    val createdAt: Instant,
)

/** A removed verification; [verificationId] is the attempt that had approved it, when one exists. */
data class PhotoVerificationRevocation(
    val userId: UUID,
    val verificationId: UUID?,
)

data class PendingPhotoVerification(
    val verificationId: UUID,
    val userId: UUID,
    val displayName: String?,
    val poseKey: String,
    val submittedAt: Instant,
    val faceCount: Int?,
    val selfieMediaAssetId: UUID?,
    val profilePhotoMediaAssetIds: List<UUID>,
)

data class PhotoVerificationQueueCursor(
    val submittedAt: Instant,
    val verificationId: UUID,
)

internal object PhotoVerificationRowStatus {
    const val CHALLENGED = "challenged"
    const val PENDING = "pending"
    const val APPROVED = "approved"
    const val REJECTED = "rejected"
    const val EXPIRED = "expired"
    const val REVOKED = "revoked"
}
