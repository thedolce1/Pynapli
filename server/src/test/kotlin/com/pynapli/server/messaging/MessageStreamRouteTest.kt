package com.pynapli.server.messaging

import com.pynapli.server.auth.AuthService
import com.pynapli.server.auth.SessionRecord
import com.pynapli.server.auth.security.Sha256
import com.pynapli.server.testsupport.SingleSessionAuthRepository
import com.pynapli.server.testsupport.receiveWhileEmitting
import com.pynapli.shared.network.dto.MessageDeltaDto
import com.pynapli.shared.network.dto.MessageDeltaType
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
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets

class MessageStreamRouteTest {
    private val streamJson =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    @Test
    fun authenticatedClientReceivesOwnMessageDelta() =
        testApplication {
            val viewerId = UUID.randomUUID()
            val actorId = UUID.randomUUID()
            val conversationId = UUID.randomUUID()
            val messageId = UUID.randomUUID()
            val viewerToken = "viewer-token"
            val authService = singleSessionAuthService(viewerToken, viewerId)
            val broadcaster = MessageBroadcaster()

            application {
                configureMessageStreamRoutes(
                    authService = authService,
                    broadcaster = broadcaster,
                )
            }

            val client = createClient { install(ClientWebSockets) }

            runBlocking {
                client.webSocket("/v1/messages/stream", request = {
                    header(HttpHeaders.Authorization, "Bearer $viewerToken")
                }) {
                    val frame =
                        receiveWhileEmitting {
                            broadcaster.emit(
                                MessageRealtimeDelta(
                                    recipientUserId = viewerId,
                                    type = MessageDeltaType.MESSAGE_CREATED,
                                    conversationId = conversationId,
                                    occurredAt = Instant.parse("2026-05-22T16:00:00Z"),
                                    messageId = messageId,
                                    actorUserId = actorId,
                                    actorDisplayName = "Maya",
                                    expiresAt = Instant.parse("2026-05-22T16:00:06Z"),
                                ),
                            )
                        }
                    val text = (frame as? Frame.Text)?.readText() ?: fail("Expected text frame")
                    val delta = streamJson.decodeFromString(MessageDeltaDto.serializer(), text)
                    assertEquals(MessageDeltaType.MESSAGE_CREATED, delta.type)
                    assertEquals(conversationId.toString(), delta.conversationId)
                    assertEquals(messageId.toString(), delta.messageId)
                    assertEquals(actorId.toString(), delta.actorUserId)
                    assertEquals("Maya", delta.actorDisplayName)
                    assertEquals("2026-05-22T16:00:06Z", delta.expiresAt)
                    assertEquals("2026-05-22T16:00:00Z", delta.occurredAt)
                }
            }
        }

    @Test
    fun firstFrameAuthDeliversDeltasToBrowserStyleClients() =
        testApplication {
            val viewerId = UUID.randomUUID()
            val conversationId = UUID.randomUUID()
            val viewerToken = "viewer-token"
            val authService = singleSessionAuthService(viewerToken, viewerId)
            val broadcaster = MessageBroadcaster()

            application {
                configureMessageStreamRoutes(
                    authService = authService,
                    broadcaster = broadcaster,
                )
            }

            val client = createClient { install(ClientWebSockets) }

            runBlocking {
                // No Authorization header — browsers cannot set WebSocket headers.
                client.webSocket("/v1/messages/stream") {
                    send(Frame.Text("""{"type":"auth","token":"$viewerToken"}"""))
                    val frame =
                        receiveWhileEmitting {
                            broadcaster.emit(
                                MessageRealtimeDelta(
                                    recipientUserId = viewerId,
                                    type = MessageDeltaType.MESSAGE_CREATED,
                                    conversationId = conversationId,
                                    occurredAt = Instant.parse("2026-05-22T16:00:00Z"),
                                ),
                            )
                        }
                    val text = (frame as? Frame.Text)?.readText() ?: fail("Expected text frame")
                    val delta = streamJson.decodeFromString(MessageDeltaDto.serializer(), text)
                    assertEquals(MessageDeltaType.MESSAGE_CREATED, delta.type)
                    assertEquals(conversationId.toString(), delta.conversationId)
                }
            }
        }

    @Test
    fun malformedFirstFrameClosesTheSocketUnauthenticated() =
        testApplication {
            val viewerToken = "viewer-token"
            val authService = singleSessionAuthService(viewerToken, UUID.randomUUID())
            val broadcaster = MessageBroadcaster()

            application {
                configureMessageStreamRoutes(
                    authService = authService,
                    broadcaster = broadcaster,
                )
            }

            val client = createClient { install(ClientWebSockets) }

            runBlocking {
                client.webSocket("/v1/messages/stream") {
                    send(Frame.Text("""{"not":"an auth frame"}"""))
                    val reason = withTimeout(2_000) { closeReason.await() }
                    assertEquals("UNAUTHENTICATED", reason?.message)
                }
            }
        }

    @Test
    fun otherUsersMessageDeltaIsNotDelivered() =
        testApplication {
            val viewerId = UUID.randomUUID()
            val otherUserId = UUID.randomUUID()
            val viewerToken = "viewer-token"
            val authService = singleSessionAuthService(viewerToken, viewerId)
            val broadcaster = MessageBroadcaster()

            application {
                configureMessageStreamRoutes(
                    authService = authService,
                    broadcaster = broadcaster,
                )
            }

            val client = createClient { install(ClientWebSockets) }

            runBlocking {
                client.webSocket("/v1/messages/stream", request = {
                    header(HttpHeaders.Authorization, "Bearer $viewerToken")
                }) {
                    val sentinelConversationId = UUID.randomUUID()
                    val first =
                        receiveWhileEmitting {
                            broadcaster.emit(
                                MessageRealtimeDelta(
                                    recipientUserId = otherUserId,
                                    type = MessageDeltaType.MESSAGE_DELETED,
                                    conversationId = UUID.randomUUID(),
                                    occurredAt = Instant.parse("2026-05-22T16:00:00Z"),
                                ),
                            )

                            broadcaster.emit(
                                MessageRealtimeDelta(
                                    recipientUserId = viewerId,
                                    type = MessageDeltaType.MESSAGE_DELETED,
                                    conversationId = sentinelConversationId,
                                    occurredAt = Instant.parse("2026-05-22T16:00:01Z"),
                                ),
                            )
                            // Sentinel pattern: a delta that must arrive is emitted right after the one that must not.
                            // The first frame must be the sentinel; if the earlier delta leaked, it would arrive first.
                            // (A tryReceive() check returns immediately and could never fail.)
                        }
                    val text = (first as? Frame.Text)?.readText() ?: fail("Expected text frame")
                    assertTrue(sentinelConversationId.toString() in text, "Expected the sentinel delta first, got: $text")
                    assertFalse("16:00:00Z" in text, "Message deltas must only be delivered to their recipient")
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
