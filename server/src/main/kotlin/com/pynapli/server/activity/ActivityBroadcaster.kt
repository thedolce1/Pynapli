package com.pynapli.server.activity

import com.pynapli.server.realtime.InstantAsString
import com.pynapli.server.realtime.LocalOnlyRealtimeRelay
import com.pynapli.server.realtime.RealtimeFanout
import com.pynapli.server.realtime.RealtimeRelay
import com.pynapli.server.realtime.UuidAsString
import com.pynapli.shared.network.dto.ActivityDeltaDto
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.serialization.Serializable
import java.time.Instant
import java.util.UUID

class ActivityBroadcaster(
    extraBufferCapacity: Int = DEFAULT_BUFFER_CAPACITY,
    relay: RealtimeRelay = LocalOnlyRealtimeRelay,
) {
    private val fanout =
        RealtimeFanout(
            topic = "activity",
            serializer = ActivityRealtimeDelta.serializer(),
            relay = relay,
            extraBufferCapacity = extraBufferCapacity,
            replay = 0,
        )
    val deltas: SharedFlow<ActivityRealtimeDelta> = fanout.deltas

    suspend fun emit(delta: ActivityRealtimeDelta) {
        fanout.emit(delta)
    }

    companion object {
        const val DEFAULT_BUFFER_CAPACITY: Int = 256
    }
}

@Serializable
data class ActivityRealtimeDelta(
    @Serializable(with = UuidAsString::class)
    val recipientUserId: UUID,
    val type: String,
    val tab: String = ActivityFeedTab.fromEventType(type).value,
    @Serializable(with = InstantAsString::class)
    val occurredAt: Instant = Instant.now(),
    @Serializable(with = UuidAsString::class)
    val eventId: UUID? = null,
) {
    fun toDto(): ActivityDeltaDto =
        ActivityDeltaDto(
            type = type,
            tab = tab,
            occurredAt = occurredAt.toString(),
            eventId = eventId?.toString(),
        )
}
