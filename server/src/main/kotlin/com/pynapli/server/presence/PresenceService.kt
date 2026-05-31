package com.pynapli.server.presence

import com.pynapli.server.profiling.profile
import com.pynapli.shared.network.dto.PresenceDeltaDto
import com.pynapli.shared.network.dto.PresenceHeartbeatResponseDto
import java.time.Clock
import java.util.UUID

class PresenceService(
    private val repository: PresenceRepository,
    private val broadcaster: PresenceBroadcaster? = null,
    private val onlineTracker: OnlinePresenceTracker? = null,
    private val clock: Clock = Clock.systemUTC(),
) {
    suspend fun heartbeat(userId: UUID): PresenceHeartbeatResponseDto {
        val state = profile("presence.repository.heartbeat") { repository.heartbeat(userId) }
        profile("presence.broadcast") {
            broadcaster?.emit(
                PresenceDeltaDto(
                    userId = userId.toString(),
                    online = true,
                    lastActiveAt = clock.instant().toString(),
                ),
            )
        }
        profile("presence.tracker") { onlineTracker?.markOnline(userId) }
        return PresenceHeartbeatResponseDto(
            online = state.online,
            expiresInSeconds = state.expiresInSeconds,
        )
    }

    suspend fun refreshOnline(userId: UUID) {
        profile("presence.repository.refresh") { repository.heartbeat(userId) }
        profile("presence.trackerRefresh") { onlineTracker?.markOnline(userId) }
    }

    suspend fun onlineUserIds(userIds: Collection<UUID>): Set<UUID> = profile("presence.repository.onlineUserIds") { repository.onlineUserIds(userIds) }
}
