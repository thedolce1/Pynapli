package com.pynapli.server.presence

import com.pynapli.shared.network.dto.PresenceDeltaDto
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

class PresenceBroadcaster(
    extraBufferCapacity: Int = DEFAULT_BUFFER_CAPACITY
) {
    private val _deltas = MutableSharedFlow<PresenceDeltaDto>(
        replay = 0,
        extraBufferCapacity = extraBufferCapacity
    )
    val deltas: SharedFlow<PresenceDeltaDto> = _deltas.asSharedFlow()

    suspend fun emit(delta: PresenceDeltaDto) {
        _deltas.emit(delta)
    }

    companion object {
        const val DEFAULT_BUFFER_CAPACITY: Int = 256
    }
}
