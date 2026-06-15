package com.pynapli.server.auth

import com.pynapli.server.auth.security.PasswordHash
import com.pynapli.server.safety.RateLimitCounter
import com.pynapli.server.safety.RateLimitRepository
import com.pynapli.server.safety.RateLimitService
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class AuthServiceTest {
    @Test
    fun signupCreatesSessionAndMeCanAuthenticate() =
        runBlocking {
            val service = AuthService(InMemoryAuthRepository())

            val session = signup(service, deviceId = "device-1", platform = "android", appVersion = "0.1")

            val user = service.authenticate(session.accessToken)

            assertEquals(session.user.userId, user.id.toString())
            assertEquals("active", user.status)
        }

    @Test
    fun loginRejectsWrongPassword() =
        runBlocking {
            val service = AuthService(InMemoryAuthRepository())
            signup(service)

            val error =
                assertFailsWith<AuthException> {
                    service.loginEmail("alex@example.com", "wrong horse battery", null, null, null)
                }

            assertEquals("INVALID_CREDENTIALS", error.code)
        }

    @Test
    fun refreshRotatesTokens() =
        runBlocking {
            val service = AuthService(InMemoryAuthRepository())
            val session = signup(service)

            val refreshed = service.refresh(session.refreshToken)

            assertEquals(session.user.userId, refreshed.user.userId)
            assertNotEquals(session.accessToken, refreshed.accessToken)
            assertNotEquals(session.refreshToken, refreshed.refreshToken)
        }

    @Test
    fun refreshInvalidatesCachedOldAccessToken() =
        runBlocking {
            val service = AuthService(InMemoryAuthRepository())
            val session = signup(service)
            service.authenticate(session.accessToken)

            service.refresh(session.refreshToken)

            val error =
                assertFailsWith<AuthException> {
                    service.authenticate(session.accessToken)
                }
            assertEquals("UNAUTHENTICATED", error.code)
        }

    @Test
    fun logoutInvalidatesCachedAccessToken() =
        runBlocking {
            val service = AuthService(InMemoryAuthRepository())
            val session = signup(service)
            service.authenticate(session.accessToken)

            assertEquals(true, service.logout(session.accessToken))

            val error =
                assertFailsWith<AuthException> {
                    service.authenticate(session.accessToken)
                }
            assertEquals("UNAUTHENTICATED", error.code)
        }

    @Test
    fun userCacheInvalidationForcesFreshAccountStatusCheck() =
        runBlocking {
            val repository = InMemoryAuthRepository()
            val service = AuthService(repository)
            val session = signup(service)
            val userId = UUID.fromString(session.user.userId)
            service.authenticate(session.accessToken)
            repository.setUserStatus(userId, "deleted")

            service.invalidateCachedSessionsForUser(userId)

            val error =
                assertFailsWith<AuthException> {
                    service.authenticate(session.accessToken)
                }
            assertEquals("ACCOUNT_NOT_ACTIVE", error.code)
        }

    @Test
    fun suspendedCurrentUserCannotAuthenticateExistingSession() =
        runBlocking {
            val repository = InMemoryAuthRepository()
            val service = AuthService(repository)
            val session = signup(service)
            repository.setUserStatus(UUID.fromString(session.user.userId), "suspended")

            val error =
                assertFailsWith<AuthException> {
                    service.authenticate(session.accessToken)
                }

            assertEquals("ACCOUNT_NOT_ACTIVE", error.code)
        }

    @Test
    fun shortPasswordIsRejected() =
        runBlocking {
            val service = AuthService(InMemoryAuthRepository())

            val error =
                assertFailsWith<AuthException> {
                    signup(service, password = "short")
                }

            assertEquals("PASSWORD_TOO_SHORT", error.code)
        }

    @Test
    fun signupStoresBirthdateAndAcceptanceState() =
        runBlocking {
            val repository = InMemoryAuthRepository()
            val service = AuthService(repository)

            val session = signup(service, birthdate = "1990-05-13")
            val profile = repository.signupProfiles.getValue(UUID.fromString(session.user.userId))

            assertEquals(LocalDate.parse("1990-05-13"), profile.birthdate)
            assertEquals(true, profile.adultConfirmed)
            assertEquals(true, profile.termsAccepted)
            assertEquals(true, profile.privacyAccepted)
        }

    @Test
    fun signupRequiresBirthdate() =
        runBlocking {
            val service = AuthService(InMemoryAuthRepository())

            val error =
                assertFailsWith<AuthException> {
                    signup(service, birthdate = null)
                }

            assertEquals("BIRTHDATE_REQUIRED", error.code)
        }

    @Test
    fun signupRejectsUnderageBirthdate() =
        runBlocking {
            val service = AuthService(InMemoryAuthRepository())

            val error =
                assertFailsWith<AuthException> {
                    signup(service, birthdate = "2010-01-01")
                }

            assertEquals("AGE_RESTRICTED", error.code)
        }

    @Test
    fun signupRequiresAgeAndTermsAcceptance() =
        runBlocking {
            val service = AuthService(InMemoryAuthRepository())

            val adultError =
                assertFailsWith<AuthException> {
                    signup(service, confirmedAdult = false)
                }
            val termsError =
                assertFailsWith<AuthException> {
                    signup(service, acceptedTerms = false)
                }
            val privacyError =
                assertFailsWith<AuthException> {
                    signup(service, acceptedPrivacy = false)
                }

            assertEquals("ADULT_CONFIRMATION_REQUIRED", adultError.code)
            assertEquals("TERMS_ACCEPTANCE_REQUIRED", termsError.code)
            assertEquals("PRIVACY_ACCEPTANCE_REQUIRED", privacyError.code)
        }

    @Test
    fun signupRejectsRateLimitedEmailBeforeCreatingUser() =
        runBlocking {
            val repository = InMemoryAuthRepository()
            val service = AuthService(repository, rateLimitService = denyingRateLimitService())

            val error =
                assertFailsWith<AuthException> {
                    signup(service)
                }

            assertEquals("RATE_LIMITED", error.code)
            assertEquals(0, repository.signupProfiles.size)
        }

    @Test
    fun loginRejectsRateLimitedEmailBeforeCredentialLookup() =
        runBlocking {
            val repository = InMemoryAuthRepository()
            val service = AuthService(repository, rateLimitService = denyingRateLimitService())

            val error =
                assertFailsWith<AuthException> {
                    service.loginEmail("alex@example.com", "correct horse battery", null, null, null)
                }

            assertEquals("RATE_LIMITED", error.code)
        }

    @Test
    fun signupRaisesAccountFarmSignalOnceTooManyAccountsShareADevice() =
        runBlocking {
            val ledger = InMemoryDeviceFarmRepository()
            val publisher = RecordingDeviceRiskSignalPublisher()
            val service =
                AuthService(
                    InMemoryAuthRepository(),
                    deviceFarmRepository = ledger,
                    deviceRiskSignalPublisher = publisher,
                )

            // First three accounts on the same device stay under the threshold (4).
            signup(service, email = "farm1@example.com", deviceId = "farm-device")
            signup(service, email = "farm2@example.com", deviceId = "farm-device")
            signup(service, email = "farm3@example.com", deviceId = "farm-device")
            assertEquals(0, publisher.signals.size)

            // The fourth account from the same device crosses the threshold and raises the signal.
            signup(service, email = "farm4@example.com", deviceId = "farm-device")
            assertEquals(1, publisher.signals.size)
            assertEquals(4, publisher.signals.single().distinctAccountCount)
            assertEquals(24L, publisher.signals.single().windowHours)
        }

    @Test
    fun signupAndLoginRecordAuthoritativeProductMetrics() =
        runBlocking {
            val metrics =
                com.pynapli.server.metrics
                    .ProductMetrics()
            val service = AuthService(InMemoryAuthRepository(), productMetrics = metrics)

            signup(service, email = "kpi@example.com", password = "correct horse battery")
            service.loginEmail("kpi@example.com", "correct horse battery", null, null, null)

            assertEquals(1, metrics.countFor(com.pynapli.server.metrics.ProductMetrics.SIGNUP))
            assertEquals(1, metrics.countFor(com.pynapli.server.metrics.ProductMetrics.LOGIN))
        }

    @Test
    fun signupWithoutDeviceIdNeverRaisesAFarmSignal() =
        runBlocking {
            val ledger = InMemoryDeviceFarmRepository()
            val publisher = RecordingDeviceRiskSignalPublisher()
            val service =
                AuthService(
                    InMemoryAuthRepository(),
                    deviceFarmRepository = ledger,
                    deviceRiskSignalPublisher = publisher,
                )

            repeat(6) { index -> signup(service, email = "nodevice$index@example.com", deviceId = null) }

            assertEquals(0, publisher.signals.size)
        }
}

