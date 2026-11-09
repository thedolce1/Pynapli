package com.pynapli.shared.network

import com.pynapli.shared.network.dto.ActivityDeltaDto
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
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
        runTest {
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
        runTest {
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

    // Regression: the server authenticates after the WebSocket upgrade and closes normally on
    // rejection (e.g. an expired access token). The client used to treat the successful upgrade as
    // healthy, reset its backoff, and reconnect with no delay — a tight loop. Every session end must
    // now back off unless the session delivered frames or stayed up.
    @Test
    fun backsOffWhenServerClosesImmediatelyWithoutFrames() =
        runTest {
            val rejected = ScriptedActivitySession(scriptedReceives = emptyList())
            val factory = ScriptedActivitySessionFactory(sessions = listOf(rejected))
            val client =
                ActivityStreamClient(
                    sessionFactory = factory,
                    tokenProvider = StaticAccessTokenProvider("expired-token"),
                    backoff = ActivityStreamBackoff(initialDelayMillis = 100, maxDelayMillis = 100, factor = 1.0),
                    json = json,
                )

            // Runs on the test scheduler, so the backoff delays are virtual and the count is exact:
            // connects at t = 0, 100, 200 and 300 ms. The old tight loop made thousands.
            val job = launch { client.connect().collect { } }
            advanceTimeBy(350)
            job.cancelAndJoin()

            assertEquals(4, factory.connectInvocations, "connects=${factory.connectInvocations}")
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
