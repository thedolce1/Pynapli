package com.pynapli.server.auth

import com.pynapli.server.auth.security.PasswordHasher
import com.pynapli.server.auth.security.Sha256
import com.pynapli.server.auth.security.TokenGenerator
import com.pynapli.server.auth.security.normalizeEmail
import com.pynapli.shared.network.dto.AuthSessionDto
import com.pynapli.shared.network.dto.AuthUserDto
import java.sql.SQLException
import java.time.Clock
import java.time.Duration
import java.util.UUID

class AuthService(
    private val repository: AuthRepository,
    private val passwordHasher: PasswordHasher = PasswordHasher(),
    private val clock: Clock = Clock.systemUTC()
) {
    suspend fun signupEmail(
        email: String,
        password: String,
        deviceId: String?,
        platform: String?,
        appVersion: String?
    ): AuthSessionDto {
        validateEmailPassword(email, password)
        val emailHash = Sha256.hash(normalizeEmail(email))
        val passwordHash = passwordHasher.hash(password)
        val user = try {
            repository.createEmailUser(
                userId = UUID.randomUUID(),
                identityId = UUID.randomUUID(),
                emailHash = emailHash,
                passwordHash = passwordHash
            )
        } catch (error: SQLException) {
            if (error.sqlState == "23505") throw AuthException("EMAIL_ALREADY_EXISTS")
            throw error
        }

        return createSession(user, deviceId, platform, appVersion)
    }

    suspend fun loginEmail(
        email: String,
        password: String,
        deviceId: String?,
        platform: String?,
        appVersion: String?
    ): AuthSessionDto {
        validateEmailPassword(email, password)
        val identity = repository.findEmailIdentity(Sha256.hash(normalizeEmail(email)))
            ?: throw AuthException("INVALID_CREDENTIALS")

        if (identity.userStatus != "active") throw AuthException("ACCOUNT_NOT_ACTIVE")
        if (!passwordHasher.verify(password, identity.passwordHash)) throw AuthException("INVALID_CREDENTIALS")

        val user = UserRecord(identity.userId, identity.userStatus)
        return createSession(user, deviceId, platform, appVersion)
    }

    suspend fun refresh(refreshToken: String): AuthSessionDto {
        val now = clock.instant()
        val session = repository.findSessionByRefreshTokenHash(Sha256.hash(refreshToken))
            ?: throw AuthException("INVALID_REFRESH_TOKEN")

        if (session.revokedAt != null || session.refreshExpiresAt <= now) {
            throw AuthException("INVALID_REFRESH_TOKEN")
        }
        if (session.userStatus != "active") throw AuthException("ACCOUNT_NOT_ACTIVE")

        val newAccessToken = TokenGenerator.newToken()
        val newRefreshToken = TokenGenerator.newToken()
        val accessExpiresAt = now.plus(ACCESS_TOKEN_TTL)
        val refreshExpiresAt = now.plus(REFRESH_TOKEN_TTL)
        val rotated = repository.rotateSession(
            sessionId = session.id,
            accessTokenHash = Sha256.hash(newAccessToken),
            refreshTokenHash = Sha256.hash(newRefreshToken),
            accessExpiresAt = accessExpiresAt,
            refreshExpiresAt = refreshExpiresAt
        ) ?: throw AuthException("INVALID_REFRESH_TOKEN")

        return AuthSessionDto(
            accessToken = newAccessToken,
            refreshToken = newRefreshToken,
            user = AuthUserDto(userId = rotated.userId.toString(), status = rotated.userStatus)
        )
    }

    suspend fun authenticate(accessToken: String): UserRecord {
        val now = clock.instant()
        val session = repository.findSessionByAccessTokenHash(Sha256.hash(accessToken))
            ?: throw AuthException("UNAUTHENTICATED")

        if (session.revokedAt != null || session.accessExpiresAt <= now) throw AuthException("UNAUTHENTICATED")
        if (session.userStatus != "active") throw AuthException("ACCOUNT_NOT_ACTIVE")

        return UserRecord(id = session.userId, status = session.userStatus)
    }

    suspend fun logout(accessToken: String): Boolean {
        val session = repository.findSessionByAccessTokenHash(Sha256.hash(accessToken))
            ?: return false
        return repository.revokeSession(session.id)
    }

    private suspend fun createSession(
        user: UserRecord,
        deviceId: String?,
        platform: String?,
        appVersion: String?
    ): AuthSessionDto {
        val now = clock.instant()
        val accessToken = TokenGenerator.newToken()
        val refreshToken = TokenGenerator.newToken()
        repository.createSession(
            sessionId = UUID.randomUUID(),
            userId = user.id,
            accessTokenHash = Sha256.hash(accessToken),
            refreshTokenHash = Sha256.hash(refreshToken),
            deviceId = deviceId,
            platform = platform,
            appVersion = appVersion,
            accessExpiresAt = now.plus(ACCESS_TOKEN_TTL),
            refreshExpiresAt = now.plus(REFRESH_TOKEN_TTL)
        )

        return AuthSessionDto(
            accessToken = accessToken,
            refreshToken = refreshToken,
            user = AuthUserDto(userId = user.id.toString(), status = user.status)
        )
    }

    private fun validateEmailPassword(email: String, password: String) {
        if (!normalizeEmail(email).contains("@")) throw AuthException("INVALID_EMAIL")
        if (password.length < 12) throw AuthException("PASSWORD_TOO_SHORT")
    }

    companion object {
        private val ACCESS_TOKEN_TTL: Duration = Duration.ofMinutes(15)
        private val REFRESH_TOKEN_TTL: Duration = Duration.ofDays(30)
    }
}

class AuthException(val code: String) : RuntimeException(code)

