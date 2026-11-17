package com.pynapli.server.presence

import com.pynapli.server.realtime.LocalOnlyRealtimeRelay
import com.pynapli.server.realtime.RealtimeFanout
import com.pynapli.server.realtime.RealtimeRelay
import com.pynapli.shared.network.dto.PresenceDeltaDto
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

class PresenceBroadcaster(
    extraBufferCapacity: Int = DEFAULT_BUFFER_CAPACITY,
    relay: RealtimeRelay = LocalOnlyRealtimeRelay,
) {
    private val fanout =
        RealtimeFanout(
            topic = "presence",
            serializer = PresenceDeltaDto.serializer(),
            relay = relay,
            extraBufferCapacity = extraBufferCapacity,
            replay = 0,
        )
    val deltas: SharedFlow<PresenceDeltaDto> = fanout.deltas

    /** Live count of active subscribers — useful for connection metrics and for tests to await readiness. */
    val subscriptionCount: StateFlow<Int> = fanout.subscriptionCount

    suspend fun emit(delta: PresenceDeltaDto) {
        fanout.emit(delta)
    }

    companion object {
        const val DEFAULT_BUFFER_CAPACITY: Int = 256
    }
}
