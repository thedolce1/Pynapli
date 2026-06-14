package com.pynapli.server.verification

import com.pynapli.server.safety.RateLimitService
import com.pynapli.server.safety.VerificationRiskSignalPublisher
import com.pynapli.shared.network.dto.AdminAgeVerificationReviewRequestDto
import com.pynapli.shared.network.dto.AgeVerificationCallbackRequestDto
import com.pynapli.shared.network.dto.AgeVerificationRequirementsDto
import com.pynapli.shared.network.dto.AgeVerificationStatusDto
import com.pynapli.shared.network.dto.StartAgeVerificationRequestDto
import java.time.Clock
import java.util.UUID

class AgeVerificationService(
    repository: AgeVerificationRepository,
    clock: Clock = Clock.systemUTC(),
    provider: AgeVerificationProvider? = null,
    rateLimitService: RateLimitService? = null,
    marketPolicy: AgeVerificationMarketPolicy = BaselineAgeVerificationMarketPolicy,
    verificationRiskSignals: VerificationRiskSignalPublisher? = null,
) {
    private val statusActions = AgeVerificationStatusActions(repository, provider, marketPolicy)
    private val startActions = AgeVerificationStartActions(repository, clock, provider, rateLimitService, marketPolicy)
    private val reviewActions = AgeVerificationReviewActions(repository, clock, verificationRiskSignals)

    suspend fun requirements(
        userId: UUID,
        market: String? = null,
    ): AgeVerificationRequirementsDto = statusActions.requirements(userId, market)

    suspend fun status(userId: UUID): AgeVerificationStatusDto = statusActions.status(userId)

    suspend fun start(
        userId: UUID,
        request: StartAgeVerificationRequestDto,
    ): AgeVerificationStatusDto = startActions.start(userId, request)

    suspend fun review(
        verificationIdText: String,
        request: AdminAgeVerificationReviewRequestDto,
        actorId: String,
    ): AgeVerificationStatusDto = reviewActions.review(verificationIdText, request, actorId)

    suspend fun callback(
        request: AgeVerificationCallbackRequestDto,
        actorId: String,
        fallbackReason: String?,
    ): AgeVerificationStatusDto = reviewActions.callback(request, actorId, fallbackReason)
}

class VerificationException(
    val code: String,
) : RuntimeException(code)
