package com.pynapli.server.psync

import com.pynapli.server.db.inTransaction
import com.pynapli.server.psync.PsyncSessionRepository.CompleteOutcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

internal suspend fun DataSource.completePsyncSession(
    json: Json,
    sessionId: UUID,
    userId: UUID,
    now: Instant,
    matchIdFactory: () -> UUID,
): CompleteOutcome =
    withContext(Dispatchers.IO) {
        connection.use { connection ->
            connection.inTransaction {
                val session =
                    connection.lockSession(json, sessionId)
                        ?: return@inTransaction CompleteOutcome.SessionNotFound
                if (session.participants.none { it.userId == userId }) {
                    return@inTransaction CompleteOutcome.NotParticipant
                }
                // Idempotent: matches were already resolved on a prior completion. Return no
                // resolved matches so the service does not re-emit Activity/notifications.
                if (session.status == PSYNC_STATUS_COMPLETED) {
                    return@inTransaction CompleteOutcome.Completed(session, emptyList())
                }
                if (session.status != PSYNC_STATUS_ACTIVE && session.status != PSYNC_STATUS_PAUSED) {
                    return@inTransaction CompleteOutcome.InvalidState
                }

                val resolved = connection.resolveMatches(session, matchIdFactory)
                connection.setSessionStatus(sessionId, PSYNC_STATUS_COMPLETED, endedAt = now)

                val updated =
                    connection.loadSession(json, sessionId)
                        ?: return@inTransaction CompleteOutcome.SessionNotFound
                CompleteOutcome.Completed(updated, resolved)
            }
        }
    }

internal suspend fun DataSource.listPsyncSessionMatches(sessionId: UUID): List<PsyncSessionMatch> =
    withContext(Dispatchers.IO) {
        connection.use { connection ->
            connection
                .prepareStatement(
                    """
                    SELECT m.target_user_id, m.kind, m.owner_user_id,
                           COALESCE(NULLIF(TRIM(pr.display_name), ''), 'Pynapli User') AS display_name
                    FROM psync_session_matches m
                    LEFT JOIN profiles pr ON pr.user_id = m.target_user_id
                    WHERE m.session_id = ?
                    ORDER BY m.kind ASC, m.created_at ASC
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, sessionId)
                    statement.executeQuery().use { rs ->
                        buildList {
                            while (rs.next()) {
                                add(
                                    PsyncSessionMatch(
                                        targetUserId = rs.getObject("target_user_id", UUID::class.java),
                                        kind = rs.getString("kind"),
                                        ownerUserId = rs.getObject("owner_user_id", UUID::class.java),
                                        displayName = rs.getString("display_name"),
                                    ),
                                )
                            }
                        }
                    }
                }
        }
    }

internal suspend fun DataSource.countPsyncDeckCards(sessionId: UUID): Int =
    withContext(Dispatchers.IO) {
        connection.use { connection ->
            connection
                .prepareStatement("SELECT COUNT(*) FROM psync_session_cards WHERE session_id = ?")
                .use { statement ->
                    statement.setObject(1, sessionId)
                    statement.executeQuery().use { rs ->
                        rs.next()
                        rs.getInt(1)
                    }
                }
        }
    }

internal suspend fun DataSource.countPsyncBothLiked(sessionId: UUID): Int =
    withContext(Dispatchers.IO) {
        connection.use { connection ->
            connection
                .prepareStatement(
                    """
                    SELECT COUNT(*) FROM (
                        SELECT target_user_id
                        FROM psync_session_actions
                        WHERE session_id = ? AND action IN ('like', 'pineapple_like')
                        GROUP BY target_user_id
                        HAVING COUNT(DISTINCT actor_user_id) = 2
                    ) both_liked
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, sessionId)
                    statement.executeQuery().use { rs ->
                        rs.next()
                        rs.getInt(1)
                    }
                }
        }
    }

internal suspend fun DataSource.isPsyncSoloShareEnabled(userId: UUID): Boolean =
    withContext(Dispatchers.IO) {
        connection.use { connection ->
            connection
                .prepareStatement(
                    "SELECT COALESCE(share_psync_solo_matches, false) FROM discovery_settings WHERE user_id = ?",
                ).use { statement ->
                    statement.setObject(1, userId)
                    statement.executeQuery().use { rs -> if (!rs.next()) false else rs.getBoolean(1) }
                }
        }
    }
