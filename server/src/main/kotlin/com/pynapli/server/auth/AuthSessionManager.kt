package com.pynapli.server.auth

import com.pynapli.server.auth.security.RefreshReplaySeal
import com.pynapli.server.auth.security.Sha256
import com.pynapli.server.auth.security.TokenGenerator
import com.pynapli.shared.network.dto.AuthSessionDto
import com.pynapli.shared.network.dto.AuthUserDto
import java.time.Clock
import java.time.Instant
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
        val presentedRefreshTokenHash = Sha256.hash(refreshToken)
        val session = repository.findSessionByRefreshTokenHash(presentedRefreshTokenHash)
        // Not a current refresh token: it may be one a rotation consumed moments ago.
        if (session == null) return replay(refreshToken, presentedRefreshTokenHash, now)

        if (session.revokedAt != null || session.refreshExpiresAt <= now) {
            throw AuthException("INVALID_REFRESH_TOKEN")
        }
        if (session.userStatus != "active") throw AuthException("ACCOUNT_NOT_ACTIVE")
        cache.remove(session.accessTokenHash)

        val newAccessToken = TokenGenerator.newToken()
        val newRefreshToken = TokenGenerator.newToken()
        val accessExpiresAt = now.plus(AUTH_ACCESS_TOKEN_TTL)
        val refreshExpiresAt = now.plus(AUTH_REFRESH_TOKEN_TTL)
        // Sealed under a key derived from the token being consumed, so only a caller presenting that
        // same token again can get these tokens back (see replay()).
        val sealed = RefreshReplaySeal.seal(refreshToken, session.id, newAccessToken, newRefreshToken)
        val rotated =
            repository.rotateSession(
                sessionId = session.id,
                presentedRefreshTokenHash = presentedRefreshTokenHash,
                accessTokenHash = Sha256.hash(newAccessToken),
                refreshTokenHash = Sha256.hash(newRefreshToken),
                accessExpiresAt = accessExpiresAt,
                refreshExpiresAt = refreshExpiresAt,
                replayNonce = sealed.nonce,
                replayCiphertext = sealed.ciphertext,
                replayExpiresAt = now.plus(AUTH_REFRESH_REPLAY_GRACE),
            )
        // Lost the compare-and-swap: a concurrent request with this same token rotated first. Converge
        // on the tokens it issued rather than rejecting (or minting a second set).
        if (rotated == null) return replay(refreshToken, presentedRefreshTokenHash, now)
        // Evict again now the rotation has committed: an authenticate() racing the window above can
        // re-cache the still-valid old access token, which would otherwise keep working for the TTL.
        cache.remove(session.accessTokenHash)

        return AuthSessionDto(
            accessToken = newAccessToken,
            refreshToken = newRefreshToken,
            user = AuthUserDto(userId = rotated.userId.toString(), status = rotated.userStatus),
        )
    }

    /**
     * Idempotent retry of a refresh whose response was lost (or that lost a race to a concurrent
     * refresh with the same token): within [AUTH_REFRESH_REPLAY_GRACE] of the rotation that consumed
     * [refreshToken], return the very tokens that rotation issued. Mints nothing and writes nothing, so
     * it cannot extend a session; it applies the same revoked/expired/account checks as a refresh, and
     * only the single most recent previous token of an unrevoked session qualifies.
     */
    private suspend fun replay(
        refreshToken: String,
        presentedRefreshTokenHash: String,
        now: Instant,
    ): AuthSessionDto {
        val replay =
            repository.findRefreshReplay(presentedRefreshTokenHash)
                ?: throw AuthException("INVALID_REFRESH_TOKEN")
        val session = replay.session
        if (session.revokedAt != null || session.refreshExpiresAt <= now || replay.replayExpiresAt <= now) {
            throw AuthException("INVALID_REFRESH_TOKEN")
        }
        val (accessToken, currentRefreshToken) =
            RefreshReplaySeal.open(refreshToken, session.id, replay.nonce, replay.ciphertext)
                ?: throw AuthException("INVALID_REFRESH_TOKEN")
        // Only ever hand back the session's live tokens (written in the same UPDATE as the blob, so
        // this holds unless the row was changed some other way; then refuse rather than guess).
        if (Sha256.hash(accessToken) != session.accessTokenHash || Sha256.hash(currentRefreshToken) != session.refreshTokenHash) {
            throw AuthException("INVALID_REFRESH_TOKEN")
        }
        if (session.userStatus != "active") throw AuthException("ACCOUNT_NOT_ACTIVE")

        return AuthSessionDto(
            accessToken = accessToken,
            refreshToken = currentRefreshToken,
            user = AuthUserDto(userId = session.userId.toString(), status = session.userStatus),
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
