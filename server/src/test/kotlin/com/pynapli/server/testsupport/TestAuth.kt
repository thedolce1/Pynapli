package com.pynapli.server.testsupport

import com.pynapli.server.auth.AuthRepository
import com.pynapli.server.auth.EmailIdentityRecord
import com.pynapli.server.auth.SessionRecord
import com.pynapli.server.auth.UserRecord
import com.pynapli.server.auth.security.PasswordHash
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Auth fake for route tests: exactly one valid session, looked up by its access-token hash. Every
 * write path throws, so a test that accidentally exercises signup/login fails loudly.
 */
internal class SingleSessionAuthRepository(
    private val session: SessionRecord,
) : AuthRepository {
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
    ): UserRecord = throw UnsupportedOperationException()

    override suspend fun findEmailIdentity(emailHash: String): EmailIdentityRecord? = null

    override suspend fun findUser(userId: UUID): UserRecord? = if (userId == session.userId) UserRecord(id = session.userId, status = session.userStatus) else null

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
    ): SessionRecord = throw UnsupportedOperationException()

    override suspend fun findSessionByAccessTokenHash(tokenHash: String): SessionRecord? = if (tokenHash == session.accessTokenHash) session else null

    override suspend fun findSessionByRefreshTokenHash(tokenHash: String): SessionRecord? = null

    override suspend fun rotateSession(
        sessionId: UUID,
        presentedRefreshTokenHash: String,
        accessTokenHash: String,
        refreshTokenHash: String,
        accessExpiresAt: Instant,
        refreshExpiresAt: Instant,
    ): SessionRecord? = null

    override suspend fun revokeSession(sessionId: UUID): Boolean = false
}
