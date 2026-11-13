package com.pynapli.server.realtime

import com.pynapli.server.messaging.MessageBroadcaster
import com.pynapli.server.messaging.MessageRealtimeDelta
import com.pynapli.server.testsupport.PynapliTestDatabase
import com.pynapli.shared.network.dto.MessageDeltaType
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Two API replicas sharing one Redis: a delta produced on replica A reaches a socket on replica B,
 * exactly once, and A's own sockets get it exactly once too (no echo from the relay).
 */
class RealtimeRelayIntegrationTest {
    @Test
    fun aDeltaEmittedOnOneReplicaReachesSocketsOnTheOtherExactlyOnce() =
        runBlocking<Unit> {
            val relayA = RedisRealtimeRelay(PynapliTestDatabase.redisUri())
            val relayB = RedisRealtimeRelay(PynapliTestDatabase.redisUri())
            try {
                val replicaA = MessageBroadcaster(relay = relayA)
                val replicaB = MessageBroadcaster(relay = relayB)
                val delta =
                    MessageRealtimeDelta(
                        recipientUserId = UUID.randomUUID(),
                        type = MessageDeltaType.MESSAGE_CREATED,
                        conversationId = UUID.randomUUID(),
                        occurredAt = Instant.parse("2026-11-13T12:00:00Z"),
                        messageId = UUID.randomUUID(),
                    )

                val onB = async(start = CoroutineStart.UNDISPATCHED) { withTimeout(5_000) { replicaB.deltas.first() } }
                val onA = async(start = CoroutineStart.UNDISPATCHED) { withTimeoutOrNull(1_500) { replicaA.deltas.take(2).toList() } }
                // Let both subscribers and the Redis SUBSCRIBE settle before publishing.
                delay(200)
                replicaA.emit(delta)

                assertEquals(delta, onB.await())
                // A sees its own delta once; a second copy (the relay echo) would complete take(2).
                assertNull(onA.await())
            } finally {
                relayA.close()
                relayB.close()
            }
        }

    @Test
    fun aSocketThatStopsReadingNeverHoldsUpTheRequestThatEmits() =
        runBlocking<Unit> {
            val broadcaster = MessageBroadcaster(extraBufferCapacity = 8)
            // A subscriber that takes one delta and then never reads again.
            val stuck =
                async(start = CoroutineStart.UNDISPATCHED) {
                    broadcaster.deltas.collect { delay(Long.MAX_VALUE) }
                }
            try {
                withTimeout(2_000) {
                    repeat(1_000) {
                        broadcaster.emit(
                            MessageRealtimeDelta(
                                recipientUserId = UUID.randomUUID(),
                                type = MessageDeltaType.MESSAGE_CREATED,
                                conversationId = UUID.randomUUID(),
                            ),
                        )
                    }
                }
            } finally {
                stuck.cancel()
            }
        }
}
