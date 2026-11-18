package com.pynapli.server.verification

import com.pynapli.server.notifications.NotificationEventDraft
import com.pynapli.server.notifications.NotificationKind
import com.pynapli.server.notifications.NotificationPublisher
import com.pynapli.server.safety.RateLimitService
import com.pynapli.server.safety.RateLimitedAction
import com.pynapli.server.safety.VerificationRiskSignalPublisher
import com.pynapli.shared.network.dto.CreatePhotoVerificationUploadRequestDto
import com.pynapli.shared.network.dto.MEDIA_IMAGE_MAX_BYTES
import com.pynapli.shared.network.dto.PhotoVerificationChallengeDto
import com.pynapli.shared.network.dto.PhotoVerificationStatus
import com.pynapli.shared.network.dto.PhotoVerificationStatusDto
import com.pynapli.shared.network.dto.PhotoVerificationUploadSlotDto
import com.pynapli.shared.network.dto.SubmitPhotoVerificationRequestDto
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID
import kotlin.random.Random
import kotlin.random.asKotlinRandom

/**
 * Selfie photo verification (TRUST_AND_SAFETY_SPEC "Photo verification"): a random server-side pose
 * challenge, one private selfie through the media upload path, an optional face-count check, and a
 * human decision. Approval is the only thing that writes `profiles.face_photo_verified_at` /
 * `verified_at`; no automatic path ever approves.
 */
