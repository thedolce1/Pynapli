package com.pynapli.server.tonight

import com.pynapli.server.tonight.TonightSessionRepository.ApproveOutcome
import com.pynapli.server.tonight.TonightSessionRepository.MutateOutcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

class JdbcTonightSessionRepository(
    private val dataSource: DataSource,
) : TonightSessionRepository {
    override suspend fun isPhotoOwnedBy(
        userId: UUID,
        photoAssetId: UUID,
    ): Boolean =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection
                    .prepareStatement(
                        """
                        SELECT 1
                        FROM media_assets
                        WHERE id = ?
                          AND owner_user_id = ?
                          AND deleted_at IS NULL
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setObject(1, photoAssetId)
                        statement.setObject(2, userId)
                        statement.executeQuery().use { rs -> rs.next() }
                    }
            }
        }

    override suspend fun hasActiveOrProposedSession(
        userId: UUID,
        now: Instant,
    ): Boolean =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection
                    .prepareStatement(
                        """
                        SELECT EXISTS (
                            SELECT 1
                            FROM tonight_session_participants p
                            JOIN tonight_sessions s ON s.id = p.session_id
                            WHERE p.user_id = ?
                              AND s.status IN ('proposed', 'active')
                              AND s.expires_at > ?
                        )
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setObject(1, userId)
                        statement.setTimestamp(2, Timestamp.from(now))
                        statement.executeQuery().use { rs ->
                            rs.next()
                            rs.getBoolean(1)
                        }
                    }
            }
        }

    override suspend fun countSessionsCreatedSince(
        userId: UUID,
        since: Instant,
    ): Int =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection
                    .prepareStatement(
                        """
                        SELECT COUNT(*)
                        FROM tonight_sessions
                        WHERE created_by_user_id = ?
                          AND created_at > ?
                          AND status <> 'declined'
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setObject(1, userId)
                        statement.setTimestamp(2, Timestamp.from(since))
                        statement.executeQuery().use { rs ->
                            rs.next()
                            rs.getInt(1)
                        }
                    }
            }
        }

    override suspend fun findActiveLinkedPartner(userId: UUID): UUID? =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection
                    .prepareStatement(
                        """
                        SELECT CASE WHEN user_id = ? THEN linked_user_id ELSE user_id END AS partner_user_id
                        FROM user_profile_links
                        WHERE status = 'active'
                          AND unlinked_at IS NULL
                          AND (user_id = ? OR linked_user_id = ?)
                        ORDER BY accepted_at DESC NULLS LAST, id ASC
                        LIMIT 1
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setObject(1, userId)
                        statement.setObject(2, userId)
                        statement.setObject(3, userId)
                        statement.executeQuery().use { rs ->
                            if (!rs.next()) null else rs.getObject("partner_user_id", UUID::class.java)
                        }
                    }
            }
        }

    override suspend fun insertSession(session: TonightSession): TonightSession =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
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
                        connection.insertParticipant(session.id, participant)
                    }
                }
                session
            }
        }

    override suspend fun approveParticipant(
        sessionId: UUID,
        userId: UUID,
        now: Instant,
    ): ApproveOutcome =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection.inTransaction {
                    val session =
                        connection.lockSession(sessionId)
                            ?: return@inTransaction ApproveOutcome.SessionNotFound
                    val participant =
                        session.participants.firstOrNull { it.userId == userId }
                            ?: return@inTransaction ApproveOutcome.NotParticipant
                    if (session.status != STATUS_PROPOSED || participant.approvalStatus != APPROVAL_PENDING) {
                        return@inTransaction ApproveOutcome.NotPending
                    }

                    connection.setParticipantApproval(sessionId, userId, APPROVAL_APPROVED, now)

                    val allApproved =
                        session.participants.all { existing ->
                            existing.userId == userId || existing.approvalStatus == APPROVAL_APPROVED
                        }
                    if (allApproved) {
                        // Honor the originally-requested window length from activation.
                        val originalWindow = java.time.Duration.between(session.createdAt, session.expiresAt)
                        connection.activateSession(sessionId, startedAt = now, expiresAt = now.plus(originalWindow))
                    }

                    val updated =
                        connection.loadSession(sessionId)
                            ?: return@inTransaction ApproveOutcome.SessionNotFound
                    ApproveOutcome.Updated(updated)
                }
            }
        }

    override suspend fun declineParticipant(
        sessionId: UUID,
        userId: UUID,
        now: Instant,
    ): MutateOutcome =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection.inTransaction {
                    val session =
                        connection.lockSession(sessionId)
                            ?: return@inTransaction MutateOutcome.SessionNotFound
                    val participant =
                        session.participants.firstOrNull { it.userId == userId }
                            ?: return@inTransaction MutateOutcome.NotParticipant
                    if (session.status != STATUS_PROPOSED || participant.approvalStatus != APPROVAL_PENDING) {
                        return@inTransaction MutateOutcome.NotPending
                    }

                    connection.setParticipantApproval(sessionId, userId, APPROVAL_DECLINED, now)
                    connection.setSessionStatus(sessionId, STATUS_DECLINED)

                    val updated =
                        connection.loadSession(sessionId)
                            ?: return@inTransaction MutateOutcome.SessionNotFound
                    MutateOutcome.Updated(updated)
                }
            }
        }

    override suspend fun endSession(
        sessionId: UUID,
        userId: UUID,
        now: Instant,
    ): MutateOutcome =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection.inTransaction {
                    val session =
                        connection.lockSession(sessionId)
                            ?: return@inTransaction MutateOutcome.SessionNotFound
                    if (session.participants.none { it.userId == userId }) {
                        return@inTransaction MutateOutcome.NotParticipant
                    }
                    if (session.status != STATUS_PROPOSED && session.status != STATUS_ACTIVE) {
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
                        connection.loadSession(sessionId)
                            ?: return@inTransaction MutateOutcome.SessionNotFound
                    MutateOutcome.Updated(updated)
                }
            }
        }

    override suspend fun findById(sessionId: UUID): TonightSession? =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection -> connection.loadSession(sessionId) }
        }

    override suspend fun findCurrentForUser(
        userId: UUID,
        now: Instant,
    ): TonightSession? =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                val sessionId =
                    connection
                        .prepareStatement(
                            """
                            SELECT s.id
                            FROM tonight_sessions s
                            JOIN tonight_session_participants p ON p.session_id = s.id
                            WHERE p.user_id = ?
                              AND s.status IN ('proposed', 'active')
                              AND s.expires_at > ?
                            ORDER BY s.created_at DESC
                            LIMIT 1
                            """.trimIndent(),
                        ).use { statement ->
                            statement.setObject(1, userId)
                            statement.setTimestamp(2, Timestamp.from(now))
                            statement.executeQuery().use { rs ->
                                if (!rs.next()) null else rs.getObject("id", UUID::class.java)
                            }
                        } ?: return@withContext null
                connection.loadSession(sessionId, withDisplayNames = true)
            }
        }

    override suspend fun expireStale(now: Instant): Int =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection
                    .prepareStatement(
                        """
                        UPDATE tonight_sessions
                        SET status = 'expired'
                        WHERE status IN ('proposed', 'active')
                          AND expires_at <= ?
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setTimestamp(1, Timestamp.from(now))
                        statement.executeUpdate()
                    }
            }
        }

    override suspend fun claimSessionsDueForReminder(
        now: Instant,
        leadWindow: java.time.Duration,
    ): List<TonightSessionRepository.TonightReminderTarget> =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection.inTransaction {
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
                        return@inTransaction emptyList()
                    }

                    // Stamp the latch so these sessions are never claimed/reminded again.
                    connection
                        .prepareStatement(
                            "UPDATE tonight_sessions SET reminder_sent_at = ? WHERE id = ANY (?)",
                        ).use { statement ->
                            statement.setTimestamp(1, Timestamp.from(now))
                            statement.setArray(2, connection.createArrayOf("uuid", dueSessionIds.toTypedArray()))
                            statement.executeUpdate()
                        }

                    val participantsBySession =
                        connection
                            .prepareStatement(
                                """
                                SELECT session_id, user_id
                                FROM tonight_session_participants
                                WHERE session_id = ANY (?)
                                """.trimIndent(),
                            ).use { statement ->
                                statement.setArray(1, connection.createArrayOf("uuid", dueSessionIds.toTypedArray()))
                                statement.executeQuery().use { rs ->
                                    buildMap<UUID, MutableList<UUID>> {
                                        while (rs.next()) {
                                            val sessionId = rs.getObject("session_id", UUID::class.java)
                                            val userId = rs.getObject("user_id", UUID::class.java)
                                            getOrPut(sessionId) { mutableListOf() }.add(userId)
                                        }
                                    }
                                }
                            }

                    dueSessionIds.map { sessionId ->
                        TonightSessionRepository.TonightReminderTarget(
                            sessionId = sessionId,
                            participantUserIds = participantsBySession[sessionId].orEmpty(),
                        )
                    }
                }
            }
        }

    // ---- transactional helpers -------------------------------------------------------

    private inline fun <T> Connection.inTransaction(block: () -> T): T {
        val previousAutoCommit = autoCommit
        autoCommit = false
        try {
            val result = block()
            commit()
            return result
        } catch (error: Throwable) {
            rollback()
            throw error
        } finally {
            autoCommit = previousAutoCommit
        }
    }

    private fun Connection.insertParticipant(
        sessionId: UUID,
        participant: TonightSessionParticipant,
    ) {
        prepareStatement(
            """
            INSERT INTO tonight_session_participants (session_id, user_id, role, approval_status, approved_at)
            VALUES (?, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, sessionId)
            statement.setObject(2, participant.userId)
            statement.setString(3, participant.role)
            statement.setString(4, participant.approvalStatus)
            statement.setTimestamp(5, participant.approvedAt?.let(Timestamp::from))
            statement.executeUpdate()
        }
    }

    private fun Connection.lockSession(sessionId: UUID): TonightSession? {
        val row =
            prepareStatement(
                "SELECT id FROM tonight_sessions WHERE id = ? FOR UPDATE",
            ).use { statement ->
                statement.setObject(1, sessionId)
                statement.executeQuery().use { rs -> rs.next() }
            }
        return if (!row) null else loadSession(sessionId)
    }

    private fun Connection.setParticipantApproval(
        sessionId: UUID,
        userId: UUID,
        approvalStatus: String,
        now: Instant,
    ) {
        prepareStatement(
            """
            UPDATE tonight_session_participants
            SET approval_status = ?,
                approved_at = CASE WHEN ? = 'approved' THEN ? ELSE approved_at END
            WHERE session_id = ?
              AND user_id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, approvalStatus)
            statement.setString(2, approvalStatus)
            statement.setTimestamp(3, Timestamp.from(now))
            statement.setObject(4, sessionId)
            statement.setObject(5, userId)
            statement.executeUpdate()
        }
    }

    private fun Connection.setSessionStatus(
        sessionId: UUID,
        status: String,
    ) {
        prepareStatement("UPDATE tonight_sessions SET status = ? WHERE id = ?").use { statement ->
            statement.setString(1, status)
            statement.setObject(2, sessionId)
            statement.executeUpdate()
        }
    }

    private fun Connection.activateSession(
        sessionId: UUID,
        startedAt: Instant,
        expiresAt: Instant,
    ) {
        prepareStatement(
            """
            UPDATE tonight_sessions
            SET status = 'active',
                started_at = ?,
                expires_at = ?
            WHERE id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setTimestamp(1, Timestamp.from(startedAt))
            statement.setTimestamp(2, Timestamp.from(expiresAt))
            statement.setObject(3, sessionId)
            statement.executeUpdate()
        }
    }

    private fun Connection.loadSession(
        sessionId: UUID,
        withDisplayNames: Boolean = false,
    ): TonightSession? {
        val session =
            prepareStatement(
                """
                SELECT id, created_by_user_id, mode, intent, host_mode, note,
                       photo_media_asset_id, map_visible, status,
                       created_at, started_at, expires_at, ended_at
                FROM tonight_sessions
                WHERE id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, sessionId)
                statement.executeQuery().use { rs ->
                    if (!rs.next()) null else rs.toSession()
                }
            } ?: return null
        return session.copy(participants = loadParticipants(sessionId, withDisplayNames))
    }

    private fun Connection.loadParticipants(
        sessionId: UUID,
        withDisplayNames: Boolean,
    ): List<TonightSessionParticipant> {
        val sql =
            if (withDisplayNames) {
                """
                SELECT p.user_id, p.role, p.approval_status, p.approved_at,
                       COALESCE(NULLIF(TRIM(pr.display_name), ''), 'Pynapli User') AS display_name
                FROM tonight_session_participants p
                LEFT JOIN profiles pr ON pr.user_id = p.user_id
                WHERE p.session_id = ?
                ORDER BY p.role ASC, p.user_id ASC
                """.trimIndent()
            } else {
                """
                SELECT user_id, role, approval_status, approved_at, NULL AS display_name
                FROM tonight_session_participants
                WHERE session_id = ?
                ORDER BY role ASC, user_id ASC
                """.trimIndent()
            }
        return prepareStatement(sql).use { statement ->
            statement.setObject(1, sessionId)
            statement.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(rs.toParticipant())
                    }
                }
            }
        }
    }

    private fun ResultSet.toSession(): TonightSession =
        TonightSession(
            id = getObject("id", UUID::class.java),
            createdByUserId = getObject("created_by_user_id", UUID::class.java),
            mode = getString("mode"),
            intent = getString("intent"),
            hostMode = getString("host_mode"),
            note = getString("note"),
            photoMediaAssetId = getObject("photo_media_asset_id", UUID::class.java),
            mapVisible = getBoolean("map_visible"),
            status = getString("status"),
            createdAt = getTimestamp("created_at").toInstant(),
            startedAt = getTimestamp("started_at")?.toInstant(),
            expiresAt = getTimestamp("expires_at").toInstant(),
            endedAt = getTimestamp("ended_at")?.toInstant(),
            participants = emptyList(),
        )

    private fun ResultSet.toParticipant(): TonightSessionParticipant =
        TonightSessionParticipant(
            userId = getObject("user_id", UUID::class.java),
            role = getString("role"),
            approvalStatus = getString("approval_status"),
            approvedAt = getTimestamp("approved_at")?.toInstant(),
            displayName = getString("display_name"),
        )

    private companion object {
        const val STATUS_PROPOSED = "proposed"
        const val STATUS_ACTIVE = "active"
        const val STATUS_DECLINED = "declined"
        const val APPROVAL_PENDING = "pending"
        const val APPROVAL_APPROVED = "approved"
        const val APPROVAL_DECLINED = "declined"
    }
}
