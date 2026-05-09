package com.pynapli.server.presence

import com.pynapli.shared.network.dto.PresenceDeltaDto
import com.pynapli.shared.network.dto.PresenceHeartbeatResponseDto
import java.time.Clock
import java.util.UUID

class PresenceService(
    private val repository: PresenceRepository,
    private val broadcaster: PresenceBroadcaster? = null,
    private val onlineTracker: OnlinePresenceTracker? = null,
    private val clock: Clock = Clock.systemUTC()
) {
    suspend fun heartbeat(userId: UUID): PresenceHeartbeatResponseDto {
        val state = repository.heartbeat(userId)
        broadcaster?.emit(
            PresenceDeltaDto(
                userId = userId.toString(),
                online = true,
                lastActiveAt = clock.instant().toString()
            )
        )
        onlineTracker?.markOnline(userId)
        return PresenceHeartbeatResponseDto(
            online = state.online,
            expiresInSeconds = state.expiresInSeconds
        )
    }

    suspend fun refreshOnline(userId: UUID) {
        repository.heartbeat(userId)
        onlineTracker?.markOnline(userId)
    }

    suspend fun onlineUserIds(userIds: Collection<UUID>): Set<UUID> = repository.onlineUserIds(userIds)
}
