package com.pynapli.server.presence

import com.pynapli.server.auth.AuthException
import com.pynapli.server.auth.AuthRepository
import com.pynapli.server.auth.AuthService
import com.pynapli.server.auth.EmailIdentityRecord
import com.pynapli.server.auth.SessionRecord
import com.pynapli.server.auth.UserRecord
import com.pynapli.server.auth.security.PasswordHash
import com.pynapli.server.auth.security.Sha256
import com.pynapli.server.safety.BlockRecord
import com.pynapli.server.safety.BlockRepository
import com.pynapli.server.safety.BlockSummaryRecord
import com.pynapli.shared.network.dto.PresenceDeltaDto
import com.pynapli.shared.network.dto.PresenceStreamSubscribeDto
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets

class PresenceStreamRouteTest {
    private val streamJson =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    @Test
    fun subscribedClientReceivesOnlineDeltaForSubscribedTarget() =
        testApplication {
            val viewerId = UUID.randomUUID()
            val targetId = UUID.randomUUID()
            val viewerToken = "viewer-token"
            val authService = singleSessionAuthService(viewerToken, viewerId)
            val broadcaster = PresenceBroadcaster()
            val blockRepository = NoopBlockRepository()

            application {
                configurePresenceStreamRoutes(
                    authService = authService,
                    broadcaster = broadcaster,
                    blockRepository = blockRepository,
                )
            }

            val client = createClient { install(ClientWebSockets) }

            runBlocking {
                client.webSocket("/v1/presence/stream", request = {
                    header(HttpHeaders.Authorization, "Bearer $viewerToken")
                }) {
                    send(
                        Frame.Text(
                            streamJson.encodeToString(
                                PresenceStreamSubscribeDto.serializer(),
                                PresenceStreamSubscribeDto(userIds = listOf(targetId.toString())),
                            ),
                        ),
                    )
                    // Give the route's emitterJob time to install the subscription before broadcasting.
                    delay(50)

                    broadcaster.emit(
                        PresenceDeltaDto(
                            userId = targetId.toString(),
                            online = true,
                            lastActiveAt = Instant.parse("2026-05-09T12:00:00Z").toString(),
                        ),
                    )

                    val frame = withTimeout(1_000) { incoming.receive() }
                    val text = (frame as? Frame.Text)?.readText() ?: fail("Expected text frame")
                    val delta = streamJson.decodeFromString(PresenceDeltaDto.serializer(), text)
                    assertEquals(targetId.toString(), delta.userId)
                    assertEquals(true, delta.online)
                }
            }
        }

    @Test
    fun deltaForUnsubscribedTargetIsNotDelivered() =
        testApplication {
            val viewerId = UUID.randomUUID()
            val subscribedId = UUID.randomUUID()
            val unsubscribedId = UUID.randomUUID()
            val viewerToken = "viewer-token"
            val authService = singleSessionAuthService(viewerToken, viewerId)
            val broadcaster = PresenceBroadcaster()
            val blockRepository = NoopBlockRepository()

            application {
                configurePresenceStreamRoutes(
                    authService = authService,
                    broadcaster = broadcaster,
                    blockRepository = blockRepository,
                )
            }

            val client = createClient { install(ClientWebSockets) }

            runBlocking {
                client.webSocket("/v1/presence/stream", request = {
                    header(HttpHeaders.Authorization, "Bearer $viewerToken")
                }) {
                    send(
                        Frame.Text(
                            streamJson.encodeToString(
                                PresenceStreamSubscribeDto.serializer(),
                                PresenceStreamSubscribeDto(userIds = listOf(subscribedId.toString())),
                            ),
                        ),
                    )
                    delay(50)

                    broadcaster.emit(
                        PresenceDeltaDto(
                            userId = unsubscribedId.toString(),
                            online = true,
                            lastActiveAt = Instant.parse("2026-05-09T12:00:00Z").toString(),
                        ),
                    )

                    broadcaster.emit(
                        PresenceDeltaDto(
                            userId = subscribedId.toString(),
                            online = true,
                            lastActiveAt = Instant.parse("2026-05-09T12:00:01Z").toString(),
                        ),
                    )
                    // Sentinel pattern: a delta that must arrive is emitted right after the one that must not.
                    // The first frame must be the sentinel; if the earlier delta leaked, it would arrive first.
                    // (A tryReceive() check returns immediately and could never fail.)
                    val first = withTimeout(1_000) { incoming.receive() }
                    val text = (first as? Frame.Text)?.readText() ?: fail("Expected text frame")
                    assertTrue(subscribedId.toString() in text, "Expected the sentinel delta first, got: $text")
                    assertFalse(unsubscribedId.toString() in text, "No frame should arrive for an unsubscribed target")
                }
            }
        }

