package com.pynapli.server.verification

import com.pynapli.server.safety.RateLimitService
import com.pynapli.server.safety.RateLimitedAction
import com.pynapli.shared.network.dto.AgeVerificationStatusDto
import com.pynapli.shared.network.dto.StartAgeVerificationRequestDto
import java.time.Clock
import java.util.UUID

internal class AgeVerificationStartActions(
    private val repository: AgeVerificationRepository,
    private val clock: Clock,
    private val provider: AgeVerificationProvider?,
    private val rateLimitService: RateLimitService?,
    private val marketPolicy: AgeVerificationMarketPolicy,
) {
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
        if (!isAdult(birthdate, clock)) throw VerificationException("AGE_RESTRICTED")

        if (latest?.status == AGE_STATUS_PENDING) return latest.toDto(profile.ageVerifiedAt)

        val method =
            request.method
                ?.trim()
                ?.lowercase()
                ?.takeIf { it.isNotEmpty() } ?: AGE_METHOD_MANUAL_REVIEW
        val market = request.market.cleanMarket()

        if (method == AGE_METHOD_MANUAL_REVIEW && enhancedRequiredFor(provider, marketPolicy, market)) {
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

        if (method != AGE_METHOD_MANUAL_REVIEW) throw VerificationException("INVALID_VERIFICATION_METHOD")

        val record =
            repository.createPending(
                verificationId = UUID.randomUUID(),
                userId = userId,
                market = market,
                method = method,
                provider = AGE_PROVIDER_INTERNAL_MANUAL,
                providerReference = null,
                requestedAt = clock.instant(),
            )
        return record.toDto(profile.ageVerifiedAt)
    }
}
