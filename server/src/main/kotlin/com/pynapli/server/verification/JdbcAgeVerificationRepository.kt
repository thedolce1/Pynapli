package com.pynapli.server.verification

import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

class JdbcAgeVerificationRepository(
    dataSource: DataSource,
) : AgeVerificationRepository {
    private val reads = JdbcAgeVerificationReadActions(dataSource)
    private val writes = JdbcAgeVerificationWriteActions(dataSource)

    override suspend fun getProfileState(userId: UUID): AgeVerificationProfileState? = reads.getProfileState(userId)

    override suspend fun latestForUser(userId: UUID): AgeVerificationRecord? = reads.latestForUser(userId)

    override suspend fun findById(verificationId: UUID): AgeVerificationRecord? = reads.findById(verificationId)

    override suspend fun findByProviderReference(
        provider: String,
        providerReference: String,
    ): AgeVerificationRecord? = reads.findByProviderReference(provider, providerReference)

    override suspend fun createPending(
        verificationId: UUID,
        userId: UUID,
        market: String,
        method: String,
        provider: String,
        providerReference: String?,
        requestedAt: Instant,
    ): AgeVerificationRecord =
        writes.createPending(
            verificationId = verificationId,
            userId = userId,
            market = market,
            method = method,
            provider = provider,
            providerReference = providerReference,
            requestedAt = requestedAt,
        )

    override suspend fun recordCallback(
        verificationId: UUID,
        userId: UUID,
        market: String,
        method: String,
        provider: String,
        providerReference: String?,
        status: String,
        verifiedAt: Instant?,
        expiresAt: Instant?,
        reviewerId: String,
        reason: String?,
        reviewedAt: Instant,
    ): AgeVerificationRecord =
        writes.recordCallback(
            verificationId = verificationId,
            userId = userId,
            market = market,
            method = method,
            provider = provider,
            providerReference = providerReference,
            status = status,
            verifiedAt = verifiedAt,
            expiresAt = expiresAt,
            reviewerId = reviewerId,
            reason = reason,
            reviewedAt = reviewedAt,
        )

    override suspend fun review(
        verificationId: UUID,
        status: String,
        verifiedAt: Instant?,
        expiresAt: Instant?,
        reviewerId: String,
        reason: String?,
        reviewedAt: Instant,
    ): AgeVerificationRecord? =
        writes.review(
            verificationId = verificationId,
            status = status,
            verifiedAt = verifiedAt,
            expiresAt = expiresAt,
            reviewerId = reviewerId,
            reason = reason,
            reviewedAt = reviewedAt,
        )
}
