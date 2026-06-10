package com.pynapli.server.verification

import com.pynapli.server.safety.RateLimitService
import com.pynapli.server.safety.RateLimitedAction
import com.pynapli.shared.network.dto.AdminAgeVerificationReviewRequestDto
import com.pynapli.shared.network.dto.AgeVerificationCallbackRequestDto
import com.pynapli.shared.network.dto.AgeVerificationMethodDto
import com.pynapli.shared.network.dto.AgeVerificationRequirementsDto
import com.pynapli.shared.network.dto.AgeVerificationStatusDto
import com.pynapli.shared.network.dto.StartAgeVerificationRequestDto
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.Period
import java.util.UUID

class AgeVerificationService(
    private val repository: AgeVerificationRepository,
    private val clock: Clock = Clock.systemUTC(),
    private val provider: AgeVerificationProvider? = null,
    private val rateLimitService: RateLimitService? = null,
    private val marketPolicy: AgeVerificationMarketPolicy = BaselineAgeVerificationMarketPolicy,
) {
    suspend fun requirements(
        userId: UUID,
        market: String? = null,
    ): AgeVerificationRequirementsDto {
        val profile = repository.getProfileState(userId) ?: throw VerificationException("PROFILE_NOT_FOUND")
        val latest = repository.latestForUser(userId)
        val enhancedRequired = enhancedRequiredFor(market.cleanMarket())
        val acceptedMethods =
            buildList {
                // Enhanced markets accept only the provider method; manual review
                // would not satisfy the legal requirement there.
                if (!enhancedRequired) add(AgeVerificationMethodDto(METHOD_MANUAL_REVIEW, "Manual review"))
                provider?.let { add(AgeVerificationMethodDto(it.methodKey, it.label)) }
            }
        return AgeVerificationRequirementsDto(
            required = profile.ageVerifiedAt == null,
            enhancedRequired = enhancedRequired,
            acceptedMethods = acceptedMethods,
            status =
                latest?.toDto(profile.ageVerifiedAt)
                    ?: profile.ageVerifiedAt?.let { verifiedProfileStatus(it) },
        )
    }

    suspend fun status(userId: UUID): AgeVerificationStatusDto {
        val profile = repository.getProfileState(userId) ?: throw VerificationException("PROFILE_NOT_FOUND")
        return repository.latestForUser(userId)?.toDto(profile.ageVerifiedAt)
            ?: profile.ageVerifiedAt?.let { verifiedProfileStatus(it) }
            ?: AgeVerificationStatusDto(
                status = STATUS_NOT_STARTED,
                message = "Age verification has not started.",
            )
    }

    suspend fun start(
        userId: UUID,
        request: StartAgeVerificationRequestDto,
    ): AgeVerificationStatusDto {
        if (rateLimitService?.check(userId, RateLimitedAction.AGE_VERIFICATION_START)?.allowed == false) {
            throw VerificationException("RATE_LIMITED")
        }
        val profile = repository.getProfileState(userId) ?: throw VerificationException("PROFILE_NOT_FOUND")
        val latest = repository.latestForUser(userId)

        profile.ageVerifiedAt?.let { verifiedAt ->
            return latest?.toDto(verifiedAt) ?: verifiedProfileStatus(verifiedAt)
        }

        if (!request.confirmedAdult) throw VerificationException("ADULT_CONFIRMATION_REQUIRED")
        val birthdate = profile.birthdate ?: throw VerificationException("BIRTHDATE_REQUIRED")
        if (!isAdult(birthdate)) throw VerificationException("AGE_RESTRICTED")

        if (latest?.status == STATUS_PENDING) return latest.toDto(profile.ageVerifiedAt)

        val method =
            request.method
                ?.trim()
                ?.lowercase()
                ?.takeIf { it.isNotEmpty() } ?: METHOD_MANUAL_REVIEW
        val market = request.market.cleanMarket()

        if (method == METHOD_MANUAL_REVIEW && enhancedRequiredFor(market)) {
            throw VerificationException("ENHANCED_VERIFICATION_REQUIRED")
        }

        if (provider != null && method == provider.methodKey) {
            val session = provider.startSession(userId, market)
            val record =
                repository.createPending(
                    verificationId = UUID.randomUUID(),
                    userId = userId,
                    market = market,
                    method = method,
                    provider = provider.providerName,
                    providerReference = session.providerReference,
                    requestedAt = clock.instant(),
                )
            return record
                .toDto(profile.ageVerifiedAt)
                .copy(
                    redirectUrl = session.redirectUrl,
                    providerReference = session.providerReference,
                )
        }

        if (method != METHOD_MANUAL_REVIEW) throw VerificationException("INVALID_VERIFICATION_METHOD")

        val record =
            repository.createPending(
                verificationId = UUID.randomUUID(),
                userId = userId,
                market = market,
                method = method,
                provider = PROVIDER_INTERNAL_MANUAL,
                providerReference = null,
                requestedAt = clock.instant(),
            )
        return record.toDto(profile.ageVerifiedAt)
    }

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
                verifiedAt = now.takeIf { normalizedStatus == STATUS_VERIFIED },
                expiresAt = null,
                reviewerId = actorId,
                reason = request.reason.cleanReason(),
                reviewedAt = now,
            ) ?: throw VerificationException("VERIFICATION_NOT_FOUND")
        return record.toDto(if (normalizedStatus == STATUS_VERIFIED) now else null)
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
        val userId = existing?.userId ?: request.userId?.toUuidOrThrow() ?: throw VerificationException("INVALID_VERIFICATION_CALLBACK")
        val profile = repository.getProfileState(userId) ?: throw VerificationException("PROFILE_NOT_FOUND")
        val verificationId = existing?.id ?: request.verificationId?.toUuidOrThrow() ?: UUID.randomUUID()
        val verifiedAt =
            request.verifiedAt?.toInstantOrThrow()
                ?: clock.instant().takeIf { normalizedStatus == STATUS_VERIFIED }
        val expiresAt = request.expiresAt?.toInstantOrThrow()

        val record =
            repository.recordCallback(
                verificationId = verificationId,
                userId = userId,
                market = DEFAULT_MARKET,
                method = METHOD_PROVIDER_CALLBACK,
                provider = provider,
                providerReference = providerReference,
                status = normalizedStatus,
                verifiedAt = verifiedAt,
                expiresAt = expiresAt,
                reviewerId = actorId,
                reason = request.reason.cleanReason() ?: fallbackReason.cleanReason(),
                reviewedAt = clock.instant(),
            )
        return record.toDto(if (normalizedStatus == STATUS_VERIFIED) verifiedAt else profile.ageVerifiedAt)
    }

    private fun AgeVerificationRecord.toDto(profileAgeVerifiedAt: Instant?): AgeVerificationStatusDto {
        val effectiveVerifiedAt = verifiedAt ?: profileAgeVerifiedAt
        val effectiveStatus = if (effectiveVerifiedAt != null && status != STATUS_REJECTED) STATUS_VERIFIED else status
        return AgeVerificationStatusDto(
            status = effectiveStatus,
            method = method,
            provider = provider,
            requestedAt = requestedAt.toString(),
            verifiedAt = effectiveVerifiedAt?.toString(),
            expiresAt = expiresAt?.toString(),
            message = messageFor(effectiveStatus),
        )
    }

    private fun verifiedProfileStatus(verifiedAt: Instant): AgeVerificationStatusDto =
        AgeVerificationStatusDto(
            status = STATUS_VERIFIED,
            method = null,
            provider = null,
            requestedAt = null,
            verifiedAt = verifiedAt.toString(),
            expiresAt = null,
            message = messageFor(STATUS_VERIFIED),
        )

    // A market flag is only honored while a provider is configured: with none,
    // manual review stays available so a policy misconfiguration cannot lock
    // users out of verification entirely.
    private fun enhancedRequiredFor(market: String): Boolean = provider != null && marketPolicy.enhancedRequired(market)

    private fun isAdult(birthdate: LocalDate): Boolean = Period.between(birthdate, LocalDate.now(clock)).years >= 18

    private fun String?.cleanMarket(): String = this?.trim()?.lowercase()?.takeIf { it.matches(SAFE_TOKEN_REGEX) } ?: DEFAULT_MARKET

    private fun String?.cleanProvider(): String = this?.trim()?.lowercase()?.takeIf { it.matches(SAFE_TOKEN_REGEX) } ?: PROVIDER_CALLBACK

    private fun String?.cleanReason(): String? {
        val value = this?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (value.length > 500) throw VerificationException("VERIFICATION_REASON_TOO_LONG")
        return value
    }

    private fun String.normalizeReviewStatus(): String =
        when (trim().lowercase()) {
            "approved", STATUS_VERIFIED -> STATUS_VERIFIED
            STATUS_REJECTED -> STATUS_REJECTED
            else -> throw VerificationException("INVALID_VERIFICATION_STATUS")
        }

    private fun String.toUuidOrThrow(): UUID = runCatching { UUID.fromString(this) }.getOrElse { throw VerificationException("INVALID_VERIFICATION_ID") }

    private fun String.toInstantOrThrow(): Instant = runCatching { Instant.parse(this) }.getOrElse { throw VerificationException("INVALID_VERIFICATION_CALLBACK") }

    private fun messageFor(status: String): String =
        when (status) {
            STATUS_VERIFIED -> "Age verification is complete."
            STATUS_PENDING -> "Age verification is pending review."
            STATUS_REJECTED -> "Age verification was not approved."
            else -> "Age verification has not started."
        }

    companion object {
        private const val DEFAULT_MARKET = "default"
        private const val METHOD_MANUAL_REVIEW = "manual_review"
        private const val METHOD_PROVIDER_CALLBACK = "provider_callback"
        private const val PROVIDER_INTERNAL_MANUAL = "internal_manual"
        private const val PROVIDER_CALLBACK = "provider"
        private const val STATUS_NOT_STARTED = "not_started"
        private const val STATUS_PENDING = "pending"
        private const val STATUS_VERIFIED = "verified"
        private const val STATUS_REJECTED = "rejected"
        private val SAFE_TOKEN_REGEX = Regex("""[a-z0-9_-]{2,40}""")
    }
}

class VerificationException(
    val code: String,
) : RuntimeException(code)
