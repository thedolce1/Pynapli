package com.pynapli.server.presence

import java.util.UUID

interface PresenceRepository {
    suspend fun heartbeat(userId: UUID): PresenceState

    suspend fun onlineUserIds(userIds: Collection<UUID>): Set<UUID>
}

data class PresenceState(
    val online: Boolean,
    val expiresInSeconds: Int,
)
