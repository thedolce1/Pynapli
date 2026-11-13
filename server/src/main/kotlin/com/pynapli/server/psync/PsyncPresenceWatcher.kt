package com.pynapli.server.psync

import com.pynapli.server.dependencies.RedisCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.sql.DataSource

/** When each user's Psync stream was last seen open. Shared by every replica (Redis in production). */
interface PsyncLiveness {
    fun markSeen(
        userId: UUID,
        at: Instant,
    )

    fun lastSeen(userId: UUID): Instant?
}

class RedisPsyncLiveness(
    private val redis: RedisCache,
) : PsyncLiveness {
    override fun markSeen(
        userId: UUID,
        at: Instant,
    ) {
        redis.commands.setex(key(userId), RETENTION_SECONDS, at.toEpochMilli().toString())
    }

    override fun lastSeen(userId: UUID): Instant? =
        redis.commands
            .get(key(userId))
            ?.toLongOrNull()
            ?.let(Instant::ofEpochMilli)

    private fun key(userId: UUID) = "pynapli:psync:seen:$userId"

    private companion object {
        const val RETENTION_SECONDS = 24L * 60 * 60
    }
}

class InMemoryPsyncLiveness : PsyncLiveness {
    private val seen = ConcurrentHashMap<UUID, Instant>()

    override fun markSeen(
        userId: UUID,
        at: Instant,
    ) {
        seen[userId] = at
    }

    override fun lastSeen(userId: UUID): Instant? = seen[userId]
}

/**
 * Pauses a live Psync session when a partner drops off, and resumes it when they come back
 * (PSYNC_SPEC "If one partner disconnects: session pauses, state is preserved").
 *
 * The Psync stream marks the viewer seen every [heartbeat] while it is open. A sweep every
 * [sweepEvery] pauses an active session in which a participant who was connected during this
 * session has not been seen for [grace] (app closed, phone locked, signal lost), records who, and
 * tells both partners. When that partner's stream opens again the session resumes on its own.
 * Someone who never opened the stream this session is never paused for it.
 */
class PsyncPresenceWatcher(
    private val dataSource: DataSource,
    private val liveness: PsyncLiveness,
    private val broadcaster: PsyncBroadcaster?,
    private val clock: Clock = Clock.systemUTC(),
    val heartbeat: Duration = Duration.ofSeconds(15),
    private val grace: Duration = Duration.ofSeconds(90),
    private val sweepEvery: Duration = Duration.ofSeconds(30),
) : AutoCloseable {
    private val logger = LoggerFactory.getLogger(PsyncPresenceWatcher::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    /** The viewer's Psync stream is open: record it, and resume anything paused for their absence. */
    suspend fun onSeen(userId: UUID) {
        liveness.markSeen(userId, clock.instant())
        val resumed =
            withContext(Dispatchers.IO) {
                dataSource.connection.use { connection ->
                    connection
                        .prepareStatement(
                            """
                            UPDATE psync_sessions
                            SET status = 'active', auto_paused_user_id = NULL
                            WHERE status = 'paused'
                              AND auto_paused_user_id = ?
                              AND expires_at > now()
                            RETURNING id
                            """.trimIndent(),
                        ).use { statement ->
                            statement.setObject(1, userId)
                            statement.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getObject(1, UUID::class.java)) } }
                        }
                }
            }
        resumed.forEach { sessionId -> announce(sessionId, PsyncBroadcaster.SESSION_RESUMED) }
    }

    /** One sweep; returns how many sessions it paused. */
    suspend fun sweep(): Int {
        val now = clock.instant()
        val candidates =
            withContext(Dispatchers.IO) {
                dataSource.connection.use { connection ->
                    connection
                        .prepareStatement(
                            """
                            SELECT s.id, s.started_at, p.user_id
                            FROM psync_sessions s
                            JOIN psync_session_participants p ON p.session_id = s.id
                            WHERE s.status = 'active'
                              AND s.started_at IS NOT NULL
                              AND s.expires_at > now()
                            """.trimIndent(),
                        ).use { statement ->
                            statement.executeQuery().use { rs ->
                                buildList {
                                    while (rs.next()) {
                                        add(Triple(rs.getObject(1, UUID::class.java), rs.getTimestamp(2).toInstant(), rs.getObject(3, UUID::class.java)))
                                    }
                                }
                            }
                        }
                }
            }
        var paused = 0
        for ((sessionId, startedAt, userId) in candidates) {
            val lastSeen = liveness.lastSeen(userId) ?: continue
            val wasHereThisSession = !lastSeen.isBefore(startedAt)
            if (!wasHereThisSession || Duration.between(lastSeen, now) < grace) continue
            if (pauseFor(sessionId, userId)) {
                paused += 1
                announce(sessionId, PsyncBroadcaster.SESSION_PAUSED)
            }
        }
        return paused
    }

    private suspend fun pauseFor(
        sessionId: UUID,
        userId: UUID,
    ): Boolean =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection
                    .prepareStatement(
                        "UPDATE psync_sessions SET status = 'paused', auto_paused_user_id = ? WHERE id = ? AND status = 'active'",
                    ).use { statement ->
                        statement.setObject(1, userId)
                        statement.setObject(2, sessionId)
                        statement.executeUpdate() > 0
                    }
            }
        }

    private suspend fun announce(
        sessionId: UUID,
        type: String,
    ) {
        val b = broadcaster ?: return
        val participants =
            withContext(Dispatchers.IO) {
                dataSource.connection.use { connection ->
                    connection.prepareStatement("SELECT user_id FROM psync_session_participants WHERE session_id = ?").use { statement ->
                        statement.setObject(1, sessionId)
                        statement.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getObject(1, UUID::class.java)) } }
                    }
                }
            }
        val now = clock.instant()
        participants.forEach { b.emit(PsyncRealtimeDelta(recipientUserId = it, sessionId = sessionId, type = type, occurredAt = now)) }
    }

    fun start() {
        if (job != null) return
        job =
            scope.launch {
                while (isActive) {
                    runCatching {
                        val paused = sweep()
                        if (paused > 0) logger.info("Psync auto-paused {} session(s) after a partner disconnected", paused)
                    }.onFailure { error -> logger.warn("Psync presence sweep failed", error) }
                    delay(sweepEvery.toMillis())
                }
            }
    }

    override fun close() {
        scope.cancel()
    }
}
