package com.pynapli.server.auth

import com.pynapli.server.auth.security.PasswordHash
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
