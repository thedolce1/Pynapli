package com.pynapli.server.activity

import com.pynapli.shared.network.dto.ActivityDeltaDto
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.time.Instant
import java.util.UUID

class ActivityBroadcaster(
    extraBufferCapacity: Int = DEFAULT_BUFFER_CAPACITY
) {
    private val _deltas = MutableSharedFlow<ActivityRealtimeDelta>(
        replay = 0,
        extraBufferCapacity = extraBufferCapacity
    )
    val deltas: SharedFlow<ActivityRealtimeDelta> = _deltas.asSharedFlow()

    suspend fun emit(delta: ActivityRealtimeDelta) {
        _deltas.emit(delta)
    }

    companion object {
        const val DEFAULT_BUFFER_CAPACITY: Int = 256
    }
}

data class ActivityRealtimeDelta(
    val recipientUserId: UUID,
    val type: String,
    val tab: String = ActivityFeedTab.fromEventType(type).value,
    val occurredAt: Instant = Instant.now(),
    val eventId: UUID? = null
) {
    fun toDto(): ActivityDeltaDto =
        ActivityDeltaDto(
            type = type,
            tab = tab,
            occurredAt = occurredAt.toString(),
            eventId = eventId?.toString()
        )
}