private suspend fun signup(
    service: AuthService,
    email: String = "alex@example.com",
    password: String = "correct horse battery",
    birthdate: String? = "1990-01-01",
    confirmedAdult: Boolean = true,
    acceptedTerms: Boolean = true,
    acceptedPrivacy: Boolean = true,
    deviceId: String? = null,
    platform: String? = null,
    appVersion: String? = null,
) = service.signupEmail(
    email = email,
    password = password,
    birthdate = birthdate,
    confirmedAdult = confirmedAdult,
    acceptedTerms = acceptedTerms,
    acceptedPrivacy = acceptedPrivacy,
    deviceId = deviceId,
    platform = platform,
    appVersion = appVersion,
)

private class InMemoryAuthRepository : AuthRepository {
    private val users = linkedMapOf<UUID, UserRecord>()
    private val identitiesByEmailHash = linkedMapOf<String, EmailIdentityRecord>()
    private val sessions = linkedMapOf<UUID, SessionRecord>()
    val signupProfiles = linkedMapOf<UUID, SignupProfile>()

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
    ): UserRecord {
        if (identitiesByEmailHash.containsKey(emailHash)) throw AuthException("EMAIL_ALREADY_EXISTS")
        val user = UserRecord(userId, "active")
        users[userId] = user
        identitiesByEmailHash[emailHash] = EmailIdentityRecord(userId, passwordHash, "active")
        signupProfiles[userId] =
            SignupProfile(
                birthdate = birthdate,
                displayAge = displayAge,
                adultConfirmed = adultConfirmedAt == termsAcceptedAt,
                termsAccepted = termsVersion.isNotBlank(),
                privacyAccepted = privacyVersion.isNotBlank(),
            )
        return user
    }

    override suspend fun findEmailIdentity(emailHash: String): EmailIdentityRecord? = identitiesByEmailHash[emailHash]

    override suspend fun findUser(userId: UUID): UserRecord? = users[userId]

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
    ): SessionRecord {
        val session =
            SessionRecord(
                id = sessionId,
                userId = userId,
                accessTokenHash = accessTokenHash,
                refreshTokenHash = refreshTokenHash,
                accessExpiresAt = accessExpiresAt,
                refreshExpiresAt = refreshExpiresAt,
                revokedAt = null,
                userStatus = users[userId]?.status ?: "active",
            )
        sessions[sessionId] = session
        return session
    }

    override suspend fun findSessionByAccessTokenHash(tokenHash: String): SessionRecord? = sessions.values.firstOrNull { it.accessTokenHash == tokenHash }?.withCurrentUserStatus()

    override suspend fun findSessionByRefreshTokenHash(tokenHash: String): SessionRecord? = sessions.values.firstOrNull { it.refreshTokenHash == tokenHash }?.withCurrentUserStatus()

    override suspend fun rotateSession(
        sessionId: UUID,
        accessTokenHash: String,
        refreshTokenHash: String,
        accessExpiresAt: Instant,
        refreshExpiresAt: Instant,
    ): SessionRecord? {
        val current = sessions[sessionId] ?: return null
        val rotated =
            current.copy(
                accessTokenHash = accessTokenHash,
                refreshTokenHash = refreshTokenHash,
                accessExpiresAt = accessExpiresAt,
                refreshExpiresAt = refreshExpiresAt,
            )
        sessions[sessionId] = rotated
        return rotated.withCurrentUserStatus()
    }

    override suspend fun revokeSession(sessionId: UUID): Boolean {
        val current = sessions[sessionId] ?: return false
        sessions[sessionId] = current.copy(revokedAt = Instant.now())
        return true
    }

    fun setUserStatus(
        userId: UUID,
        status: String,
    ) {
        users[userId] = users.getValue(userId).copy(status = status)
        identitiesByEmailHash.replaceAll { _, identity ->
            if (identity.userId == userId) identity.copy(userStatus = status) else identity
        }
    }

    private fun SessionRecord.withCurrentUserStatus(): SessionRecord = copy(userStatus = users[userId]?.status ?: userStatus)
}

