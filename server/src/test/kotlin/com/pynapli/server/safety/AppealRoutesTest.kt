package com.pynapli.server.safety

import com.pynapli.server.auth.AuthRepository
import com.pynapli.server.auth.AuthService
import com.pynapli.server.auth.EmailIdentityRecord
import com.pynapli.server.auth.SessionRecord
import com.pynapli.server.auth.UserRecord
import com.pynapli.server.auth.security.PasswordHash
import com.pynapli.server.auth.security.Sha256
import com.pynapli.server.plugins.configureSerialization
import com.pynapli.server.plugins.configureStatusPages
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.test.assertEquals

class AppealRoutesTest {
    @Test
    fun submitAppealRequiresBearerToken() =
        testApplication {
            application {
                configureSerialization()
                configureStatusPages()
                configureAppealRoutes(singleSessionAuthService("token", UUID.randomUUID()), AppealService(RouteAppealRepository()))
            }

            val response =
                client.post("/v1/appeals") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"actionType":"other","targetType":"other","reason":"Please review."}""")
                }

            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

    @Test
    fun submitAppealUsesAuthenticatedUser() =
        testApplication {
            val token = "appeal-token"
            val userId = UUID.fromString("00000000-0000-0000-0000-00000000b001")
            val repository = RouteAppealRepository()
            application {
                configureSerialization()
                configureStatusPages()
                configureAppealRoutes(singleSessionAuthService(token, userId), AppealService(repository))
            }

            val response =
                client.post("/v1/appeals") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                    contentType(ContentType.Application.Json)
                    setBody("""{"actionType":"other","targetType":"other","reason":"Please review."}""")
                }

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(userId, repository.lastUserId)
        }
}

private class RouteAppealRepository : AppealRepository {
    var lastUserId: UUID? = null

    override suspend fun createAppeal(
        userId: UUID,
        actionType: String,
        targetType: String,
        targetId: String?,
        relatedReportId: UUID?,
        reason: String,
        appealIdFactory: () -> UUID,
    ): CreateAppealResult {
        lastUserId = userId
        val now = Instant.parse("2026-05-04T12:00:00Z")
        return CreateAppealResult.Created(
            AppealRecord(
                id = appealIdFactory(),
                userId = userId,
                actionType = actionType,
                targetType = targetType,
                targetId = targetId,
                relatedReportId = relatedReportId,
                status = "open",
                userReason = reason,
                adminNote = null,
                createdAt = now,
                updatedAt = now,
                reviewedAt = null,
            ),
        )
    }

    override suspend fun listAppeals(
        userId: UUID,
        limit: Int,
    ): List<AppealRecord> = emptyList()

    override suspend fun withdrawAppeal(
        userId: UUID,
        appealId: UUID,
    ): AppealRecord? = null
}

private fun singleSessionAuthService(
    token: String,
    userId: UUID,
): AuthService {
    val now = Instant.now()
    val session =
        SessionRecord(
            id = UUID.randomUUID(),
            userId = userId,
            accessTokenHash = Sha256.hash(token),
            refreshTokenHash = Sha256.hash("refresh-$token"),
            accessExpiresAt = now.plus(1, ChronoUnit.HOURS),
            refreshExpiresAt = now.plus(30, ChronoUnit.DAYS),
            revokedAt = null,
            userStatus = "active",
        )
    return AuthService(SingleAppealSessionAuthRepository(session))
}

private class SingleAppealSessionAuthRepository(
    private val session: SessionRecord,
) : AuthRepository {
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
    ): UserRecord = throw UnsupportedOperationException()

    override suspend fun findEmailIdentity(emailHash: String): EmailIdentityRecord? = null

    override suspend fun findUser(userId: UUID): UserRecord? = if (userId == session.userId) UserRecord(id = session.userId, status = session.userStatus) else null

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
    ): SessionRecord = throw UnsupportedOperationException()

    override suspend fun findSessionByAccessTokenHash(tokenHash: String): SessionRecord? = if (tokenHash == session.accessTokenHash) session else null

    override suspend fun findSessionByRefreshTokenHash(tokenHash: String): SessionRecord? = null

    override suspend fun rotateSession(
        sessionId: UUID,
        accessTokenHash: String,
        refreshTokenHash: String,
        accessExpiresAt: Instant,
        refreshExpiresAt: Instant,
    ): SessionRecord? = null

    override suspend fun revokeSession(sessionId: UUID): Boolean = false
}
