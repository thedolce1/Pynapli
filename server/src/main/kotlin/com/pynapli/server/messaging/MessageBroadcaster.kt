package com.pynapli.server.messaging

import com.pynapli.server.realtime.InstantAsString
import com.pynapli.server.realtime.LocalOnlyRealtimeRelay
import com.pynapli.server.realtime.RealtimeFanout
import com.pynapli.server.realtime.RealtimeRelay
import com.pynapli.server.realtime.UuidAsString
import com.pynapli.shared.network.dto.MessageDeltaDto
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import java.time.Instant
import java.util.UUID

class MessageBroadcaster(
    extraBufferCapacity: Int = DEFAULT_BUFFER_CAPACITY,
    relay: RealtimeRelay = LocalOnlyRealtimeRelay,
) {
    private val fanout =
        RealtimeFanout(
            topic = "messages",
            serializer = MessageRealtimeDelta.serializer(),
            relay = relay,
            extraBufferCapacity = extraBufferCapacity,
            replay = 0,
        )
    val deltas: SharedFlow<MessageRealtimeDelta> = fanout.deltas

    /** Open sockets on this replica reading the stream (for /metrics). */
    val subscriptionCount: StateFlow<Int> = fanout.subscriptionCount

    suspend fun emit(delta: MessageRealtimeDelta) {
        fanout.emit(delta)
    }

    companion object {
        const val DEFAULT_BUFFER_CAPACITY: Int = 256
    }
}

@Serializable
data class MessageRealtimeDelta(
    @Serializable(with = UuidAsString::class)
    val recipientUserId: UUID,
    val type: String,
    @Serializable(with = UuidAsString::class)
    val conversationId: UUID,
    @Serializable(with = InstantAsString::class)
    val occurredAt: Instant = Instant.now(),
    @Serializable(with = UuidAsString::class)
    val messageId: UUID? = null,
    @Serializable(with = UuidAsString::class)
    val actorUserId: UUID? = null,
    val actorDisplayName: String? = null,
    @Serializable(with = InstantAsString::class)
    val expiresAt: Instant? = null,
) {
    fun toDto(): MessageDeltaDto =
        MessageDeltaDto(
            type = type,
            conversationId = conversationId.toString(),
            occurredAt = occurredAt.toString(),
            messageId = messageId?.toString(),
            actorUserId = actorUserId?.toString(),
            actorDisplayName = actorDisplayName,
            expiresAt = expiresAt?.toString(),
        )
}
