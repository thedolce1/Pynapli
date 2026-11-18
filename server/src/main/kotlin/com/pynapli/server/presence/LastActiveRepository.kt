package com.pynapli.server.presence

import com.pynapli.server.db.withConnection
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.sql.DataSource

/**
 * Durable "last active" signal behind the coarse last-seen buckets (V106). The timestamp only ever
 * feeds a server-side bucket; it is never returned to another user.
 */
interface LastActiveRepository {
    suspend fun record(
        userId: UUID,
        at: Instant,
    )

    /** What another person may learn about [userId]'s recent activity, before bucketing. */
    suspend fun lookup(userId: UUID): LastActiveLookup
}

/**
 * [hidden] is true when the person browses incognito: they show no last seen at all. Otherwise
 * [lastActiveAt] is the later of the presence heartbeat and their newest live session's activity.
 */
data class LastActiveLookup(
    val hidden: Boolean,
    val lastActiveAt: Instant?,
)

class JdbcLastActiveRepository(
    private val dataSource: DataSource,
) : LastActiveRepository {
    override suspend fun record(
        userId: UUID,
        at: Instant,
    ) {
        dataSource.withConnection { connection ->
            connection
                .prepareStatement(
                    """
                    INSERT INTO user_last_active (user_id, last_active_at)
                    VALUES (?, ?)
                    ON CONFLICT (user_id) DO UPDATE
                    SET last_active_at = EXCLUDED.last_active_at
                    WHERE user_last_active.last_active_at < EXCLUDED.last_active_at
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, userId)
                    statement.setTimestamp(2, Timestamp.from(at))
                    statement.executeUpdate()
                }
        }
    }

    override suspend fun lookup(userId: UUID): LastActiveLookup =
        dataSource.withConnection { connection ->
            connection
                .prepareStatement(
                    """
                    SELECT ds.incognito,
                           GREATEST(
                               (SELECT ula.last_active_at FROM user_last_active ula WHERE ula.user_id = ds.user_id),
                               (SELECT max(us.last_seen_at) FROM user_sessions us
                                WHERE us.user_id = ds.user_id AND us.revoked_at IS NULL)
                           ) AS last_active_at
                    FROM discovery_settings ds
                    WHERE ds.user_id = ?
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, userId)
                    statement.executeQuery().use { rs ->
                        if (!rs.next()) return@use LastActiveLookup(hidden = true, lastActiveAt = null)
                        val incognito = rs.getBoolean("incognito")
                        LastActiveLookup(
                            hidden = incognito,
                            lastActiveAt = if (incognito) null else rs.getTimestamp("last_active_at")?.toInstant(),
                        )
                    }
                }
        }
}

/**
 * Writes the heartbeat through to [repository] at most once per [minInterval] per user, so a busy
 * Grid session costs one small upsert a minute, not one per query. Best-effort: a failed write is
 * dropped (the session timestamp still backs the bucket).
 */
class LastActiveRecorder(
    private val repository: LastActiveRepository,
    private val clock: Clock = Clock.systemUTC(),
    private val minInterval: Duration = Duration.ofMinutes(1),
) {
    private val lastWritten = ConcurrentHashMap<UUID, Instant>()

    suspend fun record(userId: UUID) {
        val now = clock.instant()
        val previous = lastWritten[userId]
        if (previous != null && Duration.between(previous, now) < minInterval) return
        if (lastWritten.size > MAX_TRACKED_USERS) lastWritten.clear()
        lastWritten[userId] = now
        runCatching { repository.record(userId, now) }
    }

    private companion object {
        const val MAX_TRACKED_USERS = 100_000
    }
}
