package com.pynapli.server.verification

import com.pynapli.server.safety.VerificationRiskSignalPublisher
import com.pynapli.shared.network.dto.AdminAgeVerificationReviewRequestDto
import com.pynapli.shared.network.dto.AgeVerificationCallbackRequestDto
import com.pynapli.shared.network.dto.AgeVerificationStatusDto
import java.time.Clock
import java.util.UUID

internal class AgeVerificationReviewActions(
    private val repository: AgeVerificationRepository,
    private val clock: Clock,
    private val verificationRiskSignals: VerificationRiskSignalPublisher?,
) {
    suspend fun review(
        verificationIdText: String,
        request: AdminAgeVerificationReviewRequestDto,
        actorId: String,
    ): AgeVerificationStatusDto {
        val verificationId = verificationIdText.toUuidOrThrow()
        val normalizedStatus = request.status.normalizeReviewStatus()
        val now = clock.instant()
        val record =
            repository.review(
                verificationId = verificationId,
                status = normalizedStatus,
                verifiedAt = now.takeIf { normalizedStatus == AGE_STATUS_VERIFIED },
                expiresAt = null,
                reviewerId = actorId,
                reason = request.reason.cleanReason(),
                reviewedAt = now,
            ) ?: throw VerificationException("VERIFICATION_NOT_FOUND")
        if (normalizedStatus == AGE_STATUS_VERIFIED) {
            publishVerifiedRiskSignal(record.userId)
        }
        return record.toDto(if (normalizedStatus == AGE_STATUS_VERIFIED) now else null)
    }

    suspend fun callback(
        request: AgeVerificationCallbackRequestDto,
        actorId: String,
        fallbackReason: String?,
    ): AgeVerificationStatusDto {
        val normalizedStatus = request.status.normalizeReviewStatus()
        val provider = request.provider.cleanProvider()
        val providerReference = request.providerReference?.trim()?.takeIf { it.isNotEmpty() }
        val existing = providerReference?.let { repository.findByProviderReference(provider, it) }
        val userId =
            existing?.userId ?: request.userId?.toUuidOrThrow()
                ?: throw VerificationException("INVALID_VERIFICATION_CALLBACK")
        val profile = repository.getProfileState(userId) ?: throw VerificationException("PROFILE_NOT_FOUND")
        val verificationId = existing?.id ?: request.verificationId?.toUuidOrThrow() ?: UUID.randomUUID()
        val verifiedAt =
            request.verifiedAt?.toInstantOrThrow()
                ?: clock.instant().takeIf { normalizedStatus == AGE_STATUS_VERIFIED }
        val expiresAt = request.expiresAt?.toInstantOrThrow()

        val record =
            repository.recordCallback(
                verificationId = verificationId,
                userId = userId,
                market = AGE_DEFAULT_MARKET,
                method = AGE_METHOD_PROVIDER_CALLBACK,
                provider = provider,
                providerReference = providerReference,
                status = normalizedStatus,
                verifiedAt = verifiedAt,
                expiresAt = expiresAt,
                reviewerId = actorId,
                reason = request.reason.cleanReason() ?: fallbackReason.cleanReason(),
                reviewedAt = clock.instant(),
            )
        if (normalizedStatus == AGE_STATUS_VERIFIED) {
            publishVerifiedRiskSignal(userId)
        }
        return record.toDto(if (normalizedStatus == AGE_STATUS_VERIFIED) verifiedAt else profile.ageVerifiedAt)
    }

    // Verification lowers risk (TRUST_AND_SAFETY_SPEC); a risk-path failure must
    // never break the verification outcome itself.
    private suspend fun publishVerifiedRiskSignal(userId: UUID) {
        val publisher = verificationRiskSignals ?: return
        runCatching { publisher.recordAgeVerificationPassed(userId) }
    }
}
