package com.pynapli.shared.network

import com.pynapli.shared.network.dto.PsyncRealtimeDeltaDto
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class PsyncStreamClientTest {
    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    @Test
    fun backoffDelayGrowsExponentiallyAndCaps() {
        val backoff = StreamBackoff(initialDelayMillis = 1_000, maxDelayMillis = 16_000, factor = 2.0)
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
                ScriptedPsyncSession(
                    scriptedReceives =
                        listOf(
                            json.encodeToString(
                                PsyncRealtimeDeltaDto.serializer(),
                                PsyncRealtimeDeltaDto("s-1", "partner_started", "2026-06-04T16:00:00Z"),
                            ),
                            json.encodeToString(
                                PsyncRealtimeDeltaDto.serializer(),
                                PsyncRealtimeDeltaDto("s-1", "session_active", "2026-06-04T16:00:01Z"),
                            ),
                        ),
                )
            val factory = ScriptedPsyncSessionFactory(sessions = listOf(session))
            val client =
                JsonStreamClient(
                    deserializer = PsyncRealtimeDeltaDto.serializer(),
                    sessionFactory = factory,
                    tokenProvider = StaticAccessTokenProvider("test-token"),
                    json = json,
                )

            val deltas =
                withTimeout(2_000) {
                    client.connect().take(2).toList()
                }

            assertEquals(listOf("partner_started", "session_active"), deltas.map { it.type })
            assertEquals(listOf("s-1", "s-1"), deltas.map { it.sessionId })
            assertEquals("test-token", factory.tokensSeen.first())
        }

    @Test
    fun reconnectsAfterTransientFailure() =
        runTest {
            val firstSession = ScriptedPsyncSession(scriptedReceives = emptyList())
            val secondSession =
                ScriptedPsyncSession(
                    scriptedReceives =
                        listOf(
                            json.encodeToString(
                                PsyncRealtimeDeltaDto.serializer(),
                                PsyncRealtimeDeltaDto("s-1", "nudge", "2026-06-04T16:00:00Z"),
                            ),
                        ),
                )
            val factory = ScriptedPsyncSessionFactory(sessions = listOf(firstSession, secondSession))
            val client =
                JsonStreamClient(
                    deserializer = PsyncRealtimeDeltaDto.serializer(),
                    sessionFactory = factory,
                    tokenProvider = StaticAccessTokenProvider("test-token"),
                    backoff = StreamBackoff(initialDelayMillis = 10, maxDelayMillis = 20, factor = 1.0),
                    json = json,
                )

            val deltas =
                withTimeout(3_000) {
                    client.connect().take(1).toList()
                }

            assertEquals(listOf("nudge"), deltas.map { it.type })
            assertEquals(2, factory.connectInvocations)
        }
}

private class ScriptedPsyncSession(
    private val scriptedReceives: List<String>,
) : StreamSession {
    override suspend fun send(text: String) {}

    private var receiveIndex = 0

    override suspend fun receiveText(): String? {
        if (receiveIndex >= scriptedReceives.size) return null
        val next = scriptedReceives[receiveIndex]
        receiveIndex += 1
        return next
    }

    override suspend fun close() {}
}

private class ScriptedPsyncSessionFactory(
    private val sessions: List<ScriptedPsyncSession>,
) : StreamSessionFactory {
    val tokensSeen = mutableListOf<String>()
    var connectInvocations: Int = 0
        private set

    override suspend fun connect(token: String): StreamSession {
        tokensSeen += token
        val session = sessions.getOrElse(connectInvocations) { sessions.last() }
        connectInvocations += 1
        return session
    }
}
