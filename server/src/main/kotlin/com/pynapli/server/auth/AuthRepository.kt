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
        privacyVersion: String,
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
        refreshExpiresAt: Instant,
    ): SessionRecord

    suspend fun findSessionByAccessTokenHash(tokenHash: String): SessionRecord?

    suspend fun findSessionByRefreshTokenHash(tokenHash: String): SessionRecord?

    /**
     * Compare-and-swap rotation: succeeds only while [presentedRefreshTokenHash] is still the
     * session's current refresh token, so a refresh token is exchangeable exactly once even under
     * concurrent requests. Returns null when the session was revoked or already rotated.
     *
     * In the same write, [presentedRefreshTokenHash] becomes the session's previous refresh token and
     * the sealed copy of the tokens this rotation issues ([replayNonce], [replayCiphertext], openable
     * only with the consumed token) is stored until [replayExpiresAt], replacing any earlier one. See
     * [findRefreshReplay].
     */
    suspend fun rotateSession(
        sessionId: UUID,
        presentedRefreshTokenHash: String,
        accessTokenHash: String,
        refreshTokenHash: String,
        accessExpiresAt: Instant,
        refreshExpiresAt: Instant,
        replayNonce: ByteArray,
        replayCiphertext: ByteArray,
        replayExpiresAt: Instant,
    ): SessionRecord?

    /**
     * The unrevoked session whose most recent rotation consumed the refresh token hashing to
     * [previousRefreshTokenHash], with the sealed tokens that rotation issued. Never changes the row.
     */
    suspend fun findRefreshReplay(previousRefreshTokenHash: String): RefreshReplayRecord?

    suspend fun revokeSession(sessionId: UUID): Boolean
}

/**
 * A session's sealed refresh replay: [nonce] and [ciphertext] (AES-GCM, key derived from the consumed
 * refresh token, which is not stored) and the end of its grace. Not a data class: it holds arrays.
 */
class RefreshReplayRecord(
    val session: SessionRecord,
    val nonce: ByteArray,
    val ciphertext: ByteArray,
    val replayExpiresAt: Instant,
)

data class UserRecord(
    val id: UUID,
    val status: String,
)

data class EmailIdentityRecord(
    val userId: UUID,
    val passwordHash: PasswordHash,
    val userStatus: String,
)

data class SessionRecord(
    val id: UUID,
    val userId: UUID,
    val accessTokenHash: String,
    val refreshTokenHash: String,
    val accessExpiresAt: Instant,
    val refreshExpiresAt: Instant,
    val revokedAt: Instant?,
    val userStatus: String,
)
