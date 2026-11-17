package com.pynapli.server.presence

import com.pynapli.server.profiling.profile
import java.util.UUID

class PresenceService(
    private val repository: PresenceRepository,
    private val onlineTracker: OnlinePresenceTracker? = null,
) {
    suspend fun refreshOnline(userId: UUID) {
        profile("presence.repository.refresh") { repository.heartbeat(userId) }
        profile("presence.trackerRefresh") { onlineTracker?.markOnline(userId) }
    }

    suspend fun onlineUserIds(userIds: Collection<UUID>): Set<UUID> = profile("presence.repository.onlineUserIds") { repository.onlineUserIds(userIds) }
}
