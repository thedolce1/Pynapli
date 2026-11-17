package com.pynapli.server.auth

import com.pynapli.server.auth.security.PasswordHash
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import javax.sql.DataSource

class JdbcAuthRepository(
    dataSource: DataSource,
) : AuthRepository {
    private val users = JdbcAuthUserActions(dataSource)
    private val sessions = JdbcAuthSessionActions(dataSource)

    override suspend fun createEmailUser(
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
        privacyVersion: String,
    ): UserRecord =
        users.createEmailUser(
            userId = userId,
            identityId = identityId,
            emailHash = emailHash,
            passwordHash = passwordHash,
            birthdate = birthdate,
            displayAge = displayAge,
            adultConfirmedAt = adultConfirmedAt,
            termsAcceptedAt = termsAcceptedAt,
            termsVersion = termsVersion,
            privacyAcceptedAt = privacyAcceptedAt,
            privacyVersion = privacyVersion,
        )

    override suspend fun findEmailIdentity(emailHash: String): EmailIdentityRecord? = users.findEmailIdentity(emailHash)

    override suspend fun findUser(userId: UUID): UserRecord? = users.findUser(userId)

    override suspend fun createSession(
        sessionId: UUID,
        userId: UUID,
        accessTokenHash: String,
        refreshTokenHash: String,
        deviceId: String?,
        platform: String?,
        appVersion: String?,
        accessExpiresAt: Instant,
        refreshExpiresAt: Instant,
    ): SessionRecord =
        sessions.createSession(
            sessionId = sessionId,
            userId = userId,
            accessTokenHash = accessTokenHash,
            refreshTokenHash = refreshTokenHash,
            deviceId = deviceId,
            platform = platform,
            appVersion = appVersion,
            accessExpiresAt = accessExpiresAt,
            refreshExpiresAt = refreshExpiresAt,
        )

    override suspend fun findSessionByAccessTokenHash(tokenHash: String): SessionRecord? = sessions.findSessionByAccessTokenHash(tokenHash)

    override suspend fun findSessionByRefreshTokenHash(tokenHash: String): SessionRecord? = sessions.findSessionByRefreshTokenHash(tokenHash)

    override suspend fun rotateSession(
        sessionId: UUID,
        presentedRefreshTokenHash: String,
        accessTokenHash: String,
        refreshTokenHash: String,
        accessExpiresAt: Instant,
        refreshExpiresAt: Instant,
    ): SessionRecord? =
        sessions.rotateSession(
            sessionId = sessionId,
            presentedRefreshTokenHash = presentedRefreshTokenHash,
            accessTokenHash = accessTokenHash,
            refreshTokenHash = refreshTokenHash,
            accessExpiresAt = accessExpiresAt,
            refreshExpiresAt = refreshExpiresAt,
        )

    override suspend fun revokeSession(sessionId: UUID): Boolean = sessions.revokeSession(sessionId)
}