class PhotoVerificationService(
    private val repository: PhotoVerificationRepository,
    private val selfieMedia: SelfieMediaGateway,
    private val faceDetector: SelfieFaceDetector? = null,
    private val rateLimitService: RateLimitService? = null,
    private val notificationPublisher: NotificationPublisher? = null,
    private val clock: Clock = Clock.systemUTC(),
    private val poseRandom: Random = SecureRandom().asKotlinRandom(),
    // Approval lowers account risk once (TRUST_AND_SAFETY_SPEC "Photo verification reduces score").
    private val verificationRiskSignals: VerificationRiskSignalPublisher? = null,
) {
    suspend fun status(userId: UUID): PhotoVerificationStatusDto {
        repository.facePhotoVerifiedAt(userId)?.let { verifiedAt ->
            return PhotoVerificationStatusDto(status = PhotoVerificationStatus.VERIFIED, verifiedAt = verifiedAt.toString())
        }
        return repository.latest(userId)?.toStatusDto(clock.instant()) ?: PhotoVerificationStatusDto(status = PhotoVerificationStatus.NONE)
    }

    suspend fun requestChallenge(userId: UUID): PhotoVerificationStatusDto {
        enforceRateLimit(userId, RateLimitedAction.PHOTO_VERIFICATION_CHALLENGE)
        val now = clock.instant()
        if (repository.facePhotoVerifiedAt(userId) != null) throw VerificationException("PHOTO_VERIFICATION_ALREADY_VERIFIED")
        val latest = repository.latest(userId)
        if (latest?.status == PhotoVerificationRowStatus.PENDING) throw VerificationException("PHOTO_VERIFICATION_PENDING")
        if (latest != null && latest.status in RETRYABLE_STATUSES && latest.retryAvailableAt?.isAfter(now) == true) {
            throw VerificationException("PHOTO_VERIFICATION_COOLDOWN")
        }
        val pose = PhotoVerificationPoses.all.random(poseRandom)
        return repository
            .issueChallenge(
                verificationId = UUID.randomUUID(),
                userId = userId,
                poseKey = pose.key,
                challengeExpiresAt = now.plus(CHALLENGE_TTL),
            ).toStatusDto(now)
    }

    suspend fun createSelfieUpload(
        userId: UUID,
        request: CreatePhotoVerificationUploadRequestDto,
    ): PhotoVerificationUploadSlotDto {
        enforceRateLimit(userId, RateLimitedAction.MEDIA_UPLOAD)
        openChallenge(userId, request.challengeId)
        val contentType = request.contentType.trim().lowercase()
        if (contentType !in SELFIE_CONTENT_TYPES) throw VerificationException("INVALID_PHOTO_VERIFICATION_SELFIE")
        if (request.byteSize <= 0 || request.byteSize > MEDIA_IMAGE_MAX_BYTES) throw VerificationException("INVALID_PHOTO_VERIFICATION_SELFIE")
        if ((request.pixelWidth ?: 1) <= 0 || (request.pixelHeight ?: 1) <= 0) throw VerificationException("INVALID_PHOTO_VERIFICATION_SELFIE")
        return selfieMedia.createUploadSlot(
            ownerUserId = userId,
            contentType = contentType,
            byteSize = request.byteSize,
            pixelWidth = request.pixelWidth,
            pixelHeight = request.pixelHeight,
        )
    }

    suspend fun submit(
        userId: UUID,
        request: SubmitPhotoVerificationRequestDto,
    ): PhotoVerificationStatusDto {
        val challenge = openChallenge(userId, request.challengeId)
        val mediaAssetId = request.mediaAssetId.toUuidOrNull() ?: throw VerificationException("PHOTO_VERIFICATION_SELFIE_NOT_FOUND")
        val selfie = repository.findUnusedSelfie(userId, mediaAssetId) ?: throw VerificationException("PHOTO_VERIFICATION_SELFIE_NOT_FOUND")
        // Counted only once the attempt is real, so a stale challenge doesn't spend the daily budget.
        enforceRateLimit(userId, RateLimitedAction.PHOTO_VERIFICATION_SUBMIT)
        val now = clock.instant()
        val submitted =
            repository.submit(userId, challenge.verificationId, mediaAssetId, now)
                ?: throw VerificationException("PHOTO_VERIFICATION_CHALLENGE_NOT_FOUND")

        val faceCount = faceDetector?.countFaces(selfie) ?: return submitted.toStatusDto(now)
        if (faceCount == 1) {
            repository.recordFaceCount(submitted.verificationId, faceCount)
            return submitted.copy(faceCount = faceCount).toStatusDto(now)
        }
        val rejected =
            repository.rejectAutomatically(
                verificationId = submitted.verificationId,
                faceCount = faceCount,
                reason = if (faceCount == 0) NO_FACE_REASON else SEVERAL_FACES_REASON,
                decidedAt = now,
                retryAvailableAt = now.plus(FACE_CHECK_RETRY_COOLDOWN),
            ) ?: return submitted.toStatusDto(now)
        return rejected.toStatusDto(now)
    }

    suspend fun listPending(
        actorId: String,
        reason: String,
        limitText: String?,
        cursor: String?,
    ): AdminPhotoVerificationQueueDto {
        val limit = limitText?.toIntOrNull() ?: DEFAULT_QUEUE_LIMIT
        if (limitText != null && limitText.toIntOrNull() == null || limit !in 1..MAX_QUEUE_LIMIT) {
            throw VerificationException("INVALID_PHOTO_VERIFICATION_QUERY")
        }
        val after = cursor?.let(::decodeCursor)
        val rows = repository.listPending(limit + 1, after)
        val page = rows.take(limit)
        repository.recordQueueView(actorId = actorId, reason = reason, itemCount = page.size)
        return AdminPhotoVerificationQueueDto(
            items =
                page.map { row ->
                    AdminPhotoVerificationItemDto(
                        verificationId = row.verificationId.toString(),
                        userId = row.userId.toString(),
                        displayName = row.displayName,
                        poseKey = row.poseKey,
                        instruction = PhotoVerificationPoses.byKey(row.poseKey)?.instruction ?: row.poseKey,
                        submittedAt = row.submittedAt.toString(),
                        faceCount = row.faceCount,
                        selfieUrl = row.selfieMediaAssetId?.let(selfieMedia::reviewUrl),
                        profilePhotoUrls = row.profilePhotoMediaAssetIds.mapNotNull(selfieMedia::profilePhotoUrl),
                    )
                },
            nextCursor = if (rows.size > limit) page.last().let { encodeCursor(it.submittedAt, it.verificationId) } else null,
        )
    }

    suspend fun approve(
        verificationIdText: String,
        actorId: String,
        reason: String?,
    ): AdminPhotoVerificationDecisionDto {
        val verificationId = verificationIdText.toUuidOrNull() ?: throw VerificationException("INVALID_VERIFICATION_ID")
        val note = reason.cleanOptionalReason()
        val record =
            repository.approve(verificationId, actorId, note, clock.instant())
                ?: throw notPendingOrMissing(verificationId)
        publishVerifiedRiskSignal(record.userId)
        notifyDecision(record.userId, record.verificationId, "approved")
        return record.toDecisionDto()
    }

    suspend fun reject(
        verificationIdText: String,
        actorId: String,
        reason: String?,
    ): AdminPhotoVerificationDecisionDto {
        val verificationId = verificationIdText.toUuidOrNull() ?: throw VerificationException("INVALID_VERIFICATION_ID")
        val userReason = reason.requiredReason()
        val now = clock.instant()
        val record =
            repository.reject(verificationId, actorId, userReason, now, now.plus(ADMIN_REJECT_RETRY_COOLDOWN))
                ?: throw notPendingOrMissing(verificationId)
        notifyDecision(record.userId, record.verificationId, "rejected")
        return record.toDecisionDto()
    }

    suspend fun revoke(
        userIdText: String,
        actorId: String,
        reason: String?,
    ): AdminPhotoVerificationDecisionDto {
        val userId = userIdText.toUuidOrNull() ?: throw VerificationException("INVALID_USER_ID")
        val userReason = reason.requiredReason()
        val now = clock.instant()
        val revocation =
            repository.revoke(userId, actorId, userReason, now, now.plus(ADMIN_REJECT_RETRY_COOLDOWN))
                ?: throw VerificationException("PHOTO_VERIFICATION_NOT_VERIFIED")
        notifyDecision(userId, revocation.verificationId ?: userId, "revoked")
        return AdminPhotoVerificationDecisionDto(
            userId = userId.toString(),
            status = PhotoVerificationRowStatus.REVOKED,
            decidedAt = now.toString(),
            verificationId = revocation.verificationId?.toString(),
        )
    }

    /**
     * Retention: deletes selfie objects and their asset rows [SELFIE_RETENTION] after a decision, and
     * uploads never submitted with an attempt after [ABANDONED_SELFIE_GRACE]. The attempt row (pose,
     * decision, reviewer, timestamps) stays for audit. Returns how many selfies were removed.
     */
    suspend fun purgeExpiredSelfies(): Int {
        val now = clock.instant()
        // Submissions nobody reviewed within UNREVIEWED_SELFIE_RETENTION are closed (the person can
        // try again) and their selfie goes now, rather than sitting in storage indefinitely.
        val unreviewed =
            repository.expireUnreviewed(
                submittedBefore = now.minus(UNREVIEWED_SELFIE_RETENTION),
                decidedAt = now,
                limit = PURGE_BATCH_SIZE,
            )
        val due =
            unreviewed +
                repository.selfiesDueForPurge(
                    decidedBefore = now.minus(SELFIE_RETENTION),
                    abandonedBefore = now.minus(ABANDONED_SELFIE_GRACE),
                    limit = PURGE_BATCH_SIZE,
                )
        var purged = 0
        for (selfie in due.distinctBy { it.mediaAssetId }) {
            if (selfieMedia.deleteStoredObject(selfie)) {
                repository.deleteSelfie(selfie.mediaAssetId, now)
                purged++
            }
        }
        return purged
    }

    private suspend fun openChallenge(
        userId: UUID,
        challengeIdText: String,
    ): PhotoVerificationRecord {
        val challengeId = challengeIdText.toUuidOrNull() ?: throw VerificationException("PHOTO_VERIFICATION_CHALLENGE_NOT_FOUND")
        val challenge =
            repository.findOpenChallenge(userId, challengeId)
                ?: throw VerificationException("PHOTO_VERIFICATION_CHALLENGE_NOT_FOUND")
        if (!challenge.challengeExpiresAt.isAfter(clock.instant())) throw VerificationException("PHOTO_VERIFICATION_CHALLENGE_EXPIRED")
        return challenge
    }

    private suspend fun notPendingOrMissing(verificationId: UUID): VerificationException =
        if (repository.find(verificationId) == null) {
            VerificationException("VERIFICATION_NOT_FOUND")
        } else {
            VerificationException("PHOTO_VERIFICATION_NOT_PENDING")
        }

    private suspend fun enforceRateLimit(
        userId: UUID,
        action: String,
    ) {
        if (rateLimitService?.check(userId, action)?.allowed == false) throw VerificationException("RATE_LIMITED")
    }

    // Verification lowers risk (TRUST_AND_SAFETY_SPEC), as age verification does; a risk-path failure
    // must never break the verification decision itself.
    private suspend fun publishVerifiedRiskSignal(userId: UUID) {
        val publisher = verificationRiskSignals ?: return
        try {
            publisher.recordPhotoVerificationPassed(userId)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            logger.warn("Photo verification risk signal failed: {}", error.javaClass.simpleName)
        }
    }

    // A push is a courtesy on top of the decision; it must never undo or fail it.
    private suspend fun notifyDecision(
        userId: UUID,
        subjectId: UUID,
        outcome: String,
    ) {
        val publisher = notificationPublisher ?: return
        try {
            publisher.enqueue(
                NotificationEventDraft(
                    recipientUserId = userId,
                    actorUserId = null,
                    kind = NotificationKind.SYSTEM_SAFETY,
                    subjectId = subjectId,
                    idempotencyKey = "photo_verification:$subjectId:$outcome",
                    payload = mapOf("event" to "photo_verification_$outcome"),
                ),
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            logger.warn("Photo verification notification failed: {}", error.javaClass.simpleName)
        }
    }

    private fun PhotoVerificationRecord.toStatusDto(now: Instant): PhotoVerificationStatusDto =
        when (status) {
            PhotoVerificationRowStatus.CHALLENGED ->
                if (challengeExpiresAt.isAfter(now)) {
                    PhotoVerificationStatusDto(
                        status = PhotoVerificationStatus.CHALLENGE,
                        challenge =
                            PhotoVerificationChallengeDto(
                                challengeId = verificationId.toString(),
                                poseKey = poseKey,
                                instruction = PhotoVerificationPoses.byKey(poseKey)?.instruction ?: poseKey,
                                expiresAt = challengeExpiresAt.toString(),
                            ),
                    )
                } else {
                    PhotoVerificationStatusDto(status = PhotoVerificationStatus.NONE)
                }
            PhotoVerificationRowStatus.PENDING ->
                PhotoVerificationStatusDto(status = PhotoVerificationStatus.PENDING, submittedAt = submittedAt?.toString())
            PhotoVerificationRowStatus.REJECTED, PhotoVerificationRowStatus.REVOKED ->
                PhotoVerificationStatusDto(
                    status = PhotoVerificationStatus.REJECTED,
                    submittedAt = submittedAt?.toString(),
                    rejectionReason = decisionReason,
                    retryAvailableAt = retryAvailableAt?.toString(),
                )
            // Approved but the profile is no longer verified (cleared outside this flow), or expired.
            else -> PhotoVerificationStatusDto(status = PhotoVerificationStatus.NONE)
        }

    private fun PhotoVerificationRecord.toDecisionDto(): AdminPhotoVerificationDecisionDto =
        AdminPhotoVerificationDecisionDto(
            userId = userId.toString(),
            status = status,
            decidedAt = (decidedAt ?: clock.instant()).toString(),
            verificationId = verificationId.toString(),
        )

    private fun String?.cleanOptionalReason(): String? {
        val cleaned = this?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (cleaned.length > MAX_REASON_LENGTH) throw VerificationException("VERIFICATION_REASON_TOO_LONG")
        return cleaned
    }

    private fun String?.requiredReason(): String = cleanOptionalReason() ?: throw VerificationException("PHOTO_VERIFICATION_REASON_REQUIRED")

    private fun encodeCursor(
        submittedAt: Instant,
        verificationId: UUID,
    ): String = Base64.getUrlEncoder().withoutPadding().encodeToString("$submittedAt|$verificationId".toByteArray(Charsets.UTF_8))

    private fun decodeCursor(cursor: String): PhotoVerificationQueueCursor =
        runCatching {
            val (submittedAt, id) = String(Base64.getUrlDecoder().decode(cursor), Charsets.UTF_8).split('|', limit = 2)
            PhotoVerificationQueueCursor(Instant.parse(submittedAt), UUID.fromString(id))
        }.getOrElse { throw VerificationException("INVALID_PHOTO_VERIFICATION_QUERY") }

    private fun String.toUuidOrNull(): UUID? = runCatching { UUID.fromString(trim()) }.getOrNull()

    companion object {
        /** How long a pose challenge stays valid. */
        val CHALLENGE_TTL: Duration = Duration.ofMinutes(10)

        /** Wait after a reviewer rejects a selfie or removes a verification (founder-adjustable). */
        val ADMIN_REJECT_RETRY_COOLDOWN: Duration = Duration.ofHours(24)

        /** Wait after the face check rejects a selfie: usually a framing mistake, so short. */
        val FACE_CHECK_RETRY_COOLDOWN: Duration = Duration.ofMinutes(10)

        /** How long a decided selfie is kept for appeals and audit before it is deleted. */
        val SELFIE_RETENTION: Duration = Duration.ofDays(7)

        /**
         * A submission still unreviewed after this is closed as expired and its selfie deleted, so a
         * neglected queue never keeps face photos indefinitely. The person can submit again.
         */
        val UNREVIEWED_SELFIE_RETENTION: Duration = Duration.ofDays(30)

        /** Uploaded selfies never submitted with an attempt are deleted after this. */
        val ABANDONED_SELFIE_GRACE: Duration = Duration.ofHours(1)

        const val MAX_REASON_LENGTH = 280
        private const val DEFAULT_QUEUE_LIMIT = 20
        private const val MAX_QUEUE_LIMIT = 100
        private const val PURGE_BATCH_SIZE = 200
        private val RETRYABLE_STATUSES = setOf(PhotoVerificationRowStatus.REJECTED, PhotoVerificationRowStatus.REVOKED)
        private val SELFIE_CONTENT_TYPES = setOf("image/jpeg", "image/png", "image/webp")
        private const val NO_FACE_REASON =
            "We couldn't find a face in your selfie. Try again in good light with your whole face in view."
        private const val SEVERAL_FACES_REASON =
            "We found more than one face in your selfie. Take it on your own and try again."
        private val logger = LoggerFactory.getLogger(PhotoVerificationService::class.java)
    }
}
