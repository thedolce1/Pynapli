package com.pynapli.shared.network

import com.pynapli.shared.network.dto.MessageDeltaDto
import com.pynapli.shared.network.dto.MessageDeltaType
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class MessageStreamClientTest {
    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    @Test
    fun backoffDelayGrowsExponentiallyAndCaps() {
        val backoff = MessageStreamBackoff(initialDelayMillis = 1_000, maxDelayMillis = 16_000, factor = 2.0)
        assertEquals(1_000, backoff.delayMillisFor(0))
        assertEquals(2_000, backoff.delayMillisFor(1))
        assertEquals(4_000, backoff.delayMillisFor(2))
        assertEquals(8_000, backoff.delayMillisFor(3))
        assertEquals(16_000, backoff.delayMillisFor(4))
        assertEquals(16_000, backoff.delayMillisFor(99))
    }

    @Test
    fun emitsDeltasFromConnectedSession() =
        runBlocking {
            val session =
                ScriptedMessageSession(
                    scriptedReceives =
                        listOf(
                            json.encodeToString(
                                MessageDeltaDto.serializer(),
                                MessageDeltaDto(
                                    type = MessageDeltaType.MESSAGE_CREATED,
                                    conversationId = "conversation-1",
                                    occurredAt = "2026-05-22T16:00:00Z",
                                    messageId = "message-1",
                                ),
                            ),
                            json.encodeToString(
                                MessageDeltaDto.serializer(),
                                MessageDeltaDto(
                                    type = MessageDeltaType.TYPING_STARTED,
                                    conversationId = "conversation-1",
                                    occurredAt = "2026-05-22T16:00:01Z",
                                    actorUserId = "target",
                                    actorDisplayName = "Maya",
                                    expiresAt = "2026-05-22T16:00:07Z",
                                ),
                            ),
                        ),
                )
            val factory = ScriptedMessageSessionFactory(sessions = listOf(session))
            val client =
                MessageStreamClient(
                    sessionFactory = factory,
                    tokenProvider = StaticAccessTokenProvider("test-token"),
                    json = json,
                )

            val deltas =
                withTimeout(2_000) {
                    client.connect().take(2).toList()
                }

            assertEquals(listOf(MessageDeltaType.MESSAGE_CREATED, MessageDeltaType.TYPING_STARTED), deltas.map { it.type })
            assertEquals(listOf("conversation-1", "conversation-1"), deltas.map { it.conversationId })
            assertEquals("target", deltas.last().actorUserId)
            assertEquals("Maya", deltas.last().actorDisplayName)
            assertEquals("2026-05-22T16:00:07Z", deltas.last().expiresAt)
            assertEquals("test-token", factory.tokensSeen.first())
        }

    @Test
    fun reconnectsAfterTransientFailure() =
        runBlocking {
            val firstSession = ScriptedMessageSession(scriptedReceives = emptyList())
            val secondSession =
                ScriptedMessageSession(
                    scriptedReceives =
                        listOf(
                            json.encodeToString(
                                MessageDeltaDto.serializer(),
                                MessageDeltaDto(
                                    type = MessageDeltaType.MESSAGE_DELETED,
                                    conversationId = "conversation-1",
                                    occurredAt = "2026-05-22T16:00:00Z",
                                    messageId = "message-1",
                                ),
                            ),
                        ),
                )
            val factory = ScriptedMessageSessionFactory(sessions = listOf(firstSession, secondSession))
            val client =
                MessageStreamClient(
                    sessionFactory = factory,
                    tokenProvider = StaticAccessTokenProvider("test-token"),
                    backoff = MessageStreamBackoff(initialDelayMillis = 10, maxDelayMillis = 20, factor = 1.0),
                    json = json,
                )

            val deltas =
                withTimeout(3_000) {
                    client.connect().take(1).toList()
                }

            assertEquals(listOf(MessageDeltaType.MESSAGE_DELETED), deltas.map { it.type })
            assertEquals(2, factory.connectInvocations)
        }
}

private class ScriptedMessageSession(
    private val scriptedReceives: List<String>,
) : MessageStreamSession {
    private var receiveIndex = 0

    override suspend fun receiveText(): String? {
        if (receiveIndex >= scriptedReceives.size) return null
        val next = scriptedReceives[receiveIndex]
        receiveIndex += 1
        return next
    }

    override suspend fun close() {}
}

private class ScriptedMessageSessionFactory(
    private val sessions: List<ScriptedMessageSession>,
) : MessageStreamSessionFactory {
    val tokensSeen = mutableListOf<String>()
    var connectInvocations: Int = 0
        private set

    override suspend fun connect(token: String): MessageStreamSession {
        tokensSeen += token
        val session = sessions.getOrElse(connectInvocations) { sessions.last() }
        connectInvocations += 1
        return session
    }
}
