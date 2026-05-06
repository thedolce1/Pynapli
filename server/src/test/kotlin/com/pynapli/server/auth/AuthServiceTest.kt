package com.pynapli.server.auth

import com.pynapli.server.auth.security.PasswordHash
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class AuthServiceTest {
    @Test
    fun signupCreatesSessionAndMeCanAuthenticate() = runBlocking {
        val service = AuthService(InMemoryAuthRepository())

        val session = service.signupEmail(
            email = "alex@example.com",
            password = "correct horse battery",
            deviceId = "device-1",
            platform = "android",
            appVersion = "0.1"
        )

        val user = service.authenticate(session.accessToken)

        assertEquals(session.user.userId, user.id.toString())
        assertEquals("active", user.status)
    }

    @Test
    fun loginRejectsWrongPassword() = runBlocking {
        val service = AuthService(InMemoryAuthRepository())
        service.signupEmail("alex@example.com", "correct horse battery", null, null, null)

        val error = assertFailsWith<AuthException> {
            service.loginEmail("alex@example.com", "wrong horse battery", null, null, null)
        }

        assertEquals("INVALID_CREDENTIALS", error.code)
    }

    @Test
    fun refreshRotatesTokens() = runBlocking {
        val service = AuthService(InMemoryAuthRepository())
        val session = service.signupEmail("alex@example.com", "correct horse battery", null, null, null)

        val refreshed = service.refresh(session.refreshToken)

        assertEquals(session.user.userId, refreshed.user.userId)
        assertNotEquals(session.accessToken, refreshed.accessToken)
        assertNotEquals(session.refreshToken, refreshed.refreshToken)
    }

    @Test
    fun suspendedCurrentUserCannotAuthenticateExistingSession() = runBlocking {
        val repository = InMemoryAuthRepository()
        val service = AuthService(repository)
        val session = service.signupEmail("alex@example.com", "correct horse battery", null, null, null)
        repository.setUserStatus(UUID.fromString(session.user.userId), "suspended")

        val error = assertFailsWith<AuthException> {
            service.authenticate(session.accessToken)
        }

        assertEquals("ACCOUNT_NOT_ACTIVE", error.code)
    }

    @Test
    fun shortPasswordIsRejected() = runBlocking {
        val service = AuthService(InMemoryAuthRepository())

        val error = assertFailsWith<AuthException> {
            service.signupEmail("alex@example.com", "short", null, null, null)
        }

        assertEquals("PASSWORD_TOO_SHORT", error.code)
    }
}

private class InMemoryAuthRepository : AuthRepository {
    private val users = linkedMapOf<UUID, UserRecord>()
    private val identitiesByEmailHash = linkedMapOf<String, EmailIdentityRecord>()
    private val sessions = linkedMapOf<UUID, SessionRecord>()

    override suspend fun createEmailUser(
        userId: UUID,
        identityId: UUID,
        emailHash: String,
        passwordHash: PasswordHash
    ): UserRecord {
        if (identitiesByEmailHash.containsKey(emailHash)) throw AuthException("EMAIL_ALREADY_EXISTS")
        val user = UserRecord(userId, "active")
        users[userId] = user
        identitiesByEmailHash[emailHash] = EmailIdentityRecord(userId, passwordHash, "active")
        return user
    }

    override suspend fun findEmailIdentity(emailHash: String): EmailIdentityRecord? =
        identitiesByEmailHash[emailHash]

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
        refreshExpiresAt: Instant
    ): SessionRecord {
        val session = SessionRecord(
            id = sessionId,
            userId = userId,
            accessTokenHash = accessTokenHash,
            refreshTokenHash = refreshTokenHash,
            accessExpiresAt = accessExpiresAt,
            refreshExpiresAt = refreshExpiresAt,
            revokedAt = null,
            userStatus = users[userId]?.status ?: "active"
        )
        sessions[sessionId] = session
        return session
    }

    override suspend fun findSessionByAccessTokenHash(tokenHash: String): SessionRecord? =
        sessions.values.firstOrNull { it.accessTokenHash == tokenHash }?.withCurrentUserStatus()

    override suspend fun findSessionByRefreshTokenHash(tokenHash: String): SessionRecord? =
        sessions.values.firstOrNull { it.refreshTokenHash == tokenHash }?.withCurrentUserStatus()

    override suspend fun rotateSession(
        sessionId: UUID,
        accessTokenHash: String,
        refreshTokenHash: String,
        accessExpiresAt: Instant,
        refreshExpiresAt: Instant
    ): SessionRecord? {
        val current = sessions[sessionId] ?: return null
        val rotated = current.copy(
            accessTokenHash = accessTokenHash,
            refreshTokenHash = refreshTokenHash,
            accessExpiresAt = accessExpiresAt,
            refreshExpiresAt = refreshExpiresAt
        )
        sessions[sessionId] = rotated
        return rotated.withCurrentUserStatus()
    }

    override suspend fun revokeSession(sessionId: UUID): Boolean {
        val current = sessions[sessionId] ?: return false
        sessions[sessionId] = current.copy(revokedAt = Instant.now())
        return true
    }

    fun setUserStatus(userId: UUID, status: String) {
        users[userId] = users.getValue(userId).copy(status = status)
        identitiesByEmailHash.replaceAll { _, identity ->
            if (identity.userId == userId) identity.copy(userStatus = status) else identity
        }
    }

    private fun SessionRecord.withCurrentUserStatus(): SessionRecord =
        copy(userStatus = users[userId]?.status ?: userStatus)
}
