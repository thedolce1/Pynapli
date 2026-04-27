package com.pynapli.server.presence

import com.pynapli.shared.network.dto.PresenceHeartbeatResponseDto
import java.util.UUID

class PresenceService(
    private val repository: PresenceRepository
) {
    suspend fun heartbeat(userId: UUID): PresenceHeartbeatResponseDto {
        val state = repository.heartbeat(userId)
        return PresenceHeartbeatResponseDto(
            online = state.online,
            expiresInSeconds = state.expiresInSeconds
        )
    }

    suspend fun refreshOnline(userId: UUID) {
        repository.heartbeat(userId)
    }

    suspend fun onlineUserIds(userIds: Collection<UUID>): Set<UUID> = repository.onlineUserIds(userIds)
}
