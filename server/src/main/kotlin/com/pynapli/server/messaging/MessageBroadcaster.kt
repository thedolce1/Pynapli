package com.pynapli.server.messaging

import com.pynapli.shared.network.dto.MessageDeltaDto
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.time.Instant
import java.util.UUID

class MessageBroadcaster(
    extraBufferCapacity: Int = DEFAULT_BUFFER_CAPACITY,
) {
    private val _deltas =
        MutableSharedFlow<MessageRealtimeDelta>(
            replay = 0,
            extraBufferCapacity = extraBufferCapacity,
        )
    val deltas: SharedFlow<MessageRealtimeDelta> = _deltas.asSharedFlow()

    suspend fun emit(delta: MessageRealtimeDelta) {
        _deltas.emit(delta)
    }

    companion object {
        const val DEFAULT_BUFFER_CAPACITY: Int = 256
    }
}

data class MessageRealtimeDelta(
    val recipientUserId: UUID,
    val type: String,
    val conversationId: UUID,
    val occurredAt: Instant = Instant.now(),
    val messageId: UUID? = null,
    val actorUserId: UUID? = null,
    val actorDisplayName: String? = null,
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
