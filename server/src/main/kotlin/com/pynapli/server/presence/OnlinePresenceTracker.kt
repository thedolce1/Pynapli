package com.pynapli.server.presence

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/**
 * In-memory record of users seen online recently. The offline scanner reads/clears this
 * set; the presence service adds to it on every heartbeat. Survives only the process,
 * which matches the Redis presence TTL semantics.
 */
class OnlinePresenceTracker {
    private val mutex = Mutex()
    private val tracked = mutableSetOf<UUID>()

    suspend fun markOnline(userId: UUID) {
        mutex.withLock { tracked += userId }
    }

    suspend fun snapshot(): Set<UUID> = mutex.withLock { tracked.toSet() }

    suspend fun removeAll(userIds: Collection<UUID>) {
        if (userIds.isEmpty()) return
        mutex.withLock { tracked -= userIds.toSet() }
    }
}
