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
    // Same gate as PresenceService (PresenceVisibility): only members the Grid could show. Fails closed.
    private val broadcastAllowed: (suspend (UUID) -> Boolean)? = null,
    // Random delay before each "went offline" (privacy review F2); tests pass { 0 } for immediacy.
    private val offlineJitterMillis: () -> Long = { kotlin.random.Random.nextLong(0, MAX_OFFLINE_JITTER_MILLIS + 1) },
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
        gone.forEach { userId ->
            // Privacy review F2: each "went offline" waits its own random 0–60 s, so a subscriber can't
            // tell from the arrival time exactly when the 15-minute key lapsed (and so when the app was
            // last used, to the second). Came-online stays real time by product choice.
            val wait = offlineJitterMillis().coerceAtLeast(0)
            if (wait == 0L) {
                emitOffline(userId, recheck = false)
            } else {
                scope.launch {
                    delay(wait)
                    runCatching { emitOffline(userId, recheck = true) }
                }
            }
        }
    }

    private suspend fun emitOffline(
        userId: UUID,
        recheck: Boolean,
    ) {
        // Back within the delay: no offline delta (their refresh sent "came online" only if the key had
        // lapsed, which it had, so the stream is consistent either way).
        if (recheck && userId in repository.onlineUserIds(listOf(userId))) return
        // Fails closed: only members the Grid could show (PresenceVisibility).
        if (!runCatching { broadcastAllowed?.invoke(userId) ?: true }.getOrDefault(false)) return
        broadcaster.emit(
            PresenceDeltaDto(
                userId = userId.toString(),
                online = false,
                // The emission minute, so the field adds nothing to the arrival time.
                lastActiveAt =
                    clock
                        .instant()
                        .truncatedTo(java.time.temporal.ChronoUnit.MINUTES)
                        .toString(),
            ),
        )
    }

    override fun close() {
        scope.cancel()
    }

    companion object {
        const val DEFAULT_INTERVAL_MILLIS: Long = 30_000L
        const val MAX_OFFLINE_JITTER_MILLIS: Long = 60_000L
    }
}
