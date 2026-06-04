package com.pynapli.server.psync

import com.pynapli.server.psync.PsyncSessionRepository.ActiveLink
import com.pynapli.server.psync.PsyncSessionRepository.CompleteOutcome
import com.pynapli.server.psync.PsyncSessionRepository.MutateOutcome
import com.pynapli.server.psync.PsyncSessionRepository.RecordActionOutcome
import com.pynapli.server.psync.PsyncSessionRepository.StartOutcome
import com.pynapli.shared.network.dto.SwipeCardDto
import com.pynapli.shared.network.dto.SwipeDeckRequestDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.postgresql.util.PGobject
import java.sql.Connection
import java.sql.ResultSet
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
                            statement.setObject(5, jsonbOf(session.filters))
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

                    connection.loadSession(session.id)
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
                        connection.lockSession(sessionId)
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
                        connection.loadSession(sessionId)
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
                        connection.lockSession(sessionId)
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
                        connection.loadSession(sessionId)
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
                        connection.lockSession(sessionId)
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
                        connection.loadSession(sessionId)
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
                        connection.lockSession(sessionId)
                            ?: return@inTransaction MutateOutcome.SessionNotFound
                    if (session.participants.none { it.userId == userId }) {
                        return@inTransaction MutateOutcome.NotParticipant
                    }
                    if (session.status in TERMINAL_STATUSES) {
                        return@inTransaction MutateOutcome.InvalidState
                    }

                    connection.setSessionStatus(sessionId, STATUS_CANCELLED, endedAt = now)

                    val updated =
                        connection.loadSession(sessionId)
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
            dataSource.connection.use { connection -> connection.loadSession(sessionId, withDisplayNames) }
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
                connection.loadSession(sessionId, withDisplayNames = true)
            }
        }

    override suspend fun expireStale(now: Instant): Int =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection
                    .prepareStatement(
                        """
                        UPDATE psync_sessions
                        SET status = 'expired', ended_at = ?
                        WHERE status IN ($EXPIRABLE_STATUS_LIST)
                          AND expires_at <= ?
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setTimestamp(1, Timestamp.from(now))
                        statement.setTimestamp(2, Timestamp.from(now))
                        statement.executeUpdate()
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
                        connection.lockSession(sessionId)
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
                        connection.loadSession(sessionId)
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
                        connection.lockSession(sessionId)
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
                    cards.forEach { connection.insertDeckCard(it) }

                    val updated =
                        connection.loadSession(sessionId)
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
                            buildList { while (rs.next()) add(rs.toDeckCard()) }
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
                        statement.executeQuery().use { rs -> if (!rs.next()) null else rs.toDeckCard() }
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
                        connection.lockSession(sessionId)
                            ?: return@inTransaction MutateOutcome.SessionNotFound
                    if (session.participants.none { it.userId == userId }) {
                        return@inTransaction MutateOutcome.NotParticipant
                    }
                    if (session.status != fromStatus) {
                        return@inTransaction MutateOutcome.InvalidState
                    }
                    connection.setSessionStatus(sessionId, toStatus)
                    val updated =
                        connection.loadSession(sessionId)
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
                        connection.lockSession(sessionId)
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
                        connection.loadSession(sessionId)
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

    private fun jsonbOf(filters: SwipeDeckRequestDto): PGobject =
        PGobject().apply {
            type = "jsonb"
            value = json.encodeToString(SwipeDeckRequestDto.serializer(), filters)
        }

    private fun Connection.insertParticipant(
        sessionId: UUID,
        participant: PsyncSessionParticipant,
    ) {
        prepareStatement(
            """
            INSERT INTO psync_session_participants (session_id, user_id, role, consent_status, approved, updated_at)
            VALUES (?, ?, ?, ?, ?, now())
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, sessionId)
            statement.setObject(2, participant.userId)
            statement.setString(3, participant.role)
            statement.setString(4, participant.consentStatus)
            statement.setBoolean(5, participant.approved)
            statement.executeUpdate()
        }
    }

    private fun Connection.lockSession(sessionId: UUID): PsyncSession? {
        val exists =
            prepareStatement(
                "SELECT id FROM psync_sessions WHERE id = ? FOR UPDATE",
            ).use { statement ->
                statement.setObject(1, sessionId)
                statement.executeQuery().use { rs -> rs.next() }
            }
        return if (!exists) null else loadSession(sessionId)
    }

    private fun Connection.setParticipantConsent(
        sessionId: UUID,
        userId: UUID,
        consentStatus: String,
        approved: Boolean,
        now: Instant,
    ) {
        prepareStatement(
            """
            UPDATE psync_session_participants
            SET consent_status = ?, approved = ?, updated_at = ?
            WHERE session_id = ? AND user_id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, consentStatus)
            statement.setBoolean(2, approved)
            statement.setTimestamp(3, Timestamp.from(now))
            statement.setObject(4, sessionId)
            statement.setObject(5, userId)
            statement.executeUpdate()
        }
    }

    private fun Connection.resetAllApprovals(
        sessionId: UUID,
        now: Instant,
    ) {
        prepareStatement(
            "UPDATE psync_session_participants SET approved = false, updated_at = ? WHERE session_id = ?",
        ).use { statement ->
            statement.setTimestamp(1, Timestamp.from(now))
            statement.setObject(2, sessionId)
            statement.executeUpdate()
        }
    }

    private fun Connection.updateFilters(
        sessionId: UUID,
        filtersJson: String,
    ) {
        prepareStatement("UPDATE psync_sessions SET filters = ?::jsonb WHERE id = ?").use { statement ->
            statement.setObject(
                1,
                PGobject().apply {
                    type = "jsonb"
                    value = filtersJson
                },
            )
            statement.setObject(2, sessionId)
            statement.executeUpdate()
        }
    }

    private fun Connection.setSessionStatus(
        sessionId: UUID,
        status: String,
        endedAt: Instant? = null,
    ) {
        prepareStatement(
            "UPDATE psync_sessions SET status = ?, ended_at = COALESCE(?, ended_at) WHERE id = ?",
        ).use { statement ->
            statement.setString(1, status)
            statement.setTimestamp(2, endedAt?.let(Timestamp::from))
            statement.setObject(3, sessionId)
            statement.executeUpdate()
        }
    }

    private fun Connection.loadSession(
        sessionId: UUID,
        withDisplayNames: Boolean = false,
    ): PsyncSession? {
        val session =
            prepareStatement(
                """
                SELECT id, partner_link_id, initiator_user_id, status, filters::text AS filters,
                       deck_size, deck_session_id, created_at, started_at, expires_at, ended_at
                FROM psync_sessions
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
    ): List<PsyncSessionParticipant> {
        val sql =
            if (withDisplayNames) {
                """
                SELECT p.user_id, p.role, p.consent_status, p.approved, p.started,
                       COALESCE(NULLIF(TRIM(pr.display_name), ''), 'Pynapli User') AS display_name
                FROM psync_session_participants p
                LEFT JOIN profiles pr ON pr.user_id = p.user_id
                WHERE p.session_id = ?
                ORDER BY p.role ASC, p.user_id ASC
                """.trimIndent()
            } else {
                """
                SELECT user_id, role, consent_status, approved, started, NULL AS display_name
                FROM psync_session_participants
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

    private fun ResultSet.toSession(): PsyncSession =
        PsyncSession(
            id = getObject("id", UUID::class.java),
            partnerLinkId = getObject("partner_link_id", UUID::class.java),
            initiatorUserId = getObject("initiator_user_id", UUID::class.java),
            status = getString("status"),
            filters = json.decodeFromString(SwipeDeckRequestDto.serializer(), getString("filters")),
            deckSize = getInt("deck_size"),
            deckSessionId = getObject("deck_session_id", UUID::class.java),
            createdAt = getTimestamp("created_at").toInstant(),
            startedAt = getTimestamp("started_at")?.toInstant(),
            expiresAt = getTimestamp("expires_at").toInstant(),
            endedAt = getTimestamp("ended_at")?.toInstant(),
            participants = emptyList(),
        )

    private fun Connection.setParticipantStarted(
        sessionId: UUID,
        userId: UUID,
        now: Instant,
    ) {
        prepareStatement(
            "UPDATE psync_session_participants SET started = true, updated_at = ? WHERE session_id = ? AND user_id = ?",
        ).use { statement ->
            statement.setTimestamp(1, Timestamp.from(now))
            statement.setObject(2, sessionId)
            statement.setObject(3, userId)
            statement.executeUpdate()
        }
    }

    private fun Connection.insertDeckCard(card: PsyncSessionCard) {
        prepareStatement(
            """
            INSERT INTO psync_session_cards (id, session_id, position, target_user_id, card_token_hash, card_snapshot, dealt_at)
            VALUES (?, ?, ?, ?, ?, ?::jsonb, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, card.id)
            statement.setObject(2, card.sessionId)
            statement.setInt(3, card.position)
            statement.setObject(4, card.targetUserId)
            statement.setString(5, card.cardTokenHash)
            statement.setObject(
                6,
                PGobject().apply {
                    type = "jsonb"
                    value = json.encodeToString(SwipeCardDto.serializer(), card.snapshot)
                },
            )
            statement.setTimestamp(7, Timestamp.from(card.dealtAt))
            statement.executeUpdate()
        }
    }

    private fun Connection.countActions(
        sessionId: UUID,
        actorUserId: UUID,
    ): Int =
        prepareStatement(
            "SELECT COUNT(*) FROM psync_session_actions WHERE session_id = ? AND actor_user_id = ?",
        ).use { statement ->
            statement.setObject(1, sessionId)
            statement.setObject(2, actorUserId)
            statement.executeQuery().use { rs ->
                rs.next()
                rs.getInt(1)
            }
        }

    /**
     * Resolve every deck target into a together / solo / no-match outcome and persist it.
     * Together: both partners liked + target is together-open + target reciprocated (liked >=1
     * partner). Otherwise each partner who liked a target that liked them back gets a solo
     * passthrough match. Blocks (either direction, either partner) skip the target entirely.
     */
    private fun Connection.resolveMatches(
        session: PsyncSession,
        matchIdFactory: () -> UUID,
    ): List<PsyncResolvedMatch> {
        val initiator = session.initiatorUserId
        val partner =
            session.participants.firstOrNull { it.userId != initiator }?.userId
                ?: return emptyList() // a session always has two participants; nothing to resolve otherwise
        val targets = deckTargetIds(session.id)
        val likedByInitiator = likedTargetsByActor(session.id, initiator)
        val likedByPartner = likedTargetsByActor(session.id, partner)

        return buildList {
            for (target in targets) {
                val aLiked = target in likedByInitiator
                val bLiked = target in likedByPartner
                if (!aLiked && !bLiked) continue
                // Blocks override all match state.
                if (blockedBetween(target, initiator) || blockedBetween(target, partner)) continue

                val tLikesA = targetLikesPartner(target, initiator)
                val tLikesB = targetLikesPartner(target, partner)

                if (aLiked && bLiked && togetherEnabled(target) && (tLikesA || tLikesB)) {
                    val matchA = upsertMatch(initiator, target, matchIdFactory)
                    val matchB = upsertMatch(partner, target, matchIdFactory)
                    insertSessionMatch(session.id, target, kind = "together", ownerUserId = null, matchId = matchB)
                    add(PsyncResolvedMatch(initiator, target, matchA, "together"))
                    add(PsyncResolvedMatch(partner, target, matchB, "together"))
                    continue
                }
                // Solo passthrough(s): each liking partner the target liked back.
                if (aLiked && tLikesA) {
                    val matchId = upsertMatch(initiator, target, matchIdFactory)
                    insertSessionMatch(session.id, target, kind = "solo", ownerUserId = initiator, matchId = matchId)
                    add(PsyncResolvedMatch(initiator, target, matchId, "solo"))
                }
                if (bLiked && tLikesB) {
                    val matchId = upsertMatch(partner, target, matchIdFactory)
                    insertSessionMatch(session.id, target, kind = "solo", ownerUserId = partner, matchId = matchId)
                    add(PsyncResolvedMatch(partner, target, matchId, "solo"))
                }
            }
        }
    }

    private fun Connection.deckTargetIds(sessionId: UUID): List<UUID> =
        prepareStatement(
            "SELECT target_user_id FROM psync_session_cards WHERE session_id = ? ORDER BY position ASC",
        ).use { statement ->
            statement.setObject(1, sessionId)
            statement.executeQuery().use { rs ->
                buildList { while (rs.next()) add(rs.getObject("target_user_id", UUID::class.java)) }
            }
        }

    private fun Connection.likedTargetsByActor(
        sessionId: UUID,
        actorUserId: UUID,
    ): Set<UUID> =
        prepareStatement(
            """
            SELECT target_user_id
            FROM psync_session_actions
            WHERE session_id = ? AND actor_user_id = ? AND action IN ('like', 'pineapple_like')
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, sessionId)
            statement.setObject(2, actorUserId)
            statement.executeQuery().use { rs ->
                buildSet { while (rs.next()) add(rs.getObject("target_user_id", UUID::class.java)) }
            }
        }

    private fun Connection.togetherEnabled(userId: UUID): Boolean =
        prepareStatement(
            "SELECT COALESCE(together_enabled, false) FROM discovery_settings WHERE user_id = ?",
        ).use { statement ->
            statement.setObject(1, userId)
            statement.executeQuery().use { rs -> if (!rs.next()) false else rs.getBoolean(1) }
        }

    private fun Connection.targetLikesPartner(
        targetUserId: UUID,
        partnerUserId: UUID,
    ): Boolean =
        prepareStatement(
            """
            SELECT EXISTS (
                SELECT 1 FROM swipe_actions
                WHERE actor_user_id = ? AND target_user_id = ? AND action = 'like'
            )
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, targetUserId)
            statement.setObject(2, partnerUserId)
            statement.executeQuery().use { rs ->
                rs.next()
                rs.getBoolean(1)
            }
        }

    private fun Connection.blockedBetween(
        userA: UUID,
        userB: UUID,
    ): Boolean =
        prepareStatement(
            """
            SELECT EXISTS (
                SELECT 1 FROM user_blocks
                WHERE (blocker_user_id = ? AND blocked_user_id = ?)
                   OR (blocker_user_id = ? AND blocked_user_id = ?)
            )
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, userA)
            statement.setObject(2, userB)
            statement.setObject(3, userB)
            statement.setObject(4, userA)
            statement.executeQuery().use { rs ->
                rs.next()
                rs.getBoolean(1)
            }
        }

    private fun Connection.upsertMatch(
        actorUserId: UUID,
        targetUserId: UUID,
        matchIdFactory: () -> UUID,
    ): UUID {
        val (userA, userB) =
            if (actorUserId.toString() < targetUserId.toString()) {
                actorUserId to targetUserId
            } else {
                targetUserId to actorUserId
            }
        return prepareStatement(
            """
            INSERT INTO matches (id, user_a, user_b)
            VALUES (?, ?, ?)
            ON CONFLICT (user_a, user_b) DO UPDATE SET user_a = EXCLUDED.user_a
            RETURNING id
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, matchIdFactory())
            statement.setObject(2, userA)
            statement.setObject(3, userB)
            statement.executeQuery().use { rs ->
                rs.next()
                rs.getObject("id", UUID::class.java)
            }
        }
    }

    private fun Connection.insertSessionMatch(
        sessionId: UUID,
        targetUserId: UUID,
        kind: String,
        ownerUserId: UUID?,
        matchId: UUID,
    ) {
        prepareStatement(
            """
            INSERT INTO psync_session_matches (id, session_id, target_user_id, kind, owner_user_id, match_id)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT DO NOTHING
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, UUID.randomUUID())
            statement.setObject(2, sessionId)
            statement.setObject(3, targetUserId)
            statement.setString(4, kind)
            statement.setObject(5, ownerUserId)
            statement.setObject(6, matchId)
            statement.executeUpdate()
        }
    }

    private fun ResultSet.toDeckCard(): PsyncSessionCard =
        PsyncSessionCard(
            id = getObject("id", UUID::class.java),
            sessionId = getObject("session_id", UUID::class.java),
            position = getInt("position"),
            targetUserId = getObject("target_user_id", UUID::class.java),
            cardTokenHash = getString("card_token_hash"),
            snapshot = json.decodeFromString(SwipeCardDto.serializer(), getString("card_snapshot")),
            dealtAt = getTimestamp("dealt_at").toInstant(),
        )

    private fun ResultSet.toAction(): PsyncSessionAction =
        PsyncSessionAction(
            actorUserId = getObject("actor_user_id", UUID::class.java),
            targetUserId = getObject("target_user_id", UUID::class.java),
            action = getString("action"),
            createdAt = getTimestamp("created_at").toInstant(),
        )

    private fun ResultSet.toParticipant(): PsyncSessionParticipant =
        PsyncSessionParticipant(
            userId = getObject("user_id", UUID::class.java),
            role = getString("role"),
            consentStatus = getString("consent_status"),
            approved = getBoolean("approved"),
            started = getBoolean("started"),
            displayName = getString("display_name"),
        )

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
