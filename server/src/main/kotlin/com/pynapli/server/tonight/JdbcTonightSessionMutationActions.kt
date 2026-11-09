package com.pynapli.server.tonight

import com.pynapli.server.db.inTransaction
import com.pynapli.server.tonight.TonightSessionRepository.ApproveOutcome
import com.pynapli.server.tonight.TonightSessionRepository.MutateOutcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

internal suspend fun DataSource.insertTonightSession(session: TonightSession): TonightSession =
    withContext(Dispatchers.IO) {
        connection.use { connection ->
            connection.inTransaction {
                connection
                    .prepareStatement(
                        """
                        INSERT INTO tonight_sessions (
                            id, created_by_user_id, mode, intent, host_mode, note,
                            photo_media_asset_id, map_visible, status,
                            created_at, started_at, expires_at, ended_at
                        )
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setObject(1, session.id)
                        statement.setObject(2, session.createdByUserId)
                        statement.setString(3, session.mode)
                        statement.setString(4, session.intent)
                        statement.setString(5, session.hostMode)
                        statement.setString(6, session.note)
                        statement.setObject(7, session.photoMediaAssetId)
                        statement.setBoolean(8, session.mapVisible)
                        statement.setString(9, session.status)
                        statement.setTimestamp(10, Timestamp.from(session.createdAt))
                        statement.setTimestamp(11, session.startedAt?.let(Timestamp::from))
                        statement.setTimestamp(12, Timestamp.from(session.expiresAt))
                        statement.setTimestamp(13, session.endedAt?.let(Timestamp::from))
                        statement.executeUpdate()
                    }

                session.participants.forEach { participant ->
                    connection.insertTonightParticipant(session.id, participant)
                }
            }
            session
        }
    }

internal suspend fun DataSource.approveTonightParticipant(
    sessionId: UUID,
    userId: UUID,
    now: Instant,
): ApproveOutcome =
    withContext(Dispatchers.IO) {
        connection.use { connection ->
            connection.inTransaction {
                val session =
                    connection.lockTonightSession(sessionId)
                        ?: return@inTransaction ApproveOutcome.SessionNotFound
                val participant =
                    session.participants.firstOrNull { it.userId == userId }
                        ?: return@inTransaction ApproveOutcome.NotParticipant
                if (session.status != TONIGHT_STATUS_PROPOSED || participant.approvalStatus != TONIGHT_APPROVAL_PENDING) {
                    return@inTransaction ApproveOutcome.NotPending
                }

                connection.setTonightParticipantApproval(sessionId, userId, TONIGHT_APPROVAL_APPROVED, now)

                val allApproved =
                    session.participants.all { existing ->
                        existing.userId == userId || existing.approvalStatus == TONIGHT_APPROVAL_APPROVED
                    }
                if (allApproved) {
                    // Honor the originally-requested window length from activation.
                    val originalWindow = java.time.Duration.between(session.createdAt, session.expiresAt)
                    connection.activateTonightSession(sessionId, startedAt = now, expiresAt = now.plus(originalWindow))
                }

                val updated =
                    connection.loadTonightSession(sessionId)
                        ?: return@inTransaction ApproveOutcome.SessionNotFound
                ApproveOutcome.Updated(updated)
            }
        }
    }

internal suspend fun DataSource.declineTonightParticipant(
    sessionId: UUID,
    userId: UUID,
    now: Instant,
): MutateOutcome =
    withContext(Dispatchers.IO) {
        connection.use { connection ->
            connection.inTransaction {
                val session =
                    connection.lockTonightSession(sessionId)
                        ?: return@inTransaction MutateOutcome.SessionNotFound
                val participant =
                    session.participants.firstOrNull { it.userId == userId }
                        ?: return@inTransaction MutateOutcome.NotParticipant
                if (session.status != TONIGHT_STATUS_PROPOSED || participant.approvalStatus != TONIGHT_APPROVAL_PENDING) {
                    return@inTransaction MutateOutcome.NotPending
                }

                connection.setTonightParticipantApproval(sessionId, userId, TONIGHT_APPROVAL_DECLINED, now)
                connection.setTonightSessionStatus(sessionId, TONIGHT_STATUS_DECLINED)

                val updated =
                    connection.loadTonightSession(sessionId)
                        ?: return@inTransaction MutateOutcome.SessionNotFound
                MutateOutcome.Updated(updated)
            }
        }
    }

internal suspend fun DataSource.endTonightSession(
    sessionId: UUID,
    userId: UUID,
    now: Instant,
): MutateOutcome =
    withContext(Dispatchers.IO) {
        connection.use { connection ->
            connection.inTransaction {
                val session =
                    connection.lockTonightSession(sessionId)
                        ?: return@inTransaction MutateOutcome.SessionNotFound
                if (session.participants.none { it.userId == userId }) {
                    return@inTransaction MutateOutcome.NotParticipant
                }
                if (session.status != TONIGHT_STATUS_PROPOSED && session.status != TONIGHT_STATUS_ACTIVE) {
                    return@inTransaction MutateOutcome.SessionNotFound
                }

                connection
                    .prepareStatement(
                        "UPDATE tonight_sessions SET status = 'ended', ended_at = ? WHERE id = ?",
                    ).use { statement ->
                        statement.setTimestamp(1, Timestamp.from(now))
                        statement.setObject(2, sessionId)
                        statement.executeUpdate()
                    }

                val updated =
                    connection.loadTonightSession(sessionId)
                        ?: return@inTransaction MutateOutcome.SessionNotFound
                MutateOutcome.Updated(updated)
            }
        }
    }
