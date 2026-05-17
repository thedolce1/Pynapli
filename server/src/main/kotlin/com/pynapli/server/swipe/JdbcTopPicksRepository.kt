package com.pynapli.server.swipe

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.Connection
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

class JdbcTopPicksRepository(
    private val dataSource: DataSource
) : TopPicksRepository {

    override suspend fun findCurrentSession(userId: UUID, now: Instant): TopPickSessionWithCards? =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                val session = connection.prepareStatement(
                    """
                    SELECT id, user_id, deck_session_id, generated_at, expires_at
                    FROM top_pick_sessions
                    WHERE user_id = ?
                      AND expires_at > ?
                    ORDER BY generated_at DESC
                    LIMIT 1
                    """.trimIndent()
                ).use { statement ->
                    statement.setObject(1, userId)
                    statement.setTimestamp(2, Timestamp.from(now))
                    statement.executeQuery().use { rs ->
                        if (!rs.next()) return@use null
                        TopPickSessionRecord(
                            sessionId = rs.getObject("id", UUID::class.java),
                            userId = rs.getObject("user_id", UUID::class.java),
                            deckSessionId = rs.getObject("deck_session_id", UUID::class.java),
                            generatedAt = rs.getTimestamp("generated_at").toInstant(),
                            expiresAt = rs.getTimestamp("expires_at").toInstant()
                        )
                    }
                } ?: return@withContext null

                val cards = connection.selectCardsForSession(session.sessionId)
                TopPickSessionWithCards(session, cards)
            }
        }

    override suspend fun createSession(
        sessionId: UUID,
        userId: UUID,
        deckSessionId: UUID,
        generatedAt: Instant,
        expiresAt: Instant,
        cards: List<TopPickCardInsert>
    ): TopPickSessionWithCards = withContext(Dispatchers.IO) {
        dataSource.connection.use { connection ->
            val previousAutoCommit = connection.autoCommit
            connection.autoCommit = false
            try {
                connection.prepareStatement(
                    """
                    INSERT INTO top_pick_sessions (id, user_id, deck_session_id, generated_at, expires_at)
                    VALUES (?, ?, ?, ?, ?)
                    """.trimIndent()
                ).use { statement ->
                    statement.setObject(1, sessionId)
                    statement.setObject(2, userId)
                    statement.setObject(3, deckSessionId)
                    statement.setTimestamp(4, Timestamp.from(generatedAt))
                    statement.setTimestamp(5, Timestamp.from(expiresAt))
                    statement.executeUpdate()
                }

                if (cards.isNotEmpty()) {
                    connection.prepareStatement(
                        """
                        INSERT INTO top_pick_session_cards (
                            id, session_id, target_user_id, position, card_token_hash, dealt_at
                        ) VALUES (?, ?, ?, ?, ?, ?)
                        """.trimIndent()
                    ).use { statement ->
                        cards.forEach { card ->
                            statement.setObject(1, card.cardId)
                            statement.setObject(2, sessionId)
                            statement.setObject(3, card.targetUserId)
                            statement.setInt(4, card.position)
                            statement.setString(5, card.cardTokenHash)
                            statement.setTimestamp(6, Timestamp.from(card.dealtAt))
                            statement.addBatch()
                        }
                        statement.executeBatch()
                    }
                }

                connection.commit()
                TopPickSessionWithCards(
                    session = TopPickSessionRecord(
                        sessionId = sessionId,
                        userId = userId,
                        deckSessionId = deckSessionId,
                        generatedAt = generatedAt,
                        expiresAt = expiresAt
                    ),
                    cards = cards.map {
                        TopPickCardRecord(
                            cardId = it.cardId,
                            sessionId = sessionId,
                            targetUserId = it.targetUserId,
                            position = it.position,
                            cardTokenHash = it.cardTokenHash,
                            dealtAt = it.dealtAt
                        )
                    }
                )
            } catch (error: Throwable) {
                connection.rollback()
                throw error
            } finally {
                connection.autoCommit = previousAutoCommit
            }
        }
    }

    private fun Connection.selectCardsForSession(sessionId: UUID): List<TopPickCardRecord> =
        prepareStatement(
            """
            SELECT id, session_id, target_user_id, position, card_token_hash, dealt_at
            FROM top_pick_session_cards
            WHERE session_id = ?
            ORDER BY position ASC, id ASC
            """.trimIndent()
        ).use { statement ->
            statement.setObject(1, sessionId)
            statement.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            TopPickCardRecord(
                                cardId = rs.getObject("id", UUID::class.java),
                                sessionId = rs.getObject("session_id", UUID::class.java),
                                targetUserId = rs.getObject("target_user_id", UUID::class.java),
                                position = rs.getInt("position"),
                                cardTokenHash = rs.getString("card_token_hash"),
                                dealtAt = rs.getTimestamp("dealt_at").toInstant()
                            )
                        )
                    }
                }
            }
        }
}
