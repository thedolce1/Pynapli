package com.pynapli.server.auth

import com.pynapli.server.auth.security.PasswordHasher
import com.pynapli.server.auth.security.Sha256
import com.pynapli.server.auth.security.normalizeEmail
import com.pynapli.server.metrics.ProductMetrics
import com.pynapli.server.realtime.LocalOnlyRealtimeRelay
import com.pynapli.server.realtime.RealtimeRelay
import com.pynapli.server.safety.DeviceRiskSignalPublisher
import com.pynapli.server.safety.RateLimitService
import com.pynapli.server.safety.RateLimitedAction
import com.pynapli.shared.network.dto.AuthSessionDto
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
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
    private val revocationRelay: RealtimeRelay = LocalOnlyRealtimeRelay,
    /** After a successful email sign-in, with the account and the normalised typed address. */
    private val onEmailSignIn: (suspend (userId: UUID, normalisedEmail: String) -> Unit)? = null,
    /**
     * After every successful [authenticate] (each authenticated HTTP request and each realtime socket's
     * open; never [authenticateWithoutActivity]). Presence hangs off this (plan G2). Must not suspend for long or
     * throw; a failure is swallowed so it never fails the request.
     */
    private val onAuthenticated: ((userId: UUID) -> Unit)? = null,
    /** On logout and account deletion: the member's presence ends now ([endPresence]). */
    private val onPresenceEnded: (suspend (userId: UUID) -> Unit)? = null,
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

    // Accounts whose sessions were just revoked (here or on another replica): open realtime sockets
    // re-check at once instead of at their next periodic check. Never suspends; on overflow the
    // oldest is dropped and those sockets fall back to the periodic check.
    private val revokedUsers = MutableSharedFlow<UUID>(extraBufferCapacity = 256, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val sessionRevocations: SharedFlow<UUID> = revokedUsers.asSharedFlow()

    // Other replicas cache sessions for a few seconds too: tell them about every sign-out and
    // account-wide revocation so a revoked token stops working everywhere at once.
    init {
        revocationRelay.subscribe(REVOCATION_CHANNEL) { message ->
            when {
                message.startsWith(REVOKED_TOKEN_PREFIX) -> sessionManager.forgetCachedAccessTokenHash(message.removePrefix(REVOKED_TOKEN_PREFIX))
                message.startsWith(REVOKED_USER_PREFIX) ->
                    runCatching { UUID.fromString(message.removePrefix(REVOKED_USER_PREFIX)) }
                        .getOrNull()
                        ?.let { userId ->
                            sessionManager.invalidateCachedSessionsForUser(userId)
                            revokedUsers.tryEmit(userId)
                        }
            }
        }
    }

    /**
     * The first session of an account email sign-up just created (EmailSignupService, after the
     * emailed code checked out). Same tail sign-up has always had: the account-farm signal
     * (TRUST_AND_SAFETY_SPEC; signal-only, never blocks), the session, the SIGNUP metric.
     */
    internal suspend fun startFirstSession(
        user: UserRecord,
        deviceId: String?,
        platform: String?,
        appVersion: String?,
    ): AuthSessionDto {
        deviceFarmGuard.recordSignup(user.id, deviceId)
        val session = sessionManager.createSession(user, deviceId, platform, appVersion)
        productMetrics?.record(ProductMetrics.SIGNUP)
        return session
    }

    /**
     * A session for an existing account that just proved a linked Google/Apple identity
     * (SocialAuthService, which has already refused non-active accounts). Same tail as email sign-in.
     */
    internal suspend fun startSocialSignInSession(
        user: UserRecord,
        deviceId: String?,
        platform: String?,
        appVersion: String?,
    ): AuthSessionDto {
        val session = sessionManager.createSession(user, deviceId, platform, appVersion)
        productMetrics?.record(ProductMetrics.LOGIN)
        return session
    }

    suspend fun loginEmail(
        email: String,
        password: String,
        deviceId: String?,
        platform: String?,
        appVersion: String?,
        clientIp: String? = null,
    ): AuthSessionDto {
        val identity = verifyCredentials(email, password, clientIp)
        if (identity.userStatus != "active") throw AuthException("ACCOUNT_NOT_ACTIVE")
        // The typed address matched the account, so it is the owner's: an account verified before
        // contact tokens existed gets its token now, and "Hide from people you know" rows naming it are
        // settled, all before this sign-in hands back a session (TRUST_AND_SAFETY_SPEC, "Contact blocks").
        onEmailSignIn?.invoke(identity.userId, normalizeEmail(email))

        val session = sessionManager.createSession(UserRecord(identity.userId, identity.userStatus), deviceId, platform, appVersion)
        productMetrics?.record(ProductMetrics.LOGIN)
        return session
    }

    /**
     * Checks an email + password without signing in. The password is checked before the account's
     * status, so a wrong password never reveals whether an account exists or is suspended.
     * Used by sign-in and by the suspended-account appeal (which can't have a session).
     * Failed attempts are limited per account + [clientIp] (AuthRateLimitGuard).
     *
     * An address with no account still costs one full password check, against the hasher's decoy
     * hash at the current parameters, and fails exactly like a wrong password: neither the answer
     * nor the time it takes says whether the address is a member.
     */
    suspend fun verifyCredentials(
        email: String,
        password: String,
        clientIp: String?,
    ): EmailIdentityRecord {
        rateLimitGuard.beforeLoginAttempt(email, clientIp)
        validator.validateEmailPassword(email, password)
        val identity = repository.findEmailIdentity(Sha256.hash(normalizeEmail(email)))
        // Always verify (no short-circuit on a missing account), then decide.
        val passwordMatches = passwordHasher.verify(password, identity?.passwordHash ?: passwordHasher.decoyHash)
        if (identity == null || !passwordMatches) {
            rateLimitGuard.recordLoginFailure(email, clientIp)
            throw AuthException("INVALID_CREDENTIALS")
        }
        return identity
    }

    suspend fun refresh(refreshToken: String): AuthSessionDto {
        rateLimitGuard.enforce(RateLimitedAction.AUTH_REFRESH, refreshToken)
        return sessionManager.refresh(refreshToken)
    }

    /**
     * Authenticates a real request by the member, which also counts as activity ([onAuthenticated]:
     * presence). Use [authenticateWithoutActivity] where the call is not the member using the app.
     */
    suspend fun authenticate(accessToken: String): UserRecord =
        sessionManager.authenticate(accessToken).also { user ->
            onAuthenticated?.let { hook -> runCatching { hook(user.id) } }
        }

    /**
     * The same check as [authenticate] without counting as activity (privacy review F3): realtime
     * sockets' periodic re-auth ticks, the rate-limit plugin's token lookup, logout-adjacent session
     * sign-outs and account deletion. None of these may keep someone looking online.
     */
    suspend fun authenticateWithoutActivity(accessToken: String): UserRecord = sessionManager.authenticate(accessToken)

    /**
     * Ends the member's presence now (logout, account deletion): their online key is deleted, so the dot
     * goes at once and nothing about the sign-out is broadcast beyond the scanner's ordinary, jittered,
     * eligibility-gated "went offline". Best-effort.
     */
    suspend fun endPresence(userId: UUID) {
        onPresenceEnded?.let { hook -> runCatching { hook(userId) } }
    }

    /** The session behind an access token (one lookup; used where a row must follow the session, e.g. push devices). */
    suspend fun sessionIdFor(accessToken: String): UUID? = repository.findSessionByAccessTokenHash(Sha256.hash(accessToken))?.id

    suspend fun logout(accessToken: String): Boolean {
        revocationRelay.publish(REVOCATION_CHANNEL, REVOKED_TOKEN_PREFIX + Sha256.hash(accessToken))
        val signedOutUserId = sessionManager.logout(accessToken) ?: return false
        endPresence(signedOutUserId)
        return true
    }

    fun invalidateCachedSessionsForUser(userId: UUID) {
        revocationRelay.publish(REVOCATION_CHANNEL, REVOKED_USER_PREFIX + userId)
        sessionManager.invalidateCachedSessionsForUser(userId)
        revokedUsers.tryEmit(userId)
    }
}

class AuthException(
    val code: String,
) : RuntimeException(code)

private const val REVOCATION_CHANNEL = "pynapli:auth:revocations"
private const val REVOKED_TOKEN_PREFIX = "token:"
private const val REVOKED_USER_PREFIX = "user:"
