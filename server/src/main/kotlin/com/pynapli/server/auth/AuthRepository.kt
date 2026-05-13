package com.pynapli.server.auth

import com.pynapli.server.auth.security.PasswordHash
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

interface AuthRepository {
    suspend fun createEmailUser(
        userId: UUID,
        identityId: UUID,
        emailHash: String,
        passwordHash: PasswordHash,
        birthdate: LocalDate,
        displayAge: Int,
        adultConfirmedAt: Instant,
        termsAcceptedAt: Instant,
        termsVersion: String,
        privacyAcceptedAt: Instant,
        privacyVersion: String
    ): UserRecord

    suspend fun findEmailIdentity(emailHash: String): EmailIdentityRecord?

    suspend fun findUser(userId: UUID): UserRecord?

    suspend fun createSession(
        sessionId: UUID,
        userId: UUID,
        accessTokenHash: String,
        refreshTokenHash: String,
        deviceId: String?,
        platform: String?,
        appVersion: String?,
        accessExpiresAt: Instant,
        refreshExpiresAt: Instant
    ): SessionRecord

    suspend fun findSessionByAccessTokenHash(tokenHash: String): SessionRecord?

    suspend fun findSessionByRefreshTokenHash(tokenHash: String): SessionRecord?

    suspend fun rotateSession(
        sessionId: UUID,
        accessTokenHash: String,
        refreshTokenHash: String,
        accessExpiresAt: Instant,
        refreshExpiresAt: Instant
    ): SessionRecord?

    suspend fun revokeSession(sessionId: UUID): Boolean
}

data class UserRecord(
    val id: UUID,
    val status: String
)

data class EmailIdentityRecord(
    val userId: UUID,
    val passwordHash: PasswordHash,
    val userStatus: String
)

data class SessionRecord(
    val id: UUID,
    val userId: UUID,
    val accessTokenHash: String,
    val refreshTokenHash: String,
    val accessExpiresAt: Instant,
    val refreshExpiresAt: Instant,
    val revokedAt: Instant?,
    val userStatus: String
)
