package com.pynapli.server.verification

import com.pynapli.shared.network.dto.AgeVerificationMethodDto
import com.pynapli.shared.network.dto.AgeVerificationRequirementsDto
import com.pynapli.shared.network.dto.AgeVerificationStatusDto
import java.util.UUID

internal class AgeVerificationStatusActions(
    private val repository: AgeVerificationRepository,
    private val provider: AgeVerificationProvider?,
    private val marketPolicy: AgeVerificationMarketPolicy,
) {
    suspend fun requirements(
        userId: UUID,
        market: String? = null,
    ): AgeVerificationRequirementsDto {
        val profile = repository.getProfileState(userId) ?: throw VerificationException("PROFILE_NOT_FOUND")
        val latest = repository.latestForUser(userId)
        val enhancedRequired = enhancedRequiredFor(provider, marketPolicy, market.cleanMarket())
        val acceptedMethods =
            buildList {
                // Enhanced markets accept only the provider method; manual review
                // would not satisfy the legal requirement there.
                if (!enhancedRequired) add(AgeVerificationMethodDto(AGE_METHOD_MANUAL_REVIEW, "Manual review"))
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
                status = AGE_STATUS_NOT_STARTED,
                message = "Age verification has not started.",
            )
    }
}
