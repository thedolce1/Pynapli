package com.pynapli.server.verification

import java.time.Instant
import java.time.LocalDate
import java.util.UUID

interface AgeVerificationRepository {
    suspend fun getProfileState(userId: UUID): AgeVerificationProfileState?

    suspend fun latestForUser(userId: UUID): AgeVerificationRecord?

    suspend fun findById(verificationId: UUID): AgeVerificationRecord?

    suspend fun findByProviderReference(provider: String, providerReference: String): AgeVerificationRecord?

    suspend fun createPending(
        verificationId: UUID,
        userId: UUID,
        market: String,
        method: String,
        provider: String,
        providerReference: String?,
        requestedAt: Instant
    ): AgeVerificationRecord

    suspend fun recordCallback(
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
        reviewedAt: Instant
    ): AgeVerificationRecord

    suspend fun review(
        verificationId: UUID,
        status: String,
        verifiedAt: Instant?,
        expiresAt: Instant?,
        reviewerId: String,
        reason: String?,
        reviewedAt: Instant
    ): AgeVerificationRecord?
}

data class AgeVerificationProfileState(
    val userId: UUID,
    val birthdate: LocalDate?,
    val ageVerifiedAt: Instant?
)

data class AgeVerificationRecord(
    val id: UUID,
    val userId: UUID,
    val market: String,
    val method: String,
    val provider: String,
    val providerReference: String?,
    val status: String,
    val requestedAt: Instant,
    val verifiedAt: Instant?,
    val expiresAt: Instant?,
    val reviewedAt: Instant?,
    val reviewerId: String?,
    val reviewReason: String?
)
