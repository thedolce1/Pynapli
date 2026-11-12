package com.pynapli.server.auth

import com.pynapli.server.auth.security.PasswordHasher
import com.pynapli.server.auth.security.Sha256
import com.pynapli.server.auth.security.normalizeEmail
import com.pynapli.server.metrics.ProductMetrics
import com.pynapli.server.safety.DeviceRiskSignalPublisher
import com.pynapli.server.safety.RateLimitService
import com.pynapli.server.safety.RateLimitedAction
import com.pynapli.shared.network.dto.AuthSessionDto
import java.sql.SQLException
import java.time.Clock
import java.time.Duration
import java.util.UUID

class AuthService(
    private val repository: AuthRepository,
    private val passwordHasher: PasswordHasher = PasswordHasher(),
    clock: Clock = Clock.systemUTC(),
    accessSessionCacheTtl: Duration = Duration.ofSeconds(5),
    accessSessionCacheMaxSize: Int = 20_000,
    rateLimitService: RateLimitService? = null,
    deviceFarmRepository: DeviceFarmRepository? = null,
    deviceRiskSignalPublisher: DeviceRiskSignalPublisher? = null,
    private val productMetrics: ProductMetrics? = null,
) {
    private val validator = AuthRequestValidator(clock)
    private val rateLimitGuard = AuthRateLimitGuard(rateLimitService)
    private val deviceFarmGuard = DeviceFarmGuard(deviceFarmRepository, deviceRiskSignalPublisher, clock)
    private val sessionManager =
        AuthSessionManager(
            repository = repository,
            clock = clock,
            cache = AuthAccessSessionCache(accessSessionCacheTtl, accessSessionCacheMaxSize),
        )

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
        rateLimitGuard.enforce(RateLimitedAction.AUTH_SIGNUP, email, deviceId)
        validator.validateEmailPassword(email, password)
        val ageGate =
            validator.validateSignupAgeGate(
                birthdate = birthdate,
                confirmedAdult = confirmedAdult,
                acceptedTerms = acceptedTerms,
                acceptedPrivacy = acceptedPrivacy,
            )
        val user =
            try {
                repository.createEmailUser(
                    userId = UUID.randomUUID(),
                    identityId = UUID.randomUUID(),
                    emailHash = Sha256.hash(normalizeEmail(email)),
                    passwordHash = passwordHasher.hash(password),
                    birthdate = ageGate.birthdate,
                    displayAge = ageGate.displayAge,
                    adultConfirmedAt = ageGate.acceptedAt,
                    termsAcceptedAt = ageGate.acceptedAt,
                    termsVersion = AUTH_TERMS_VERSION,
                    privacyAcceptedAt = ageGate.acceptedAt,
                    privacyVersion = AUTH_PRIVACY_VERSION,
                )
            } catch (error: SQLException) {
                if (error.sqlState == "23505") throw AuthException("EMAIL_ALREADY_EXISTS")
                throw error
            }

        // Account-farm signal (TRUST_AND_SAFETY_SPEC): record the device->account link and raise a
        // risk signal if this device is behind too many accounts. Signal-only — never blocks signup.
        deviceFarmGuard.recordSignup(user.id, deviceId)

        val session = sessionManager.createSession(user, deviceId, platform, appVersion)
        productMetrics?.record(ProductMetrics.SIGNUP)
        return session
    }

    suspend fun loginEmail(
        email: String,
        password: String,
        deviceId: String?,
        platform: String?,
        appVersion: String?,
    ): AuthSessionDto {
        val identity = verifyCredentials(email, password, deviceId)
        if (identity.userStatus != "active") throw AuthException("ACCOUNT_NOT_ACTIVE")

        val session = sessionManager.createSession(UserRecord(identity.userId, identity.userStatus), deviceId, platform, appVersion)
        productMetrics?.record(ProductMetrics.LOGIN)
        return session
    }

    /**
     * Checks an email + password without signing in. The password is checked before the account's
     * status, so a wrong password never reveals whether an account exists or is suspended.
     * Used by sign-in and by the suspended-account appeal (which can't have a session).
     */
    suspend fun verifyCredentials(
        email: String,
        password: String,
        deviceId: String?,
    ): EmailIdentityRecord {
        rateLimitGuard.enforce(RateLimitedAction.AUTH_LOGIN, email, deviceId)
        validator.validateEmailPassword(email, password)
        val identity =
            repository.findEmailIdentity(Sha256.hash(normalizeEmail(email)))
                ?: throw AuthException("INVALID_CREDENTIALS")
        if (!passwordHasher.verify(password, identity.passwordHash)) throw AuthException("INVALID_CREDENTIALS")
        return identity
    }

    suspend fun refresh(refreshToken: String): AuthSessionDto {
        rateLimitGuard.enforce(RateLimitedAction.AUTH_REFRESH, refreshToken)
        return sessionManager.refresh(refreshToken)
    }

    suspend fun authenticate(accessToken: String): UserRecord = sessionManager.authenticate(accessToken)

    /** The session behind an access token (one lookup; used where a row must follow the session, e.g. push devices). */
    suspend fun sessionIdFor(accessToken: String): UUID? = repository.findSessionByAccessTokenHash(Sha256.hash(accessToken))?.id

    suspend fun logout(accessToken: String): Boolean = sessionManager.logout(accessToken)

    fun invalidateCachedSessionsForUser(userId: UUID) {
        sessionManager.invalidateCachedSessionsForUser(userId)
    }
}

class AuthException(
    val code: String,
) : RuntimeException(code)
