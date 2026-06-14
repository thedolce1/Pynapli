package com.pynapli.server.tonight

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

internal suspend fun DataSource.claimTonightSessionsDueForReminder(
    now: Instant,
    leadWindow: Duration,
): List<TonightSessionRepository.TonightReminderTarget> =
    withContext(Dispatchers.IO) {
        connection.use { connection ->
            connection.inTonightTransaction {
                // Lock the due-but-unreminded rows; SKIP LOCKED lets concurrent ticks/instances
                // each take a disjoint set instead of blocking on one another.
                val dueSessionIds =
                    connection
                        .prepareStatement(
                            """
                            SELECT id
                            FROM tonight_sessions
                            WHERE status = 'active'
                              AND reminder_sent_at IS NULL
                              AND expires_at > ?
                              AND expires_at <= ?
                            FOR UPDATE SKIP LOCKED
                            """.trimIndent(),
                        ).use { statement ->
                            statement.setTimestamp(1, Timestamp.from(now))
                            statement.setTimestamp(2, Timestamp.from(now.plus(leadWindow)))
                            statement.executeQuery().use { rs ->
                                buildList { while (rs.next()) add(rs.getObject("id", UUID::class.java)) }
                            }
                        }

                if (dueSessionIds.isEmpty()) {
                    return@inTonightTransaction emptyList()
                }

                connection
                    .prepareStatement(
                        "UPDATE tonight_sessions SET reminder_sent_at = ? WHERE id = ANY (?)",
                    ).use { statement ->
                        statement.setTimestamp(1, Timestamp.from(now))
                        statement.setArray(2, connection.createArrayOf("uuid", dueSessionIds.toTypedArray()))
                        statement.executeUpdate()
                    }

                val participantsBySession = connection.loadTonightParticipantsBySession(dueSessionIds)

                dueSessionIds.map { sessionId ->
                    TonightSessionRepository.TonightReminderTarget(
                        sessionId = sessionId,
                        participantUserIds = participantsBySession[sessionId].orEmpty(),
                    )
                }
            }
        }
    }
