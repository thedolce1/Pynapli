package com.pynapli.server.psync

import com.pynapli.shared.network.dto.SwipeCardDto
import kotlinx.serialization.json.Json
import org.postgresql.util.PGobject
import java.sql.Connection
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/**
 * Session/participant lifecycle JDBC operations for [JdbcPsyncSessionRepository], extracted to keep
 * the repository class focused on the [PsyncSessionRepository] interface methods. These are
 * `Connection` extension functions (the repository invokes them inside its `inTransaction` blocks);
 * `json` is threaded as a parameter for the few that serialize/deserialize snapshots. Row-mapping
 * lives in [PsyncSessionSql]. Behavior matches the original private members verbatim.
 */
internal fun Connection.insertParticipant(
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

internal fun Connection.lockSession(
    json: Json,
    sessionId: UUID,
): PsyncSession? {
    val exists =
        prepareStatement(
            "SELECT id FROM psync_sessions WHERE id = ? FOR UPDATE",
        ).use { statement ->
            statement.setObject(1, sessionId)
            statement.executeQuery().use { rs -> rs.next() }
        }
    return if (!exists) null else loadSession(json, sessionId)
}

internal fun Connection.setParticipantConsent(
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

internal fun Connection.resetAllApprovals(
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

internal fun Connection.updateFilters(
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

internal fun Connection.setSessionStatus(
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

internal fun Connection.loadSession(
    json: Json,
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
                if (!rs.next()) null else rs.toSession(json)
            }
        } ?: return null
    return session.copy(participants = loadParticipants(sessionId, withDisplayNames))
}

internal fun Connection.loadParticipants(
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

internal fun Connection.setParticipantStarted(
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

internal fun Connection.insertDeckCard(
    json: Json,
    card: PsyncSessionCard,
) {
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

internal fun Connection.countActions(
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
