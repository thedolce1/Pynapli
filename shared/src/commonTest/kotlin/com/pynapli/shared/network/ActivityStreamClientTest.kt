package com.pynapli.shared.network

import com.pynapli.shared.network.dto.ActivityDeltaDto
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class ActivityStreamClientTest {
    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    @Test
    fun backoffDelayGrowsExponentiallyAndCaps() {
        val backoff = ActivityStreamBackoff(initialDelayMillis = 1_000, maxDelayMillis = 16_000, factor = 2.0)
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
                ScriptedActivitySession(
                    scriptedReceives =
                        listOf(
                            json.encodeToString(
                                ActivityDeltaDto.serializer(),
                                ActivityDeltaDto(type = "flirt", tab = "flirts", occurredAt = "2026-05-20T16:00:00Z"),
                            ),
                            json.encodeToString(
                                ActivityDeltaDto.serializer(),
                                ActivityDeltaDto(type = "match", tab = "matches", occurredAt = "2026-05-20T16:00:01Z"),
                            ),
                        ),
                )
            val factory = ScriptedActivitySessionFactory(sessions = listOf(session))
            val client =
                ActivityStreamClient(
                    sessionFactory = factory,
                    tokenProvider = StaticAccessTokenProvider("test-token"),
                    json = json,
                )

            val deltas =
                withTimeout(2_000) {
                    client.connect().take(2).toList()
                }

            assertEquals(listOf("flirt", "match"), deltas.map { it.type })
            assertEquals(listOf("flirts", "matches"), deltas.map { it.tab })
            assertEquals("test-token", factory.tokensSeen.first())
        }

    @Test
    fun reconnectsAfterTransientFailure() =
        runBlocking {
            val firstSession = ScriptedActivitySession(scriptedReceives = emptyList())
            val secondSession =
                ScriptedActivitySession(
                    scriptedReceives =
                        listOf(
                            json.encodeToString(
                                ActivityDeltaDto.serializer(),
                                ActivityDeltaDto(type = "album_request", tab = "albums", occurredAt = "2026-05-20T16:00:00Z"),
                            ),
                        ),
                )
            val factory = ScriptedActivitySessionFactory(sessions = listOf(firstSession, secondSession))
            val client =
                ActivityStreamClient(
                    sessionFactory = factory,
                    tokenProvider = StaticAccessTokenProvider("test-token"),
                    backoff = ActivityStreamBackoff(initialDelayMillis = 10, maxDelayMillis = 20, factor = 1.0),
                    json = json,
                )

            val deltas =
                withTimeout(3_000) {
                    client.connect().take(1).toList()
                }

            assertEquals(listOf("album_request"), deltas.map { it.type })
            assertEquals(2, factory.connectInvocations)
        }
}

private class ScriptedActivitySession(
    private val scriptedReceives: List<String>,
) : ActivityStreamSession {
    private var receiveIndex = 0

    override suspend fun receiveText(): String? {
        if (receiveIndex >= scriptedReceives.size) return null
        val next = scriptedReceives[receiveIndex]
        receiveIndex += 1
        return next
    }

    override suspend fun close() {}
}

private class ScriptedActivitySessionFactory(
    private val sessions: List<ScriptedActivitySession>,
) : ActivityStreamSessionFactory {
    val tokensSeen = mutableListOf<String>()
    var connectInvocations: Int = 0
        private set

    override suspend fun connect(token: String): ActivityStreamSession {
        tokensSeen += token
        val session = sessions.getOrElse(connectInvocations) { sessions.last() }
        connectInvocations += 1
        return session
    }
}
