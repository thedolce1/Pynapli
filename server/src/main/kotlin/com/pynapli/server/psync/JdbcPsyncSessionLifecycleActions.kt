package com.pynapli.server.psync

import com.pynapli.server.db.inTransaction
import com.pynapli.server.psync.PsyncSessionRepository.ActiveLink
import com.pynapli.server.psync.PsyncSessionRepository.MutateOutcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

internal suspend fun DataSource.findPsyncActiveLink(userId: UUID): ActiveLink? =
    withContext(Dispatchers.IO) {
        connection.use { connection ->
            connection
                .prepareStatement(
                    """
                    SELECT id,
                           CASE WHEN user_id = ? THEN linked_user_id ELSE user_id END AS partner_user_id
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
                        if (!rs.next()) {
                            null
                        } else {
                            ActiveLink(
                                linkId = rs.getObject("id", UUID::class.java),
                                partnerUserId = rs.getObject("partner_user_id", UUID::class.java),
                            )
                        }
                    }
                }
        }
    }

internal suspend fun DataSource.countStartedPsyncSessionsSince(
    initiatorUserId: UUID,
    since: Instant,
): Int =
    withContext(Dispatchers.IO) {
        connection.use { connection ->
            connection
                .prepareStatement(
                    "SELECT COUNT(*) FROM psync_sessions WHERE initiator_user_id = ? AND started_at >= ?",
                ).use { statement ->
                    statement.setObject(1, initiatorUserId)
                    statement.setTimestamp(2, Timestamp.from(since))
                    statement.executeQuery().use { rs ->
                        rs.next()
                        rs.getInt(1)
                    }
                }
        }
    }

internal suspend fun DataSource.hasNonTerminalPsyncSessionForLink(linkId: UUID): Boolean =
    withContext(Dispatchers.IO) {
        connection.use { connection ->
            connection
                .prepareStatement(
                    """
                    SELECT EXISTS (
                        SELECT 1
                        FROM psync_sessions
                        WHERE partner_link_id = ?
                          AND status IN ($PSYNC_NON_TERMINAL_STATUS_LIST)
                    )
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, linkId)
                    statement.executeQuery().use { rs ->
                        rs.next()
                        rs.getBoolean(1)
                    }
                }
        }
    }

