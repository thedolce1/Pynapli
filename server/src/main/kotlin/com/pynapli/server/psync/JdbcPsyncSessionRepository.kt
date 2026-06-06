package com.pynapli.server.psync

import com.pynapli.server.psync.PsyncSessionRepository.ActiveLink
import com.pynapli.server.psync.PsyncSessionRepository.CompleteOutcome
import com.pynapli.server.psync.PsyncSessionRepository.MutateOutcome
import com.pynapli.server.psync.PsyncSessionRepository.RecordActionOutcome
import com.pynapli.server.psync.PsyncSessionRepository.StartOutcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

class JdbcPsyncSessionRepository(
    private val dataSource: DataSource,
    private val json: Json = Json,
) : PsyncSessionRepository {
    override suspend fun findActiveLink(userId: UUID): ActiveLink? =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
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

    override suspend fun hasNonTerminalSessionForLink(linkId: UUID): Boolean =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection
                    .prepareStatement(
                        """
                        SELECT EXISTS (
                            SELECT 1
                            FROM psync_sessions
                            WHERE partner_link_id = ?
                              AND status IN ($NON_TERMINAL_STATUS_LIST)
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

    override suspend fun insertSession(
        session: PsyncSession,
        participants: List<PsyncSessionParticipant>,
    ): PsyncSession =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection.inTransaction {
                    connection
                        .prepareStatement(
                            """
                            INSERT INTO psync_sessions (
                                id, partner_link_id, initiator_user_id, status, filters,
                                deck_size, deck_session_id, created_at, started_at, expires_at, ended_at
                            )
                            VALUES (?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?)
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

    override suspend fun accept(
        sessionId: UUID,
        userId: UUID,
        now: Instant,
    ): MutateOutcome =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection.inTransaction {
                    val session =
                        connection.lockSession(json, sessionId)
                            ?: return@inTransaction MutateOutcome.SessionNotFound
                    if (session.participants.none { it.userId == userId }) {
                        return@inTransaction MutateOutcome.NotParticipant
                    }
                    if (session.status != STATUS_INVITED && session.status != STATUS_CONFIGURING) {
                        return@inTransaction MutateOutcome.InvalidState
                    }

                    connection.setParticipantConsent(sessionId, userId, CONSENT_ACCEPTED, approved = true, now = now)

                    val allApproved =
                        session.participants.all { existing ->
                            existing.userId == userId || existing.approved
                        }
                    if (allApproved) {
                        connection.setSessionStatus(sessionId, STATUS_READY)
                    }

                    val updated =
                        connection.loadSession(json, sessionId)
                            ?: return@inTransaction MutateOutcome.SessionNotFound
                    MutateOutcome.Updated(updated)
                }
            }
        }

    override suspend fun deny(
        sessionId: UUID,
        userId: UUID,
        now: Instant,
    ): MutateOutcome =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection.inTransaction {
                    val session =
                        connection.lockSession(json, sessionId)
                            ?: return@inTransaction MutateOutcome.SessionNotFound
                    if (session.participants.none { it.userId == userId }) {
                        return@inTransaction MutateOutcome.NotParticipant
                    }
                    if (session.status in TERMINAL_STATUSES) {
                        return@inTransaction MutateOutcome.InvalidState
                    }

                    connection.setParticipantConsent(sessionId, userId, CONSENT_DENIED, approved = false, now = now)
                    connection.setSessionStatus(sessionId, STATUS_CANCELLED, endedAt = now)

                    val updated =
                        connection.loadSession(json, sessionId)
                            ?: return@inTransaction MutateOutcome.SessionNotFound
                    MutateOutcome.Updated(updated)
                }
            }
        }

    override suspend fun suggestFilters(
        sessionId: UUID,
        userId: UUID,
        filtersJson: String,
        now: Instant,
    ): MutateOutcome =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection.inTransaction {
                    val session =
                        connection.lockSession(json, sessionId)
                            ?: return@inTransaction MutateOutcome.SessionNotFound
                    if (session.participants.none { it.userId == userId }) {
                        return@inTransaction MutateOutcome.NotParticipant
                    }
                    if (session.status !in SUGGESTABLE_STATUSES) {
                        return@inTransaction MutateOutcome.InvalidState
                    }

                    // New setup: reset everyone's approval, then re-approve only the suggester.
                    connection.resetAllApprovals(sessionId, now)
                    connection.setParticipantConsent(sessionId, userId, CONSENT_ACCEPTED, approved = true, now = now)
                    connection.updateFilters(sessionId, filtersJson)
                    connection.setSessionStatus(sessionId, STATUS_CONFIGURING)

                    val updated =
                        connection.loadSession(json, sessionId)
                            ?: return@inTransaction MutateOutcome.SessionNotFound
                    MutateOutcome.Updated(updated)
                }
            }
        }

    override suspend fun leave(
        sessionId: UUID,
        userId: UUID,
        now: Instant,
    ): MutateOutcome =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection.inTransaction {
                    val session =
                        connection.lockSession(json, sessionId)
                            ?: return@inTransaction MutateOutcome.SessionNotFound
                    if (session.participants.none { it.userId == userId }) {
                        return@inTransaction MutateOutcome.NotParticipant
                    }
                    if (session.status in TERMINAL_STATUSES) {
                        return@inTransaction MutateOutcome.InvalidState
                    }

                    connection.setSessionStatus(sessionId, STATUS_CANCELLED, endedAt = now)

                    val updated =
                        connection.loadSession(json, sessionId)
                            ?: return@inTransaction MutateOutcome.SessionNotFound
                    MutateOutcome.Updated(updated)
                }
            }
        }

    override suspend fun findById(
        sessionId: UUID,
        withDisplayNames: Boolean,
    ): PsyncSession? =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection -> connection.loadSession(json, sessionId, withDisplayNames) }
        }

    override suspend fun findCurrentForUser(
        userId: UUID,
        now: Instant,
    ): PsyncSession? =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                val sessionId =
                    connection
                        .prepareStatement(
                            """
                            SELECT s.id
                            FROM psync_sessions s
                            JOIN psync_session_participants p ON p.session_id = s.id
                            WHERE p.user_id = ?
                              AND s.status IN ($NON_TERMINAL_STATUS_LIST)
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

    override suspend fun expireStale(now: Instant): List<PsyncExpiredSession> =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection.inTransaction {
                    // Lock the rows we're about to expire so participants line up with the flip.
                    val expiredIds =
                        connection
                            .prepareStatement(
                                """
                                SELECT id FROM psync_sessions
                                WHERE status IN ($EXPIRABLE_STATUS_LIST)
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

    // ---- PS-2: shared deck + private swipes --------------------------------------------

    override suspend fun start(
        sessionId: UUID,
        userId: UUID,
        now: Instant,
    ): StartOutcome =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection.inTransaction {
                    val session =
                        connection.lockSession(json, sessionId)
                            ?: return@inTransaction StartOutcome.SessionNotFound
                    if (session.participants.none { it.userId == userId }) {
                        return@inTransaction StartOutcome.NotParticipant
                    }
                    // Idempotent: the deck is already generated and the session is live.
                    if (session.status == STATUS_ACTIVE) {
                        return@inTransaction StartOutcome.Updated(session, allStarted = true, alreadyActive = true)
                    }
                    if (session.status != STATUS_READY) {
                        return@inTransaction StartOutcome.InvalidState
                    }

                    connection.setParticipantStarted(sessionId, userId, now)

                    val allStarted =
                        session.participants.all { existing ->
                            existing.userId == userId || existing.started
                        }
                    val updated =
                        connection.loadSession(json, sessionId)
                            ?: return@inTransaction StartOutcome.SessionNotFound
                    StartOutcome.Updated(updated, allStarted = allStarted, alreadyActive = false)
                }
            }
        }

    override suspend fun activateWithDeck(
        sessionId: UUID,
        deckSessionId: UUID,
        cards: List<PsyncSessionCard>,
        now: Instant,
    ): MutateOutcome =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection.inTransaction {
                    val session =
                        connection.lockSession(json, sessionId)
                            ?: return@inTransaction MutateOutcome.SessionNotFound
                    // Race guard: another start already activated + persisted the deck.
                    if (session.status == STATUS_ACTIVE) {
                        return@inTransaction MutateOutcome.Updated(session)
                    }
                    if (session.status != STATUS_READY) {
                        return@inTransaction MutateOutcome.InvalidState
                    }

                    connection
                        .prepareStatement(
                            "UPDATE psync_sessions SET status = ?, started_at = ?, deck_session_id = ? WHERE id = ?",
                        ).use { statement ->
                            statement.setString(1, STATUS_ACTIVE)
                            statement.setTimestamp(2, Timestamp.from(now))
                            statement.setObject(3, deckSessionId)
                            statement.setObject(4, sessionId)
                            statement.executeUpdate()
                        }
                    cards.forEach { connection.insertDeckCard(json, it) }

                    val updated =
                        connection.loadSession(json, sessionId)
                            ?: return@inTransaction MutateOutcome.SessionNotFound
                    MutateOutcome.Updated(updated)
                }
            }
        }

    override suspend fun listDeckCards(sessionId: UUID): List<PsyncSessionCard> =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection
                    .prepareStatement(
                        """
                        SELECT id, session_id, position, target_user_id, card_token_hash,
                               card_snapshot::text AS card_snapshot, dealt_at
                        FROM psync_session_cards
                        WHERE session_id = ?
                        ORDER BY position ASC
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setObject(1, sessionId)
                        statement.executeQuery().use { rs ->
                            buildList { while (rs.next()) add(rs.toDeckCard(json)) }
                        }
                    }
            }
        }

    override suspend fun listActionsForActor(
        sessionId: UUID,
        actorUserId: UUID,
    ): List<PsyncSessionAction> =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection
                    .prepareStatement(
                        """
                        SELECT actor_user_id, target_user_id, action, created_at
                        FROM psync_session_actions
                        WHERE session_id = ? AND actor_user_id = ?
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setObject(1, sessionId)
                        statement.setObject(2, actorUserId)
                        statement.executeQuery().use { rs ->
                            buildList { while (rs.next()) add(rs.toAction()) }
                        }
                    }
            }
        }

    override suspend fun findCardByTokenHash(
        sessionId: UUID,
        cardTokenHash: String,
    ): PsyncSessionCard? =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection
                    .prepareStatement(
                        """
                        SELECT id, session_id, position, target_user_id, card_token_hash,
                               card_snapshot::text AS card_snapshot, dealt_at
                        FROM psync_session_cards
                        WHERE session_id = ? AND card_token_hash = ?
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setObject(1, sessionId)
                        statement.setString(2, cardTokenHash)
                        statement.executeQuery().use { rs -> if (!rs.next()) null else rs.toDeckCard(json) }
                    }
            }
        }

    override suspend fun recordAction(
        sessionId: UUID,
        actorUserId: UUID,
        targetUserId: UUID,
        action: String,
        now: Instant,
        actionIdFactory: () -> UUID,
    ): RecordActionOutcome =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection.inTransaction {
                    val inserted =
                        connection
                            .prepareStatement(
                                """
                                INSERT INTO psync_session_actions (id, session_id, actor_user_id, target_user_id, action, created_at)
                                VALUES (?, ?, ?, ?, ?, ?)
                                ON CONFLICT (session_id, actor_user_id, target_user_id) DO NOTHING
                                """.trimIndent(),
                            ).use { statement ->
                                statement.setObject(1, actionIdFactory())
                                statement.setObject(2, sessionId)
                                statement.setObject(3, actorUserId)
                                statement.setObject(4, targetUserId)
                                statement.setString(5, action)
                                statement.setTimestamp(6, Timestamp.from(now))
                                statement.executeUpdate()
                            }
                    val actedCount = connection.countActions(sessionId, actorUserId)
                    RecordActionOutcome(newlyRecorded = inserted > 0, actedCount = actedCount)
                }
            }
        }

    override suspend fun countActionsForActor(
        sessionId: UUID,
        actorUserId: UUID,
    ): Int =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection -> connection.countActions(sessionId, actorUserId) }
        }

    override suspend fun transitionStatus(
        sessionId: UUID,
        userId: UUID,
        fromStatus: String,
        toStatus: String,
    ): MutateOutcome =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection.inTransaction {
                    val session =
                        connection.lockSession(json, sessionId)
                            ?: return@inTransaction MutateOutcome.SessionNotFound
                    if (session.participants.none { it.userId == userId }) {
                        return@inTransaction MutateOutcome.NotParticipant
                    }
                    if (session.status != fromStatus) {
                        return@inTransaction MutateOutcome.InvalidState
                    }
                    connection.setSessionStatus(sessionId, toStatus)
                    val updated =
                        connection.loadSession(json, sessionId)
                            ?: return@inTransaction MutateOutcome.SessionNotFound
                    MutateOutcome.Updated(updated)
                }
            }
        }

    // ---- PS-3: match resolution + recap ------------------------------------------------

    override suspend fun complete(
        sessionId: UUID,
        userId: UUID,
        now: Instant,
        matchIdFactory: () -> UUID,
    ): CompleteOutcome =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection.inTransaction {
                    val session =
                        connection.lockSession(json, sessionId)
                            ?: return@inTransaction CompleteOutcome.SessionNotFound
                    if (session.participants.none { it.userId == userId }) {
                        return@inTransaction CompleteOutcome.NotParticipant
                    }
                    // Idempotent: matches were already resolved on a prior completion. Return no
                    // resolved matches so the service does not re-emit Activity/notifications.
                    if (session.status == STATUS_COMPLETED) {
                        return@inTransaction CompleteOutcome.Completed(session, emptyList())
                    }
                    if (session.status != STATUS_ACTIVE && session.status != STATUS_PAUSED) {
                        return@inTransaction CompleteOutcome.InvalidState
                    }

                    val resolved = connection.resolveMatches(session, matchIdFactory)
                    connection.setSessionStatus(sessionId, STATUS_COMPLETED, endedAt = now)

                    val updated =
                        connection.loadSession(json, sessionId)
                            ?: return@inTransaction CompleteOutcome.SessionNotFound
                    CompleteOutcome.Completed(updated, resolved)
                }
            }
        }

    override suspend fun listSessionMatches(sessionId: UUID): List<PsyncSessionMatch> =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
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

    override suspend fun countDeckCards(sessionId: UUID): Int =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
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

    override suspend fun countBothLiked(sessionId: UUID): Int =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
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

    override suspend fun isSoloShareEnabled(userId: UUID): Boolean =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection
                    .prepareStatement(
                        "SELECT COALESCE(share_psync_solo_matches, false) FROM discovery_settings WHERE user_id = ?",
                    ).use { statement ->
                        statement.setObject(1, userId)
                        statement.executeQuery().use { rs -> if (!rs.next()) false else rs.getBoolean(1) }
                    }
            }
        }

    private companion object {
        const val STATUS_INVITED = "invited"
        const val STATUS_CONFIGURING = "configuring"
        const val STATUS_READY = "ready"
        const val STATUS_ACTIVE = "active"
        const val STATUS_PAUSED = "paused"
        const val STATUS_COMPLETED = "completed"
        const val STATUS_CANCELLED = "cancelled"
        const val CONSENT_ACCEPTED = "accepted"
        const val CONSENT_DENIED = "denied"

        // Non-terminal: a session that still has a future (negotiating or live). Terminal =
        // completed/expired/cancelled. (active/paused belong to later phases but are listed so
        // the guards behave once PS-2 lands.)
        val NON_TERMINAL_STATUSES = setOf("invited", "configuring", "ready", "active", "paused")
        val TERMINAL_STATUSES = setOf("completed", "expired", "cancelled")

        // Filters can be re-suggested while negotiating or once ready (re-opens negotiation).
        val SUGGESTABLE_STATUSES = setOf("invited", "configuring", "ready")

        // Sweep these to 'expired' when past expires_at.
        val EXPIRABLE_STATUSES = setOf("invited", "configuring", "ready", "paused")

        val NON_TERMINAL_STATUS_LIST = NON_TERMINAL_STATUSES.joinToString(", ") { "'$it'" }
        val EXPIRABLE_STATUS_LIST = EXPIRABLE_STATUSES.joinToString(", ") { "'$it'" }
    }
}
