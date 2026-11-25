package com.pynapli.server.presence

import com.pynapli.server.profiling.profile
import com.pynapli.shared.network.dto.PresenceDeltaDto
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Presence (GRID_IMPLEMENTATION_SPEC "Presence States", plan G2). Any authenticated HTTP request, a
 * realtime socket opening, and `POST /v1/presence/heartbeat` call [touch] (re-auth ticks don't); at
 * most one [refreshOnline] per user per [refreshInterval] per instance actually writes (a Redis
 * SET/GET with the 15-minute TTL, the in-memory tracker for the offline scanner, and the durable
 * last-active row). A refresh that finds the person offline emits a "came online" delta; the offline
 * scanner emits "went offline" when the key expires.
 */
class PresenceService(
    private val repository: PresenceRepository,
    private val onlineTracker: OnlinePresenceTracker? = null,
    private val lastActiveRecorder: LastActiveRecorder? = null,
    private val broadcaster: PresenceBroadcaster? = null,
    private val clock: Clock = Clock.systemUTC(),
    private val refreshInterval: Duration = DEFAULT_REFRESH_INTERVAL,
    /**
     * Whether [userId]'s online/offline transitions may go out on the presence stream: false for a
     * member hiding their activity (incognito). The stream accepts any subscribed id, so this gate is
     * what keeps an incognito member's comings and goings off it. Null (tests) allows everyone.
     */
    private val broadcastAllowed: (suspend (UUID) -> Boolean)? = null,
) {
    private val lastRefreshed = ConcurrentHashMap<UUID, Instant>()

    /**
     * Claims this user's next refresh slot without suspending: true at most once per
     * [refreshInterval]. Lets the auth hook skip launching any work for the common case.
     */
    fun claimRefresh(userId: UUID): Boolean {
        val now = clock.instant()
        if (lastRefreshed.size > MAX_TRACKED_USERS) lastRefreshed.clear()
        var claimed = false
        lastRefreshed.compute(userId) { _, previous ->
            if (previous == null || Duration.between(previous, now) >= refreshInterval) {
                claimed = true
                now
            } else {
                previous
            }
        }
        return claimed
    }

    /** Throttled mark-online for request paths. Best-effort: a presence failure never fails the caller. */
    suspend fun touch(userId: UUID) {
        if (!claimRefresh(userId)) return
        runCatching { refreshOnline(userId) }
    }

    /** Unthrottled mark-online. Prefer [touch] on request paths. */
    suspend fun refreshOnline(userId: UUID) {
        val state = profile("presence.repository.refresh") { repository.heartbeat(userId) }
        profile("presence.trackerRefresh") { onlineTracker?.markOnline(userId) }
        profile("presence.lastActive") { lastActiveRecorder?.record(userId) }
        // Fail closed: if the privacy check errors, say nothing.
        if (state.cameOnline && runCatching { broadcastAllowed?.invoke(userId) ?: true }.getOrDefault(false)) {
            // Same shape as the offline delta. The time is cut to the minute: the delta itself is the
            // live signal, so a finer timestamp would tell a subscriber nothing it needs.
            runCatching {
                broadcaster?.emit(
                    PresenceDeltaDto(
                        userId = userId.toString(),
                        online = true,
                        lastActiveAt = clock.instant().truncatedTo(ChronoUnit.MINUTES).toString(),
                    ),
                )
            }
        }
    }

    /**
     * Logout or account deletion (privacy review F3): delete the online key now and release the refresh
     * claim. Broadcasts nothing itself; the offline scanner later sends its ordinary jittered,
     * eligibility-gated "went offline" (nothing at all for a deleted account).
     */
    suspend fun endPresence(userId: UUID) {
        lastRefreshed.remove(userId)
        repository.clear(userId)
    }

    suspend fun onlineUserIds(userIds: Collection<UUID>): Set<UUID> = profile("presence.repository.onlineUserIds") { repository.onlineUserIds(userIds) }

    companion object {
        val DEFAULT_REFRESH_INTERVAL: Duration = Duration.ofSeconds(60)
        private const val MAX_TRACKED_USERS = 100_000
    }
}
