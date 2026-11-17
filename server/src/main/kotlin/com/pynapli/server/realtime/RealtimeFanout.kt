package com.pynapli.server.realtime

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * The delivery core every realtime broadcaster shares:
 *
 * - **Never blocks the emitter.** The local flow drops its oldest delta when full instead of
 *   suspending, so a request that produces a delta can't be stalled by sockets that are behind.
 *   (Each socket also reads through its own bounded buffer; see [PER_CONNECTION_BUFFER].)
 * - **Crosses replicas.** Each delta is delivered here and published on [relay]; deltas other
 *   replicas publish are delivered here. Every replica tags what it publishes with its own id and
 *   skips its own echo, so a delta reaches local sockets exactly once.
 */
class RealtimeFanout<T>(
    topic: String,
    private val serializer: KSerializer<T>,
    private val relay: RealtimeRelay,
    extraBufferCapacity: Int,
    replay: Int = 0,
) {
    private val logger = LoggerFactory.getLogger(RealtimeFanout::class.java)
    private val channel = "pynapli:realtime:$topic"
    private val originId = UUID.randomUUID().toString()
    private val local =
        MutableSharedFlow<T>(
            replay = replay,
            extraBufferCapacity = extraBufferCapacity,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

    val deltas: SharedFlow<T> = local.asSharedFlow()
    val subscriptionCount: StateFlow<Int> = local.subscriptionCount

    init {
        relay.subscribe(channel) { raw ->
            val envelope = runCatching { relayJson.decodeFromString(RelayEnvelope.serializer(), raw) }.getOrNull() ?: return@subscribe
            if (envelope.origin == originId) return@subscribe
            val delta =
                runCatching { relayJson.decodeFromString(serializer, envelope.payload) }
                    .onFailure { error -> logger.debug("Dropped undecodable relay delta on {}: {}", channel, error.message) }
                    .getOrNull() ?: return@subscribe
            local.tryEmit(delta)
        }
    }

    fun emit(delta: T) {
        local.tryEmit(delta)
        val payload = runCatching { relayJson.encodeToString(serializer, delta) }.getOrNull() ?: return
        relay.publish(channel, relayJson.encodeToString(RelayEnvelope.serializer(), RelayEnvelope(originId, payload)))
    }

    companion object {
        /** Deltas a single socket may fall behind by before its oldest are dropped. */
        const val PER_CONNECTION_BUFFER: Int = 64
    }
}

@Serializable
private data class RelayEnvelope(
    val origin: String,
    val payload: String,
)

private val relayJson =
    Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
