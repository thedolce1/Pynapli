package com.pynapli.server.tonight

import java.sql.Connection
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

internal const val TONIGHT_STATUS_PROPOSED = "proposed"
internal const val TONIGHT_STATUS_ACTIVE = "active"
internal const val TONIGHT_STATUS_DECLINED = "declined"
internal const val TONIGHT_APPROVAL_PENDING = "pending"
internal const val TONIGHT_APPROVAL_APPROVED = "approved"
internal const val TONIGHT_APPROVAL_DECLINED = "declined"

internal fun Connection.insertTonightParticipant(
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

internal fun Connection.lockTonightSession(sessionId: UUID): TonightSession? {
    val row =
        prepareStatement(
            "SELECT id FROM tonight_sessions WHERE id = ? FOR UPDATE",
        ).use { statement ->
            statement.setObject(1, sessionId)
            statement.executeQuery().use { rs -> rs.next() }
        }
    return if (!row) null else loadTonightSession(sessionId)
}

internal fun Connection.setTonightParticipantApproval(
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

internal fun Connection.setTonightSessionStatus(
    sessionId: UUID,
    status: String,
) {
    prepareStatement("UPDATE tonight_sessions SET status = ? WHERE id = ?").use { statement ->
        statement.setString(1, status)
        statement.setObject(2, sessionId)
        statement.executeUpdate()
    }
}

internal fun Connection.activateTonightSession(
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

internal fun Connection.loadTonightSession(
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
                if (!rs.next()) null else rs.toTonightSession()
            }
        } ?: return null
    return session.copy(participants = loadTonightParticipants(sessionId, withDisplayNames))
}

internal fun Connection.loadTonightParticipantsBySession(sessionIds: List<UUID>): Map<UUID, MutableList<UUID>> {
    if (sessionIds.isEmpty()) return emptyMap()
    return prepareStatement(
        """
        SELECT session_id, user_id
        FROM tonight_session_participants
        WHERE session_id = ANY (?)
        """.trimIndent(),
    ).use { statement ->
        statement.setArray(1, createArrayOf("uuid", sessionIds.toTypedArray()))
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
}

private fun Connection.loadTonightParticipants(
    sessionId: UUID,
    withDisplayNames: Boolean,
): List<TonightSessionParticipant> {
    val sql =
        if (withDisplayNames) {
            """
            SELECT p.user_id, p.role, p.approval_status, p.approved_at,
                   COALESCE(NULLIF(TRIM(pr.display_name), ''), 'New member') AS display_name
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
                    add(rs.toTonightParticipant())
                }
            }
        }
    }
}

private fun ResultSet.toTonightSession(): TonightSession =
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

private fun ResultSet.toTonightParticipant(): TonightSessionParticipant =
    TonightSessionParticipant(
        userId = getObject("user_id", UUID::class.java),
        role = getString("role"),
        approvalStatus = getString("approval_status"),
        approvedAt = getTimestamp("approved_at")?.toInstant(),
        displayName = getString("display_name"),
    )
