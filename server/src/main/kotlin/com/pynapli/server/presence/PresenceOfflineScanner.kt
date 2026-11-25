package com.pynapli.server.presence

import com.pynapli.shared.network.dto.PresenceDeltaDto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.time.Clock
import java.util.UUID

class PresenceOfflineScanner(
    private val tracker: OnlinePresenceTracker,
    private val repository: PresenceRepository,
    private val broadcaster: PresenceBroadcaster,
    private val intervalMillis: Long = DEFAULT_INTERVAL_MILLIS,
    private val clock: Clock = Clock.systemUTC(),
    // Same gate as PresenceService: an incognito member's "went offline" never goes out. Fails closed.
    private val broadcastAllowed: (suspend (UUID) -> Boolean)? = null,
) : AutoCloseable {
    private val logger = LoggerFactory.getLogger(PresenceOfflineScanner::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    fun start() {
        if (job != null) return
        job =
            scope.launch {
                while (isActive) {
                    runCatching { scanOnce() }.onFailure { error ->
                        logger.warn("Presence offline scan failed", error)
                    }
                    delay(intervalMillis)
                }
            }
    }

    suspend fun scanOnce() {
        val tracked = tracker.snapshot()
        if (tracked.isEmpty()) return
        val stillOnline = repository.onlineUserIds(tracked)
        val gone: Set<UUID> = tracked - stillOnline
        if (gone.isEmpty()) return
        tracker.removeAll(gone)
        // Cut to the minute, like the "came online" delta (PresenceService).
        val now =
            clock
                .instant()
                .truncatedTo(java.time.temporal.ChronoUnit.MINUTES)
                .toString()
        gone.forEach { userId ->
            if (!runCatching { broadcastAllowed?.invoke(userId) ?: true }.getOrDefault(false)) return@forEach
            broadcaster.emit(
                PresenceDeltaDto(
                    userId = userId.toString(),
                    online = false,
                    lastActiveAt = now,
                ),
            )
        }
    }

    override fun close() {
        scope.cancel()
    }

    companion object {
        const val DEFAULT_INTERVAL_MILLIS: Long = 30_000L
    }
}