    @Test
    fun deltaForBlockedRelationshipIsFiltered() =
        testApplication {
            val viewerId = UUID.randomUUID()
            val blockedTargetId = UUID.randomUUID()
            val visibleTargetId = UUID.randomUUID()
            val viewerToken = "viewer-token"
            val authService = singleSessionAuthService(viewerToken, viewerId)
            val broadcaster = PresenceBroadcaster()
            val blockRepository = BlockingBlockRepository(blockedPair = setOf(setOf(viewerId, blockedTargetId)))

            application {
                configurePresenceStreamRoutes(
                    authService = authService,
                    broadcaster = broadcaster,
                    blockRepository = blockRepository,
                )
            }

            val client = createClient { install(ClientWebSockets) }

            runBlocking {
                client.webSocket("/v1/presence/stream", request = {
                    header(HttpHeaders.Authorization, "Bearer $viewerToken")
                }) {
                    send(
                        Frame.Text(
                            streamJson.encodeToString(
                                PresenceStreamSubscribeDto.serializer(),
                                PresenceStreamSubscribeDto(userIds = listOf(blockedTargetId.toString(), visibleTargetId.toString())),
                            ),
                        ),
                    )
                    delay(50)

                    broadcaster.emit(
                        PresenceDeltaDto(
                            userId = blockedTargetId.toString(),
                            online = true,
                            lastActiveAt = Instant.parse("2026-05-09T12:00:00Z").toString(),
                        ),
                    )

                    broadcaster.emit(
                        PresenceDeltaDto(
                            userId = visibleTargetId.toString(),
                            online = true,
                            lastActiveAt = Instant.parse("2026-05-09T12:00:01Z").toString(),
                        ),
                    )
                    // Sentinel pattern (see above): the unblocked target's delta must be the first frame.
                    val first = withTimeout(1_000) { incoming.receive() }
                    val text = (first as? Frame.Text)?.readText() ?: fail("Expected text frame")
                    assertTrue(visibleTargetId.toString() in text, "Expected the sentinel delta first, got: $text")
                    assertFalse(blockedTargetId.toString() in text, "Blocked targets must not leak presence to viewer")
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

private class NoopBlockRepository : BlockRepository {
    override suspend fun block(
        blockerUserId: UUID,
        blockedUserId: UUID,
        reason: String?,
        blockIdFactory: () -> UUID,
    ): BlockRecord = throw UnsupportedOperationException()

    override suspend fun unblock(
        blockerUserId: UUID,
        blockedUserId: UUID,
    ): Boolean = false

    override suspend fun listBlocks(blockerUserId: UUID): List<BlockSummaryRecord> = emptyList()

    override suspend fun isBlockedEitherWay(
        userIdA: UUID,
        userIdB: UUID,
    ): Boolean = false

    override suspend fun listBlockedRelatedUserIds(userId: UUID): Set<UUID> = emptySet()
}

private class BlockingBlockRepository(
    private val blockedPair: Set<Set<UUID>>,
) : BlockRepository {
    override suspend fun block(
        blockerUserId: UUID,
        blockedUserId: UUID,
        reason: String?,
        blockIdFactory: () -> UUID,
    ): BlockRecord = throw UnsupportedOperationException()

    override suspend fun unblock(
        blockerUserId: UUID,
        blockedUserId: UUID,
    ): Boolean = false

    override suspend fun listBlocks(blockerUserId: UUID): List<BlockSummaryRecord> = emptyList()

    override suspend fun isBlockedEitherWay(
        userIdA: UUID,
        userIdB: UUID,
    ): Boolean = setOf(userIdA, userIdB) in blockedPair

    override suspend fun listBlockedRelatedUserIds(userId: UUID): Set<UUID> = emptySet()
}
