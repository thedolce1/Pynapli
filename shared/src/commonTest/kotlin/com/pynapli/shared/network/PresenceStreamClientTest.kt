package com.pynapli.shared.network

import com.pynapli.shared.network.dto.PresenceDeltaDto
import com.pynapli.shared.network.dto.PresenceStreamSubscribeDto
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class PresenceStreamClientTest {
    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    @Test
    fun backoffDelayGrowsExponentiallyAndCaps() {
        val backoff = PresenceStreamBackoff(initialDelayMillis = 1_000, maxDelayMillis = 16_000, factor = 2.0)
        assertEquals(1_000, backoff.delayMillisFor(0))
        assertEquals(2_000, backoff.delayMillisFor(1))
        assertEquals(4_000, backoff.delayMillisFor(2))
        assertEquals(8_000, backoff.delayMillisFor(3))
        assertEquals(16_000, backoff.delayMillisFor(4))
        assertEquals(16_000, backoff.delayMillisFor(99))
    }

    @Test
    fun emitsDeltasFromConnectedSession() =
        runTest {
            val session =
                ScriptedSession(
                    scriptedReceives =
                        listOf(
                            json.encodeToString(
                                PresenceDeltaDto.serializer(),
                                PresenceDeltaDto(userId = "u1", online = true, lastActiveAt = "2026-05-09T12:00:00Z"),
                            ),
                            json.encodeToString(
                                PresenceDeltaDto.serializer(),
                                PresenceDeltaDto(userId = "u2", online = true, lastActiveAt = "2026-05-09T12:00:01Z"),
                            ),
                        ),
                )
            val factory = ScriptedSessionFactory(sessions = listOf(session))
            val client =
                PresenceStreamClient(
                    sessionFactory = factory,
                    tokenProvider = StaticAccessTokenProvider("test-token"),
                    json = json,
                )

            val userIds = MutableStateFlow(setOf("u1", "u2"))
            val deltas =
                withTimeout(2_000) {
                    client.connect(userIds).take(2).toList()
                }

            assertEquals(listOf("u1", "u2"), deltas.map { it.userId })
            assertEquals(listOf(true, true), deltas.map { it.online })
            assertEquals("test-token", factory.tokensSeen.first())
        }

    @Test
    fun sendsSubscribeFrameOnConnectAndOnUserIdsUpdate() =
        runTest {
            val session = ScriptedSession(scriptedReceives = listOf("__hold_open__"))
            val factory = ScriptedSessionFactory(sessions = listOf(session))
            val client =
                PresenceStreamClient(
                    sessionFactory = factory,
                    tokenProvider = StaticAccessTokenProvider("test-token"),
                    json = json,
                )

            val userIds = MutableStateFlow(setOf("u1"))
            coroutineScope {
                val collectorJob =
                    client
                        .connect(userIds)
                        .onEach { /* ignore */ }
                        .launchIn(this)
                withTimeout(1_000) {
                    while (session.sentMessages.size < 1) delay(5)
                }
                userIds.value = setOf("u1", "u2")
                withTimeout(1_000) {
                    while (session.sentMessages.size < 2) delay(5)
                }
                collectorJob.cancel()
            }

            val firstSubscribe = json.decodeFromString(PresenceStreamSubscribeDto.serializer(), session.sentMessages[0])
            val secondSubscribe = json.decodeFromString(PresenceStreamSubscribeDto.serializer(), session.sentMessages[1])
            assertEquals(listOf("u1"), firstSubscribe.userIds)
            assertEquals(setOf("u1", "u2"), secondSubscribe.userIds.toSet())
        }

    @Test
    fun reconnectsAfterTransientFailure() =
        runTest {
            val firstSession = ScriptedSession(scriptedReceives = emptyList())
            val secondSession =
                ScriptedSession(
                    scriptedReceives =
                        listOf(
                            json.encodeToString(
                                PresenceDeltaDto.serializer(),
                                PresenceDeltaDto(userId = "u-after-reconnect", online = true, lastActiveAt = "2026-05-09T12:00:00Z"),
                            ),
                        ),
                )
            val factory = ScriptedSessionFactory(sessions = listOf(firstSession, secondSession))
            val client =
                PresenceStreamClient(
                    sessionFactory = factory,
                    tokenProvider = StaticAccessTokenProvider("test-token"),
                    backoff = PresenceStreamBackoff(initialDelayMillis = 10, maxDelayMillis = 20, factor = 1.0),
                    json = json,
                )

            val userIds = MutableStateFlow(setOf("u-after-reconnect"))
            val deltas =
                withTimeout(3_000) {
                    client.connect(userIds).take(1).toList()
                }

            assertEquals(listOf("u-after-reconnect"), deltas.map { it.userId })
            assertEquals(2, factory.connectInvocations)
        }
}

private class ScriptedSession(
    private val scriptedReceives: List<String>,
) : PresenceStreamSession {
    val sentMessages = mutableListOf<String>()
    private var receiveIndex = 0

    override suspend fun send(text: String) {
        sentMessages += text
    }

    override suspend fun receiveText(): String? {
        if (receiveIndex >= scriptedReceives.size) return null
        val next = scriptedReceives[receiveIndex]
        receiveIndex += 1
        if (next == "__hold_open__") {
            // Hold the session open without delivering messages so the test can drive sends.
            kotlinx.coroutines.awaitCancellation()
        }
        return next
    }

    override suspend fun close() {}
}

private class ScriptedSessionFactory(
    private val sessions: List<ScriptedSession>,
) : PresenceStreamSessionFactory {
    val tokensSeen = mutableListOf<String>()
    var connectInvocations: Int = 0
        private set

    override suspend fun connect(token: String): PresenceStreamSession {
        tokensSeen += token
        val session = sessions.getOrElse(connectInvocations) { sessions.last() }
        connectInvocations += 1
        return session
    }
}
