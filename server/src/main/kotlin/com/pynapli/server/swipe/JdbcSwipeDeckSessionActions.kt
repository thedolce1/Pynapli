package com.pynapli.server.swipe

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

internal class JdbcSwipeDeckSessionActions(
    private val dataSource: DataSource,
) {
    suspend fun upsertDeckSession(
        session: SwipeDeckSessionRecord,
        cards: List<SwipeDeckSessionCardRecord>,
    ): Unit =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                val previousAutoCommit = connection.autoCommit
                connection.autoCommit = false
                try {
                    connection.insertDeckSession(session)
                    connection.insertDeckCards(cards)

                    connection.commit()
                } catch (error: Throwable) {
                    connection.rollback()
                    throw error
                } finally {
                    connection.autoCommit = previousAutoCommit
                }
            }
        }

    suspend fun findDeckSession(
        sessionId: UUID,
        viewerUserId: UUID,
    ): SwipeDeckSessionRecord? =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection.findDeckSession(sessionId, viewerUserId)
            }
        }

    suspend fun findDeckSessionState(
        sessionId: UUID,
        viewerUserId: UUID,
    ): SwipeDeckSessionState? =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection.findDeckSessionState(sessionId, viewerUserId)
            }
        }

    suspend fun listDeckSessionTargetIds(sessionId: UUID): Set<UUID> =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection.listDeckSessionTargetIds(sessionId)
            }
        }

    suspend fun isDeckCardActionable(
        sessionId: UUID,
        cardTokenHash: String,
        viewerUserId: UUID,
        targetUserId: UUID,
        now: Instant,
    ): Boolean =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection.isDeckCardActionable(
                    sessionId = sessionId,
                    cardTokenHash = cardTokenHash,
                    viewerUserId = viewerUserId,
                    targetUserId = targetUserId,
                    now = now,
                )
            }
        }

    suspend fun markDeckCardActionRecorded(
        sessionId: UUID,
        cardTokenHash: String,
        targetUserId: UUID,
        action: String,
        now: Instant,
    ): Unit =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection.markDeckCardActionRecorded(sessionId, cardTokenHash, targetUserId, action, now)
            }
        }
}