internal suspend fun DataSource.insertPsyncSession(
    json: Json,
    session: PsyncSession,
    participants: List<PsyncSessionParticipant>,
): PsyncSession =
    withContext(Dispatchers.IO) {
        connection.use { connection ->
            connection.inTransaction {
                connection
                    .prepareStatement(
                        """
                        INSERT INTO psync_sessions (
                            id, partner_link_id, initiator_user_id, status, filters,
                            deck_size, deck_session_id, created_at, started_at, expires_at, ended_at,
                            travel_location_id, location_label
                        )
                        VALUES (?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?)
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setObject(1, session.id)
                        statement.setObject(2, session.partnerLinkId)
                        statement.setObject(3, session.initiatorUserId)
                        statement.setString(4, session.status)
                        statement.setObject(5, jsonbOf(json, session.filters))
                        statement.setInt(6, session.deckSize)
                        statement.setObject(7, session.deckSessionId)
                        statement.setTimestamp(8, Timestamp.from(session.createdAt))
                        statement.setTimestamp(9, session.startedAt?.let(Timestamp::from))
                        statement.setTimestamp(10, Timestamp.from(session.expiresAt))
                        statement.setTimestamp(11, session.endedAt?.let(Timestamp::from))
                        statement.setObject(12, session.travelLocationId)
                        statement.setString(13, session.locationLabel)
                        statement.executeUpdate()
                    }

                participants.forEach { participant ->
                    connection.insertParticipant(session.id, participant)
                }

                connection.loadSession(json, session.id)
                    ?: error("Inserted Psync session could not be reloaded")
            }
        }
    }

internal suspend fun DataSource.acceptPsyncSession(
    json: Json,
    sessionId: UUID,
    userId: UUID,
    now: Instant,
): MutateOutcome =
    withContext(Dispatchers.IO) {
        connection.use { connection ->
            connection.inTransaction {
                val session =
                    connection.lockSession(json, sessionId)
                        ?: return@inTransaction MutateOutcome.SessionNotFound
                if (session.participants.none { it.userId == userId }) {
                    return@inTransaction MutateOutcome.NotParticipant
                }
                if (session.status != PSYNC_STATUS_INVITED && session.status != PSYNC_STATUS_CONFIGURING) {
                    return@inTransaction MutateOutcome.InvalidState
                }

                connection.setParticipantConsent(sessionId, userId, PSYNC_CONSENT_ACCEPTED, approved = true, now = now)

                val allApproved =
                    session.participants.all { existing ->
                        existing.userId == userId || existing.approved
                    }
                if (allApproved) {
                    connection.setSessionStatus(sessionId, PSYNC_STATUS_READY)
                }

                val updated =
                    connection.loadSession(json, sessionId)
                        ?: return@inTransaction MutateOutcome.SessionNotFound
                MutateOutcome.Updated(updated)
            }
        }
    }

internal suspend fun DataSource.denyPsyncSession(
    json: Json,
    sessionId: UUID,
    userId: UUID,
    now: Instant,
): MutateOutcome =
    withContext(Dispatchers.IO) {
        connection.use { connection ->
            connection.inTransaction {
                val session =
                    connection.lockSession(json, sessionId)
                        ?: return@inTransaction MutateOutcome.SessionNotFound
                if (session.participants.none { it.userId == userId }) {
                    return@inTransaction MutateOutcome.NotParticipant
                }
                if (session.status in PSYNC_TERMINAL_STATUSES) {
                    return@inTransaction MutateOutcome.InvalidState
                }

                connection.setParticipantConsent(sessionId, userId, PSYNC_CONSENT_DENIED, approved = false, now = now)
                connection.setSessionStatus(sessionId, PSYNC_STATUS_CANCELLED, endedAt = now)

                val updated =
                    connection.loadSession(json, sessionId)
                        ?: return@inTransaction MutateOutcome.SessionNotFound
                MutateOutcome.Updated(updated)
            }
        }
    }

internal suspend fun DataSource.suggestPsyncFilters(
    json: Json,
    sessionId: UUID,
    userId: UUID,
    filtersJson: String,
    now: Instant,
): MutateOutcome =
    withContext(Dispatchers.IO) {
        connection.use { connection ->
            connection.inTransaction {
                val session =
                    connection.lockSession(json, sessionId)
                        ?: return@inTransaction MutateOutcome.SessionNotFound
                if (session.participants.none { it.userId == userId }) {
                    return@inTransaction MutateOutcome.NotParticipant
                }
                if (session.status !in PSYNC_SUGGESTABLE_STATUSES) {
                    return@inTransaction MutateOutcome.InvalidState
                }

                // New setup: reset everyone's approval, then re-approve only the suggester.
                connection.resetAllApprovals(sessionId, now)
                connection.setParticipantConsent(sessionId, userId, PSYNC_CONSENT_ACCEPTED, approved = true, now = now)
                connection.updateFilters(sessionId, filtersJson)
                connection.setSessionStatus(sessionId, PSYNC_STATUS_CONFIGURING)

                val updated =
                    connection.loadSession(json, sessionId)
                        ?: return@inTransaction MutateOutcome.SessionNotFound
                MutateOutcome.Updated(updated)
            }
        }
    }

internal suspend fun DataSource.leavePsyncSession(
    json: Json,
    sessionId: UUID,
    userId: UUID,
    now: Instant,
): MutateOutcome =
    withContext(Dispatchers.IO) {
        connection.use { connection ->
            connection.inTransaction {
                val session =
                    connection.lockSession(json, sessionId)
                        ?: return@inTransaction MutateOutcome.SessionNotFound
                if (session.participants.none { it.userId == userId }) {
                    return@inTransaction MutateOutcome.NotParticipant
                }
                if (session.status in PSYNC_TERMINAL_STATUSES) {
                    return@inTransaction MutateOutcome.InvalidState
                }

                connection.setSessionStatus(sessionId, PSYNC_STATUS_CANCELLED, endedAt = now)

                val updated =
                    connection.loadSession(json, sessionId)
                        ?: return@inTransaction MutateOutcome.SessionNotFound
                MutateOutcome.Updated(updated)
            }
        }
    }

internal suspend fun DataSource.findPsyncSessionById(
    json: Json,
    sessionId: UUID,
    withDisplayNames: Boolean,
): PsyncSession? =
    withContext(Dispatchers.IO) {
        connection.use { connection -> connection.loadSession(json, sessionId, withDisplayNames) }
    }

internal suspend fun DataSource.findCurrentPsyncSessionForUser(
    json: Json,
    userId: UUID,
    now: Instant,
): PsyncSession? =
    withContext(Dispatchers.IO) {
        connection.use { connection ->
            val sessionId =
                connection
                    .prepareStatement(
                        """
                        SELECT s.id
                        FROM psync_sessions s
                        JOIN psync_session_participants p ON p.session_id = s.id
                        WHERE p.user_id = ?
                          AND s.status IN ($PSYNC_NON_TERMINAL_STATUS_LIST)
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
            connection.loadSession(json, sessionId, withDisplayNames = true)
        }
    }

/**
 * The location the session was set up with no longer matches the initiator's (they turned Travel
 * mode on, off, or picked another city). Record the new one and send the session back to both
 * partners for approval, clearing anyone's Start tap. Only a session that hasn't started can move.
 */
internal suspend fun DataSource.resetPsyncLocation(
    json: Json,
    sessionId: UUID,
    travelLocationId: UUID?,
    locationLabel: String?,
    now: Instant,
): MutateOutcome =
    withContext(Dispatchers.IO) {
        connection.use { connection ->
            connection.inTransaction {
                connection.lockSession(json, sessionId) ?: return@inTransaction MutateOutcome.SessionNotFound
                val moved =
                    connection
                        .prepareStatement(
                            """
                            UPDATE psync_sessions
                            SET status = 'configuring', travel_location_id = ?, location_label = ?
                            WHERE id = ? AND status IN ('invited', 'configuring', 'ready')
                            """.trimIndent(),
                        ).use { statement ->
                            statement.setObject(1, travelLocationId)
                            statement.setString(2, locationLabel)
                            statement.setObject(3, sessionId)
                            statement.executeUpdate()
                        }
                if (moved == 0) return@inTransaction MutateOutcome.InvalidState
                connection
                    .prepareStatement(
                        "UPDATE psync_session_participants SET approved = false, started = false, updated_at = ? WHERE session_id = ?",
                    ).use { statement ->
                        statement.setTimestamp(1, Timestamp.from(now))
                        statement.setObject(2, sessionId)
                        statement.executeUpdate()
                    }
                val updated = connection.loadSession(json, sessionId) ?: return@inTransaction MutateOutcome.SessionNotFound
                MutateOutcome.Updated(updated)
            }
        }
    }

internal suspend fun DataSource.listOverdueStartedPsyncSessions(now: Instant): List<PsyncOverdueSession> =
    withContext(Dispatchers.IO) {
        connection.use { connection ->
            connection
                .prepareStatement(
                    """
                    SELECT id, initiator_user_id FROM psync_sessions
                    WHERE status IN ('active', 'paused')
                      AND expires_at <= ?
                    """.trimIndent(),
                ).use { statement ->
                    statement.setTimestamp(1, Timestamp.from(now))
                    statement.executeQuery().use { rs ->
                        buildList {
                            while (rs.next()) {
                                add(
                                    PsyncOverdueSession(
                                        sessionId = rs.getObject("id", UUID::class.java),
                                        initiatorUserId = rs.getObject("initiator_user_id", UUID::class.java),
                                    ),
                                )
                            }
                        }
                    }
                }
        }
    }

internal suspend fun DataSource.expireStalePsyncSessions(now: Instant): List<PsyncExpiredSession> =
    withContext(Dispatchers.IO) {
        connection.use { connection ->
            connection.inTransaction {
                val expiredIds =
                    connection
                        .prepareStatement(
                            """
                            SELECT id FROM psync_sessions
                            WHERE status IN ($PSYNC_EXPIRABLE_STATUS_LIST)
                              AND expires_at <= ?
                            FOR UPDATE
                            """.trimIndent(),
                        ).use { statement ->
                            statement.setTimestamp(1, Timestamp.from(now))
                            statement.executeQuery().use { rs ->
                                buildList { while (rs.next()) add(rs.getObject("id", UUID::class.java)) }
                            }
                        }
                if (expiredIds.isEmpty()) return@inTransaction emptyList()

                val participantsBySession = connection.participantIdsForSessions(expiredIds)
                connection
                    .prepareStatement(
                        "UPDATE psync_sessions SET status = 'expired', ended_at = ? WHERE id = ANY(?)",
                    ).use { statement ->
                        statement.setTimestamp(1, Timestamp.from(now))
                        statement.setArray(2, connection.createArrayOf("uuid", expiredIds.toTypedArray()))
                        statement.executeUpdate()
                    }

                expiredIds.map { sessionId ->
                    PsyncExpiredSession(sessionId, participantsBySession[sessionId].orEmpty())
                }
            }
        }
    }
