package com.pynapli.server.swipe

import com.pynapli.server.db.inTransaction
import com.pynapli.server.db.withConnection
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
        dataSource.withConnection { connection ->
            connection.inTransaction {
                connection.insertDeckSession(session)
                connection.insertDeckCards(cards)

                connection.commit()
            }
        }

    suspend fun findDeckSession(
        sessionId: UUID,
        viewerUserId: UUID,
    ): SwipeDeckSessionRecord? =
        dataSource.withConnection { connection ->
            connection.findDeckSession(sessionId, viewerUserId)
        }

    suspend fun findDeckSessionState(
        sessionId: UUID,
        viewerUserId: UUID,
    ): SwipeDeckSessionState? =
        dataSource.withConnection { connection ->
            connection.findDeckSessionState(sessionId, viewerUserId)
        }

    suspend fun listDeckSessionTargetIds(sessionId: UUID): Set<UUID> =
        dataSource.withConnection { connection ->
            connection.listDeckSessionTargetIds(sessionId)
        }

    suspend fun isDeckCardActionable(
        sessionId: UUID,
        cardTokenHash: String,
        viewerUserId: UUID,
        targetUserId: UUID,
        now: Instant,
    ): Boolean =
        dataSource.withConnection { connection ->
            connection.isDeckCardActionable(
                sessionId = sessionId,
                cardTokenHash = cardTokenHash,
                viewerUserId = viewerUserId,
                targetUserId = targetUserId,
                now = now,
            )
        }

    suspend fun markDeckCardActionRecorded(
        sessionId: UUID,
        cardTokenHash: String,
        targetUserId: UUID,
        action: String,
        now: Instant,
    ): Unit =
        dataSource.withConnection { connection ->
            connection.markDeckCardActionRecorded(sessionId, cardTokenHash, targetUserId, action, now)
        }
}
