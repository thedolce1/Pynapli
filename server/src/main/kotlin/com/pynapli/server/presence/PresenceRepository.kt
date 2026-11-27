package com.pynapli.server.presence

import java.util.UUID

interface PresenceRepository {
    suspend fun heartbeat(userId: UUID): PresenceState

    suspend fun onlineUserIds(userIds: Collection<UUID>): Set<UUID>

    /** Drops the online key at once (logout, account deletion). */
    suspend fun clear(userId: UUID) {}

    /**
     * Short-lived "presence ended at" tombstone (logout, account deletion), so activity from before it
     * that is still queued can't bring presence back (PresenceService.refreshOnline).
     */
    suspend fun markPresenceEnded(
        userId: UUID,
        at: java.time.Instant,
    ) {}

    /** The tombstone's time, or null when there is none (never set, or expired). */
    suspend fun presenceEndedAt(userId: UUID): java.time.Instant? = null
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
