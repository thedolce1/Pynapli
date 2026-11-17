package com.pynapli.server.tonight

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

internal suspend fun DataSource.listActiveTonightPresenceSessions(now: Instant): List<TonightSessionRepository.TonightPresenceSession> =
    withContext(Dispatchers.IO) {
        connection.use { connection ->
            val sessions =
                connection
                    .prepareStatement(
                        """
                        SELECT id, paused_at
                        FROM tonight_sessions
                        WHERE status = 'active'
                          AND mode = 'together'
                          AND expires_at > ?
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setTimestamp(1, Timestamp.from(now))
                        statement.executeQuery().use { rs ->
                            buildList {
                                while (rs.next()) {
                                    add(
                                        rs.getObject("id", UUID::class.java) to
                                            rs.getTimestamp("paused_at")?.toInstant(),
                                    )
                                }
                            }
                        }
                    }

            if (sessions.isEmpty()) {
                return@withContext emptyList()
            }

            val sessionIds = sessions.map { it.first }
            val participantsBySession = connection.loadTonightParticipantsBySession(sessionIds)

            sessions.map { (sessionId, pausedAt) ->
                TonightSessionRepository.TonightPresenceSession(
                    sessionId = sessionId,
                    pausedAt = pausedAt,
                    participantUserIds = participantsBySession[sessionId].orEmpty(),
                )
            }
        }
    }

internal suspend fun DataSource.pauseTonightSession(
    sessionId: UUID,
    now: Instant,
): Boolean =
    withContext(Dispatchers.IO) {
        connection.use { connection ->
            connection
                .prepareStatement("UPDATE tonight_sessions SET paused_at = ? WHERE id = ? AND status = 'active'")
                .use { statement ->
                    statement.setTimestamp(1, Timestamp.from(now))
                    statement.setObject(2, sessionId)
                    statement.executeUpdate() > 0
                }
        }
    }

internal suspend fun DataSource.resumeTonightSession(sessionId: UUID): Boolean =
    withContext(Dispatchers.IO) {
        connection.use { connection ->
            connection
                .prepareStatement("UPDATE tonight_sessions SET paused_at = NULL WHERE id = ? AND status = 'active'")
                .use { statement ->
                    statement.setObject(1, sessionId)
                    statement.executeUpdate() > 0
                }
        }
    }

internal suspend fun DataSource.systemEndTonightSession(
    sessionId: UUID,
    now: Instant,
): Boolean =
    withContext(Dispatchers.IO) {
        connection.use { connection ->
            connection
                .prepareStatement("UPDATE tonight_sessions SET status = 'ended', ended_at = ? WHERE id = ? AND status = 'active'")
                .use { statement ->
                    statement.setTimestamp(1, Timestamp.from(now))
                    statement.setObject(2, sessionId)
                    statement.executeUpdate() > 0
                }
        }
    }
