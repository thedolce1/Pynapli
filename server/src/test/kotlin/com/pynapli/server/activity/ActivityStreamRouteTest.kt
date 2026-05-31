package com.pynapli.server.activity

import com.pynapli.server.auth.AuthRepository
import com.pynapli.server.auth.AuthService
import com.pynapli.server.auth.EmailIdentityRecord
import com.pynapli.server.auth.SessionRecord
import com.pynapli.server.auth.UserRecord
import com.pynapli.server.auth.security.PasswordHash
import com.pynapli.server.auth.security.Sha256
import com.pynapli.shared.network.dto.ActivityDeltaDto
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.fail
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets

class ActivityStreamRouteTest {
    private val streamJson =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    @Test
    fun authenticatedClientReceivesOwnActivityDelta() =
        testApplication {
            val viewerId = UUID.randomUUID()
            val viewerToken = "viewer-token"
            val authService = singleSessionAuthService(viewerToken, viewerId)
            val broadcaster = ActivityBroadcaster()

            application {
                configureActivityStreamRoutes(
                    authService = authService,
                    broadcaster = broadcaster,
                )
            }

            val client = createClient { install(ClientWebSockets) }

            runBlocking {
                client.webSocket("/v1/activity/stream", request = {
                    header(HttpHeaders.Authorization, "Bearer $viewerToken")
                }) {
                    delay(50)

                    broadcaster.emit(
                        ActivityRealtimeDelta(
                            recipientUserId = viewerId,
                            type = "match",
                            occurredAt = Instant.parse("2026-05-20T16:00:00Z"),
                        ),
                    )

                    val frame = withTimeout(1_000) { incoming.receive() }
                    val text = (frame as? Frame.Text)?.readText() ?: fail("Expected text frame")
                    val delta = streamJson.decodeFromString(ActivityDeltaDto.serializer(), text)
                    assertEquals("match", delta.type)
                    assertEquals("matches", delta.tab)
                    assertEquals("2026-05-20T16:00:00Z", delta.occurredAt)
                }
            }
        }

    @Test
    fun otherUsersActivityDeltaIsNotDelivered() =
        testApplication {
            val viewerId = UUID.randomUUID()
            val otherUserId = UUID.randomUUID()
            val viewerToken = "viewer-token"
            val authService = singleSessionAuthService(viewerToken, viewerId)
            val broadcaster = ActivityBroadcaster()

            application {
                configureActivityStreamRoutes(
                    authService = authService,
                    broadcaster = broadcaster,
                )
            }

            val client = createClient { install(ClientWebSockets) }

            runBlocking {
                client.webSocket("/v1/activity/stream", request = {
                    header(HttpHeaders.Authorization, "Bearer $viewerToken")
                }) {
                    delay(50)

                    broadcaster.emit(
                        ActivityRealtimeDelta(
                            recipientUserId = otherUserId,
                            type = "flirt",
                            occurredAt = Instant.parse("2026-05-20T16:00:00Z"),
                        ),
                    )

                    val maybeFrame =
                        withTimeout(300) {
                            runCatching { incoming.tryReceive().getOrNull() }.getOrNull()
                        }
                    assertNull(maybeFrame, "Activity deltas must only be delivered to their recipient")
                }
            }
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
        return AuthService(SingleSessionAuthRepository(session))
    }
}

private class SingleSessionAuthRepository(
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
