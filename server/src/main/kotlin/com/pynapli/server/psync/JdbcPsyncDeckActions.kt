package com.pynapli.server.psync

import com.pynapli.server.db.inTransaction
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

internal suspend fun DataSource.startPsyncSession(
    json: Json,
    sessionId: UUID,
    userId: UUID,
    now: Instant,
): StartOutcome =
    withContext(Dispatchers.IO) {
        connection.use { connection ->
            connection.inTransaction {
                val session =
                    connection.lockSession(json, sessionId)
                        ?: return@inTransaction StartOutcome.SessionNotFound
                if (session.participants.none { it.userId == userId }) {
                    return@inTransaction StartOutcome.NotParticipant
                }
                // Idempotent: the deck is already generated and the session is live.
                if (session.status == PSYNC_STATUS_ACTIVE) {
                    return@inTransaction StartOutcome.Updated(session, allStarted = true, alreadyActive = true)
                }
                if (session.status != PSYNC_STATUS_READY) {
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

internal suspend fun DataSource.activatePsyncWithDeck(
    json: Json,
    sessionId: UUID,
    deckSessionId: UUID,
    cards: List<PsyncSessionCard>,
    now: Instant,
): MutateOutcome =
    withContext(Dispatchers.IO) {
        connection.use { connection ->
            connection.inTransaction {
                val session =
                    connection.lockSession(json, sessionId)
                        ?: return@inTransaction MutateOutcome.SessionNotFound
                // Race guard: another start already activated + persisted the deck.
                if (session.status == PSYNC_STATUS_ACTIVE) {
                    return@inTransaction MutateOutcome.Updated(session)
                }
                if (session.status != PSYNC_STATUS_READY) {
                    return@inTransaction MutateOutcome.InvalidState
                }

                connection
                    .prepareStatement(
                        "UPDATE psync_sessions SET status = ?, started_at = ?, deck_session_id = ? WHERE id = ?",
                    ).use { statement ->
                        statement.setString(1, PSYNC_STATUS_ACTIVE)
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

internal suspend fun DataSource.listPsyncDeckCards(
    json: Json,
    sessionId: UUID,
): List<PsyncSessionCard> =
    withContext(Dispatchers.IO) {
        connection.use { connection ->
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

internal suspend fun DataSource.listPsyncActionsForActor(
    sessionId: UUID,
    actorUserId: UUID,
): List<PsyncSessionAction> =
    withContext(Dispatchers.IO) {
        connection.use { connection ->
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

internal suspend fun DataSource.findPsyncCardByTokenHash(
    json: Json,
    sessionId: UUID,
    cardTokenHash: String,
): PsyncSessionCard? =
    withContext(Dispatchers.IO) {
        connection.use { connection ->
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

internal suspend fun DataSource.recordPsyncAction(
    sessionId: UUID,
    actorUserId: UUID,
    targetUserId: UUID,
    action: String,
    now: Instant,
    actionIdFactory: () -> UUID,
    consumedInventoryId: UUID?,
): RecordActionOutcome =
    withContext(Dispatchers.IO) {
        connection.use { connection ->
            connection.inTransaction {
                val inserted =
                    connection
                        .prepareStatement(
                            """
                            INSERT INTO psync_session_actions (
                                id, session_id, actor_user_id, target_user_id, action, created_at, consumed_inventory_id
                            )
                            VALUES (?, ?, ?, ?, ?, ?, ?)
                            ON CONFLICT (session_id, actor_user_id, target_user_id) DO NOTHING
                            """.trimIndent(),
                        ).use { statement ->
                            statement.setObject(1, actionIdFactory())
                            statement.setObject(2, sessionId)
                            statement.setObject(3, actorUserId)
                            statement.setObject(4, targetUserId)
                            statement.setString(5, action)
                            statement.setTimestamp(6, Timestamp.from(now))
                            statement.setObject(7, consumedInventoryId)
                            statement.executeUpdate()
                        }
                val actedCount = connection.countActions(sessionId, actorUserId)
                RecordActionOutcome(newlyRecorded = inserted > 0, actedCount = actedCount)
            }
        }
    }

internal suspend fun DataSource.psyncAlreadySettledTargets(
    partnerUserIds: List<UUID>,
    targetUserIds: List<UUID>,
): Set<UUID> =
    if (partnerUserIds.isEmpty() || targetUserIds.isEmpty()) {
        emptySet()
    } else {
        withContext(Dispatchers.IO) {
            connection.use { connection ->
                connection
                    .prepareStatement(
                        """
                        SELECT DISTINCT t.id
                        FROM unnest(?::uuid[]) AS t(id)
                        CROSS JOIN unnest(?::uuid[]) AS p(id)
                        WHERE EXISTS (
                                  SELECT 1 FROM swipe_actions sa
                                  WHERE sa.actor_user_id = p.id
                                    AND sa.target_user_id = t.id
                                    AND sa.rewound_at IS NULL
                              )
                           OR EXISTS (
                                  SELECT 1 FROM matches m
                                  WHERE (m.user_a = p.id AND m.user_b = t.id)
                                     OR (m.user_a = t.id AND m.user_b = p.id)
                              )
                           OR EXISTS (
                                  SELECT 1 FROM safety_reports sr
                                  WHERE sr.reporter_user_id = p.id
                                    AND sr.reported_user_id = t.id
                              )
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setArray(1, connection.createArrayOf("uuid", targetUserIds.toTypedArray()))
                        statement.setArray(2, connection.createArrayOf("uuid", partnerUserIds.toTypedArray()))
                        statement.executeQuery().use { rs ->
                            buildSet { while (rs.next()) add(rs.getObject(1, UUID::class.java)) }
                        }
                    }
            }
        }
    }

/**
 * Claims spent-but-undelivered Pineapple Likes in ended sessions. "Delivered" means completion
 * wrote this actor's like on this target into `swipe_actions` for the session; from then on the
 * Swipe rewind path owns the refund. Clearing the column is the claim, so a row is returned once.
 */
internal suspend fun DataSource.claimUndeliveredPsyncPineappleRefunds(): List<UUID> =
    withContext(Dispatchers.IO) {
        connection.use { connection ->
            connection
                .prepareStatement(
                    """
                    WITH claim AS (
                        SELECT psa.id, psa.consumed_inventory_id
                        FROM psync_session_actions psa
                        JOIN psync_sessions s ON s.id = psa.session_id
                        WHERE psa.consumed_inventory_id IS NOT NULL
                          AND s.status IN ('completed', 'expired', 'cancelled')
                          AND NOT EXISTS (
                              SELECT 1 FROM swipe_actions sa
                              WHERE sa.psync_session_id = psa.session_id
                                AND sa.actor_user_id = psa.actor_user_id
                                AND sa.target_user_id = psa.target_user_id
                          )
                        FOR UPDATE OF psa SKIP LOCKED
                    )
                    UPDATE psync_session_actions p
                    SET consumed_inventory_id = NULL
                    FROM claim
                    WHERE p.id = claim.id
                    RETURNING claim.consumed_inventory_id
                    """.trimIndent(),
                ).use { statement ->
                    statement.executeQuery().use { rs ->
                        buildList { while (rs.next()) add(rs.getObject(1, UUID::class.java)) }
                    }
                }
        }
    }

internal suspend fun DataSource.countPsyncActionsForActor(
    sessionId: UUID,
    actorUserId: UUID,
): Int =
    withContext(Dispatchers.IO) {
        connection.use { connection -> connection.countActions(sessionId, actorUserId) }
    }

internal suspend fun DataSource.transitionPsyncStatus(
    json: Json,
    sessionId: UUID,
    userId: UUID,
    fromStatus: String,
    toStatus: String,
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