private data class SignupProfile(
    val birthdate: LocalDate,
    val displayAge: Int,
    val adultConfirmed: Boolean,
    val termsAccepted: Boolean,
    val privacyAccepted: Boolean,
)

private class InMemoryDeviceFarmRepository : DeviceFarmRepository {
    private data class Link(
        val deviceHash: String,
        val userId: UUID,
        val createdAt: Instant,
    )

    private val links = mutableListOf<Link>()

    override suspend fun linkDeviceToAccount(
        deviceHash: String,
        userId: UUID,
        occurredAt: Instant,
    ) {
        if (links.none { it.deviceHash == deviceHash && it.userId == userId }) {
            links += Link(deviceHash, userId, occurredAt)
        }
    }

    override suspend fun countDistinctAccountsForDevice(
        deviceHash: String,
        since: Instant,
    ): Int =
        links
            .filter { it.deviceHash == deviceHash && !it.createdAt.isBefore(since) }
            .map { it.userId }
            .distinct()
            .size
}

private data class RecordedDeviceFarmSignal(
    val userId: UUID,
    val distinctAccountCount: Int,
    val windowHours: Long,
)

private class RecordingDeviceRiskSignalPublisher : com.pynapli.server.safety.DeviceRiskSignalPublisher {
    val signals = mutableListOf<RecordedDeviceFarmSignal>()

    override suspend fun recordDeviceAccountFarm(
        userId: UUID,
        distinctAccountCount: Int,
        windowHours: Long,
    ) {
        signals += RecordedDeviceFarmSignal(userId, distinctAccountCount, windowHours)
    }
}

private fun denyingRateLimitService(): RateLimitService =
    RateLimitService(
        object : RateLimitRepository {
            override suspend fun increment(
                key: String,
                windowSeconds: Long,
            ): RateLimitCounter = RateLimitCounter(count = Long.MAX_VALUE, ttlSeconds = windowSeconds)
        },
    )
