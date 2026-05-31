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
import java.time.Instant
import java.time.LocalDate
import java.time.Period
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class AuthService(
    private val repository: AuthRepository,
    private val passwordHasher: PasswordHasher = PasswordHasher(),
    private val clock: Clock = Clock.systemUTC(),
    private val accessSessionCacheTtl: Duration = Duration.ofSeconds(5),
    private val accessSessionCacheMaxSize: Int = 20_000,
) {
    private val accessSessionCache = ConcurrentHashMap<String, CachedAccessSession>()

    suspend fun signupEmail(
        email: String,
        password: String,
        birthdate: String?,
        confirmedAdult: Boolean,
        acceptedTerms: Boolean,
        acceptedPrivacy: Boolean,
        deviceId: String?,
        platform: String?,
        appVersion: String?,
    ): AuthSessionDto {
        validateEmailPassword(email, password)
        val ageGate =
            validateSignupAgeGate(
                birthdate = birthdate,
                confirmedAdult = confirmedAdult,
                acceptedTerms = acceptedTerms,
                acceptedPrivacy = acceptedPrivacy,
            )
        val emailHash = Sha256.hash(normalizeEmail(email))
        val passwordHash = passwordHasher.hash(password)
        val user =
            try {
                repository.createEmailUser(
                    userId = UUID.randomUUID(),
                    identityId = UUID.randomUUID(),
                    emailHash = emailHash,
                    passwordHash = passwordHash,
                    birthdate = ageGate.birthdate,
                    displayAge = ageGate.displayAge,
                    adultConfirmedAt = ageGate.acceptedAt,
                    termsAcceptedAt = ageGate.acceptedAt,
                    termsVersion = TERMS_VERSION,
                    privacyAcceptedAt = ageGate.acceptedAt,
                    privacyVersion = PRIVACY_VERSION,
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
        appVersion: String?,
    ): AuthSessionDto {
        validateEmailPassword(email, password)
        val identity =
            repository.findEmailIdentity(Sha256.hash(normalizeEmail(email)))
                ?: throw AuthException("INVALID_CREDENTIALS")

        if (identity.userStatus != "active") throw AuthException("ACCOUNT_NOT_ACTIVE")
        if (!passwordHasher.verify(password, identity.passwordHash)) throw AuthException("INVALID_CREDENTIALS")

        val user = UserRecord(identity.userId, identity.userStatus)
        return createSession(user, deviceId, platform, appVersion)
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
        accessSessionCache.remove(session.accessTokenHash)

        val newAccessToken = TokenGenerator.newToken()
        val newRefreshToken = TokenGenerator.newToken()
        val accessExpiresAt = now.plus(ACCESS_TOKEN_TTL)
        val refreshExpiresAt = now.plus(REFRESH_TOKEN_TTL)
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
        accessSessionCache[accessTokenHash]?.let { cached ->
            if (cached.expiresAt.isAfter(now)) {
                return cached.user
            }
            accessSessionCache.remove(accessTokenHash, cached)
        }

        val session =
            repository.findSessionByAccessTokenHash(accessTokenHash)
                ?: throw AuthException("UNAUTHENTICATED")

        if (session.revokedAt != null || session.accessExpiresAt <= now) throw AuthException("UNAUTHENTICATED")
        if (session.userStatus != "active") throw AuthException("ACCOUNT_NOT_ACTIVE")

        val user = UserRecord(id = session.userId, status = session.userStatus)
        cacheAccessSession(
            accessTokenHash = accessTokenHash,
            user = user,
            sessionExpiresAt = session.accessExpiresAt,
            now = now,
        )
        return user
    }

    suspend fun logout(accessToken: String): Boolean {
        val accessTokenHash = Sha256.hash(accessToken)
        accessSessionCache.remove(accessTokenHash)
        val session =
            repository.findSessionByAccessTokenHash(accessTokenHash)
                ?: return false
        return repository.revokeSession(session.id)
    }

    fun invalidateCachedSessionsForUser(userId: UUID) {
        accessSessionCache.entries.removeIf { (_, cached) -> cached.user.id == userId }
    }

    private suspend fun createSession(
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
            deviceId = deviceId,
            platform = platform,
            appVersion = appVersion,
            accessExpiresAt = now.plus(ACCESS_TOKEN_TTL),
            refreshExpiresAt = now.plus(REFRESH_TOKEN_TTL),
        )

        return AuthSessionDto(
            accessToken = accessToken,
            refreshToken = refreshToken,
            user = AuthUserDto(userId = user.id.toString(), status = user.status),
        )
    }

    private fun validateEmailPassword(
        email: String,
        password: String,
    ) {
        if (!normalizeEmail(email).contains("@")) throw AuthException("INVALID_EMAIL")
        if (password.length < 12) throw AuthException("PASSWORD_TOO_SHORT")
    }

    private fun validateSignupAgeGate(
        birthdate: String?,
        confirmedAdult: Boolean,
        acceptedTerms: Boolean,
        acceptedPrivacy: Boolean,
    ): SignupAgeGate {
        if (!confirmedAdult) throw AuthException("ADULT_CONFIRMATION_REQUIRED")
        if (!acceptedTerms) throw AuthException("TERMS_ACCEPTANCE_REQUIRED")
        if (!acceptedPrivacy) throw AuthException("PRIVACY_ACCEPTANCE_REQUIRED")

        val parsedBirthdate =
            birthdate
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.let { value ->
                    runCatching { LocalDate.parse(value) }.getOrElse { throw AuthException("INVALID_BIRTHDATE") }
                }
                ?: throw AuthException("BIRTHDATE_REQUIRED")

        val age = Period.between(parsedBirthdate, LocalDate.now(clock)).years
        if (age < 18) throw AuthException("AGE_RESTRICTED")

        return SignupAgeGate(
            birthdate = parsedBirthdate,
            displayAge = age,
            acceptedAt = clock.instant(),
        )
    }

    private fun cacheAccessSession(
        accessTokenHash: String,
        user: UserRecord,
        sessionExpiresAt: Instant,
        now: Instant,
    ) {
        if (accessSessionCacheTtl.isZero || accessSessionCacheTtl.isNegative || accessSessionCacheMaxSize <= 0) {
            return
        }
        if (accessSessionCache.size >= accessSessionCacheMaxSize) {
            accessSessionCache.entries.removeIf { (_, value) -> !value.expiresAt.isAfter(now) }
            if (accessSessionCache.size >= accessSessionCacheMaxSize) {
                accessSessionCache.clear()
            }
        }
        val cacheExpiresAt = minOf(now.plus(accessSessionCacheTtl), sessionExpiresAt)
        if (cacheExpiresAt.isAfter(now)) {
            accessSessionCache[accessTokenHash] = CachedAccessSession(user = user, expiresAt = cacheExpiresAt)
        }
    }

    companion object {
        private val ACCESS_TOKEN_TTL: Duration = Duration.ofMinutes(15)
        private val REFRESH_TOKEN_TTL: Duration = Duration.ofDays(30)
        private const val TERMS_VERSION = "2026-05-13"
        private const val PRIVACY_VERSION = "2026-05-13"
    }
}

class AuthException(
    val code: String,
) : RuntimeException(code)

private data class SignupAgeGate(
    val birthdate: LocalDate,
    val displayAge: Int,
    val acceptedAt: Instant,
)

private data class CachedAccessSession(
    val user: UserRecord,
    val expiresAt: Instant,
)
