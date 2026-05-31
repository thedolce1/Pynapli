package com.pynapli.server.swipe

import java.sql.Connection
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

internal fun Connection.insertDeckSession(session: SwipeDeckSessionRecord) {
    prepareStatement(
        """
        INSERT INTO swipe_deck_sessions (
            id,
            deck_session_token,
            deck_session_token_hash,
            viewer_user_id,
            scope_hash,
            radius_meters,
            page_limit,
            filter_payload,
            generated_at,
            expires_at
        )
        VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)
        ON CONFLICT (id) DO NOTHING
        """.trimIndent(),
    ).use { statement ->
        statement.setObject(1, session.sessionId)
        statement.setString(2, session.deckSessionToken)
        statement.setString(3, session.deckSessionTokenHash)
        statement.setObject(4, session.viewerUserId)
        statement.setString(5, session.scopeHash)
        statement.setInt(6, session.radiusMeters)
        statement.setInt(7, session.pageLimit)
        statement.setString(8, session.filterPayload)
        statement.setTimestamp(9, Timestamp.from(session.generatedAt))
        statement.setTimestamp(10, Timestamp.from(session.expiresAt))
        statement.executeUpdate()
    }
}

internal fun Connection.insertDeckCards(cards: List<SwipeDeckSessionCardRecord>) {
    if (cards.isEmpty()) return
    prepareStatement(
        """
        INSERT INTO swipe_deck_cards (
            id,
            deck_session_id,
            viewer_user_id,
            target_user_id,
            card_token_hash,
            position,
            dealt_at
        )
        VALUES (?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT DO NOTHING
        """.trimIndent(),
    ).use { statement ->
        for (card in cards) {
            statement.setObject(1, card.cardId)
            statement.setObject(2, card.sessionId)
            statement.setObject(3, card.viewerUserId)
            statement.setObject(4, card.targetUserId)
            statement.setString(5, card.cardTokenHash)
            statement.setInt(6, card.position)
            statement.setTimestamp(7, Timestamp.from(card.dealtAt))
            statement.addBatch()
        }
        statement.executeBatch()
    }
}

internal fun Connection.findDeckSession(
    sessionId: UUID,
    viewerUserId: UUID,
): SwipeDeckSessionRecord? =
    prepareStatement(
        """
        SELECT id,
               deck_session_token,
               deck_session_token_hash,
               viewer_user_id,
               scope_hash,
               radius_meters,
               page_limit,
               filter_payload::text AS filter_payload,
               generated_at,
               expires_at
        FROM swipe_deck_sessions
        WHERE id = ?
          AND viewer_user_id = ?
        """.trimIndent(),
    ).use { statement ->
        statement.setObject(1, sessionId)
        statement.setObject(2, viewerUserId)
        statement.executeQuery().use { rs ->
            if (!rs.next()) null else rs.toSwipeDeckSessionRecord()
        }
    }

internal fun Connection.findDeckSessionState(
    sessionId: UUID,
    viewerUserId: UUID,
): SwipeDeckSessionState? {
    val session = findDeckSession(sessionId, viewerUserId) ?: return null
    return SwipeDeckSessionState(
        session = session,
        targetUserIds = listDeckSessionTargetIds(sessionId),
    )
}

internal fun Connection.listDeckSessionTargetIds(sessionId: UUID): Set<UUID> =
    prepareStatement(
        """
        SELECT target_user_id
        FROM swipe_deck_cards
        WHERE deck_session_id = ?
        """.trimIndent(),
    ).use { statement ->
        statement.setObject(1, sessionId)
        statement.executeQuery().use { rs ->
            buildSet {
                while (rs.next()) {
                    add(rs.getObject("target_user_id", UUID::class.java))
                }
            }
        }
    }

internal fun Connection.isDeckCardActionable(
    sessionId: UUID,
    cardTokenHash: String,
    viewerUserId: UUID,
    targetUserId: UUID,
    now: Instant,
): Boolean =
    prepareStatement(
        """
        SELECT EXISTS (
            SELECT 1
            FROM swipe_deck_sessions s
            JOIN swipe_deck_cards c
              ON c.deck_session_id = s.id
            WHERE s.id = ?
              AND s.viewer_user_id = ?
              AND c.viewer_user_id = ?
              AND c.target_user_id = ?
              AND c.card_token_hash = ?
              AND s.expires_at > ?
              AND c.action_recorded_at IS NULL
        )
        """.trimIndent(),
    ).use { statement ->
        statement.setObject(1, sessionId)
        statement.setObject(2, viewerUserId)
        statement.setObject(3, viewerUserId)
        statement.setObject(4, targetUserId)
        statement.setString(5, cardTokenHash)
        statement.setTimestamp(6, Timestamp.from(now))
        statement.executeQuery().use { rs ->
            rs.next()
            rs.getBoolean(1)
        }
    }

internal fun Connection.markDeckCardActionRecorded(
    sessionId: UUID,
    cardTokenHash: String,
    targetUserId: UUID,
    action: String,
    now: Instant,
) {
    prepareStatement(
        """
        UPDATE swipe_deck_cards
        SET action = ?,
            action_recorded_at = ?
        WHERE deck_session_id = ?
          AND target_user_id = ?
          AND card_token_hash = ?
          AND action_recorded_at IS NULL
        """.trimIndent(),
    ).use { statement ->
        statement.setString(1, action)
        statement.setTimestamp(2, Timestamp.from(now))
        statement.setObject(3, sessionId)
        statement.setObject(4, targetUserId)
        statement.setString(5, cardTokenHash)
        statement.executeUpdate()
    }
}

internal fun ResultSet.toSwipeDeckSessionRecord(): SwipeDeckSessionRecord =
    SwipeDeckSessionRecord(
        sessionId = getObject("id", UUID::class.java),
        deckSessionToken = getString("deck_session_token"),
        deckSessionTokenHash = getString("deck_session_token_hash"),
        viewerUserId = getObject("viewer_user_id", UUID::class.java),
        scopeHash = getString("scope_hash"),
        radiusMeters = getInt("radius_meters"),
        pageLimit = getInt("page_limit"),
        filterPayload = getString("filter_payload"),
        generatedAt = getTimestamp("generated_at").toInstant(),
        expiresAt = getTimestamp("expires_at").toInstant(),
    )
