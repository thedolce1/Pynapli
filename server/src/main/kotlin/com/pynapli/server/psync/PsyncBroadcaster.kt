package com.pynapli.server.psync

import com.pynapli.shared.network.dto.PsyncRealtimeDeltaDto
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.time.Instant
import java.util.UUID

/**
 * PS-4b convenience realtime channel for Psync sessions. Mirrors [ActivityBroadcaster]:
 * a fan-out [SharedFlow] consumed by the `/v1/psync/stream` WebSocket, filtered per
 * recipient. Deltas are convenience-only — they signal that session state changed so the
 * client can refetch canonical HTTP state. They NEVER carry swipe-by-swipe detail.
 */
class PsyncBroadcaster(
    extraBufferCapacity: Int = DEFAULT_BUFFER_CAPACITY,
    replay: Int = 0,
) {
    private val _deltas =
        MutableSharedFlow<PsyncRealtimeDelta>(
            replay = replay,
            extraBufferCapacity = extraBufferCapacity,
        )
    val deltas: SharedFlow<PsyncRealtimeDelta> = _deltas.asSharedFlow()

    suspend fun emit(delta: PsyncRealtimeDelta) {
        _deltas.emit(delta)
    }

    companion object {
        const val DEFAULT_BUFFER_CAPACITY: Int = 256

        // Delta types. NOTHING here exposes an individual swipe — only session-level state.
        const val PARTNER_STARTED = "partner_started"
        const val SESSION_ACTIVE = "session_active"
        const val PARTNER_FINISHED = "partner_finished"
        const val SESSION_COMPLETED = "session_completed"
        const val SESSION_PAUSED = "session_paused"
        const val SESSION_RESUMED = "session_resumed"
        const val NUDGE = "nudge"

        // The setup changed before the deck started: invited, accepted, declined, filters
        // suggested, cancelled or expired. Carries no detail; the client refetches the session.
        const val SESSION_UPDATED = "session_updated"
    }
}

data class PsyncRealtimeDelta(
    val recipientUserId: UUID,
    val sessionId: UUID,
    val type: String,
    val occurredAt: Instant = Instant.now(),
) {
    fun toDto(): PsyncRealtimeDeltaDto =
        PsyncRealtimeDeltaDto(
            sessionId = sessionId.toString(),
            type = type,
            occurredAt = occurredAt.toString(),
        )
}
