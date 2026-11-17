package com.pynapli.server.auth

import com.pynapli.server.auth.security.Sha256
import com.pynapli.server.auth.security.TokenGenerator
import com.pynapli.shared.network.dto.AuthSessionDto
import com.pynapli.shared.network.dto.AuthUserDto
import java.time.Clock
import java.util.UUID

internal class AuthSessionManager(
    private val repository: AuthRepository,
    private val clock: Clock,
    private val cache: AuthAccessSessionCache,
) {
    suspend fun createSession(
        user: UserRecord,
        deviceId: String?,
        platform: String?,
        appVersion: String?,
    ): AuthSessionDto {
        val now = clock.instant()
        val accessToken = TokenGenerator.newToken()
        val refreshToken = TokenGenerator.newToken()
        repository.createSession(
            sessionId = UUID.randomUUID(),
            userId = user.id,
            accessTokenHash = Sha256.hash(accessToken),
            refreshTokenHash = Sha256.hash(refreshToken),
            // Client-supplied labels: trimmed and capped so a client can't store arbitrary blobs.
            deviceId = deviceId.capped(128),
            platform = platform.capped(32),
            appVersion = appVersion.capped(64),
            accessExpiresAt = now.plus(AUTH_ACCESS_TOKEN_TTL),
            refreshExpiresAt = now.plus(AUTH_REFRESH_TOKEN_TTL),
        )

        return AuthSessionDto(
            accessToken = accessToken,
            refreshToken = refreshToken,
            user = AuthUserDto(userId = user.id.toString(), status = user.status),
        )
    }

    suspend fun refresh(refreshToken: String): AuthSessionDto {
        val now = clock.instant()
        val session =
            repository.findSessionByRefreshTokenHash(Sha256.hash(refreshToken))
                ?: throw AuthException("INVALID_REFRESH_TOKEN")

        if (session.revokedAt != null || session.refreshExpiresAt <= now) {
            throw AuthException("INVALID_REFRESH_TOKEN")
        }
        if (session.userStatus != "active") throw AuthException("ACCOUNT_NOT_ACTIVE")
        cache.remove(session.accessTokenHash)

        val newAccessToken = TokenGenerator.newToken()
        val newRefreshToken = TokenGenerator.newToken()
        val accessExpiresAt = now.plus(AUTH_ACCESS_TOKEN_TTL)
        val refreshExpiresAt = now.plus(AUTH_REFRESH_TOKEN_TTL)
        val rotated =
            repository.rotateSession(
                sessionId = session.id,
                accessTokenHash = Sha256.hash(newAccessToken),
                refreshTokenHash = Sha256.hash(newRefreshToken),
                accessExpiresAt = accessExpiresAt,
                refreshExpiresAt = refreshExpiresAt,
            ) ?: throw AuthException("INVALID_REFRESH_TOKEN")

        return AuthSessionDto(
            accessToken = newAccessToken,
            refreshToken = newRefreshToken,
            user = AuthUserDto(userId = rotated.userId.toString(), status = rotated.userStatus),
        )
    }

    suspend fun authenticate(accessToken: String): UserRecord {
        val now = clock.instant()
        val accessTokenHash = Sha256.hash(accessToken)
        cache.get(accessTokenHash, now)?.let { return it }

        val session =
            repository.findSessionByAccessTokenHash(accessTokenHash)
                ?: throw AuthException("UNAUTHENTICATED")

        if (session.revokedAt != null || session.accessExpiresAt <= now) throw AuthException("UNAUTHENTICATED")
        if (session.userStatus != "active") throw AuthException("ACCOUNT_NOT_ACTIVE")

        val user = UserRecord(id = session.userId, status = session.userStatus)
        cache.put(
            accessTokenHash = accessTokenHash,
            user = user,
            sessionExpiresAt = session.accessExpiresAt,
            now = now,
        )
        return user
    }

    suspend fun logout(accessToken: String): Boolean {
        val accessTokenHash = Sha256.hash(accessToken)
        cache.remove(accessTokenHash)
        val session =
            repository.findSessionByAccessTokenHash(accessTokenHash)
                ?: return false
        return repository.revokeSession(session.id)
    }

    fun forgetCachedAccessTokenHash(accessTokenHash: String) {
        cache.remove(accessTokenHash)
    }

    fun invalidateCachedSessionsForUser(userId: UUID) {
        cache.removeUser(userId)
    }
}

private fun String?.capped(maxLength: Int): String? =
    this
        ?.trim()
        ?.take(maxLength)
        ?.takeIf { it.isNotEmpty() }
