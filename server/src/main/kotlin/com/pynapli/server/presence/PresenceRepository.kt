package com.pynapli.server.presence

import java.util.UUID

interface PresenceRepository {
    suspend fun heartbeat(userId: UUID): PresenceState

    suspend fun onlineUserIds(userIds: Collection<UUID>): Set<UUID>
}

/**
 * [cameOnline] is true when this heartbeat found no live presence key, i.e. the person was offline a
 * moment ago. It drives the stream's "came online" delta; a repository that cannot tell says false.
 */
data class PresenceState(
    val online: Boolean,
    val expiresInSeconds: Int,
    val cameOnline: Boolean = false,
)
