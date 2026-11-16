package com.pynapli.server.swipe

import com.pynapli.server.db.withConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

internal class JdbcSwipeActionPersistenceActions(
    private val dataSource: DataSource,
) {
    suspend fun isActionTargetEligible(
        viewerUserId: UUID,
        targetUserId: UUID,
        radiusMeters: Int,
        requireIntroNotesAllowed: Boolean,
    ): Boolean =
        withContext(Dispatchers.IO) {
            if (viewerUserId == targetUserId) return@withContext false
            dataSource.connection.use { connection ->
                connection.isSwipeActionTargetEligible(
                    viewerUserId = viewerUserId,
                    targetUserId = targetUserId,
                    radiusMeters = radiusMeters,
                    requireIntroNotesAllowed = requireIntroNotesAllowed,
                )
            }
        }

    suspend fun checkActionPreconditions(
        sessionId: UUID,
        cardTokenHash: String,
        viewerUserId: UUID,
        targetUserId: UUID,
        radiusMeters: Int,
        requireIntroNotesAllowed: Boolean,
        now: Instant,
    ): SwipeActionPreconditionCheck =
        withContext(Dispatchers.IO) {
            if (viewerUserId == targetUserId) {
                return@withContext SwipeActionPreconditionCheck(deckCardActionable = false, targetEligible = false)
            }
            dataSource.connection.use { connection ->
                connection.checkSwipeActionPreconditions(
                    sessionId = sessionId,
                    cardTokenHash = cardTokenHash,
                    viewerUserId = viewerUserId,
                    targetUserId = targetUserId,
                    radiusMeters = radiusMeters,
                    requireIntroNotesAllowed = requireIntroNotesAllowed,
                    now = now,
                )
            }
        }

    suspend fun findSwipeActionById(
        actionId: UUID,
        actorUserId: UUID,
    ): SwipeActionRecord? =
        dataSource.withConnection { connection ->
            connection.findSwipeActionById(actionId, actorUserId)
        }

    suspend fun findLatestRewindableAction(
        viewerUserId: UUID,
        radiusMeters: Int,
    ): SwipeRewindCandidateRecord? =
        dataSource.withConnection { connection ->
            connection.findLatestRewindableAction(viewerUserId, radiusMeters)
        }

    suspend fun recordSwipeAction(
        actionId: UUID,
        actorUserId: UUID,
        targetUserId: UUID,
        action: String,
        noteText: String?,
        consumedInventoryId: UUID?,
        travelLocationId: UUID?,
        matchIdFactory: () -> UUID,
    ): SwipeActionResult =
        dataSource.withConnection { connection ->
            connection.recordSwipeActionTransaction(
                actionId = actionId,
                actorUserId = actorUserId,
                targetUserId = targetUserId,
                action = action,
                noteText = noteText,
                consumedInventoryId = consumedInventoryId,
                travelLocationId = travelLocationId,
                matchIdFactory = matchIdFactory,
            )
        }

    suspend fun likeBack(
        actionId: UUID,
        viewerUserId: UUID,
        targetUserId: UUID,
        travelLocationId: UUID?,
        matchIdFactory: () -> UUID,
    ): SwipeActionResult? =
        dataSource.withConnection { connection ->
            connection.likeBackTransaction(actionId, viewerUserId, targetUserId, travelLocationId, matchIdFactory)
        }

    suspend fun recordSwipeActionAndMarkCard(
        actionId: UUID,
        actorUserId: UUID,
        targetUserId: UUID,
        action: String,
        sessionId: UUID,
        cardTokenHash: String,
        now: Instant,
        noteText: String?,
        travelLocationId: UUID?,
        spendInventory: SwipeInventorySpend?,
        matchIdFactory: () -> UUID,
    ): SwipeActionResult =
        dataSource.withConnection { connection ->
            connection.recordSwipeActionTransaction(
                actionId = actionId,
                actorUserId = actorUserId,
                targetUserId = targetUserId,
                action = action,
                noteText = noteText,
                consumedInventoryId = null,
                travelLocationId = travelLocationId,
                matchIdFactory = matchIdFactory,
                spendInventory = spendInventory,
                now = now,
            ) {
                markDeckCardActionRecorded(
                    sessionId = sessionId,
                    cardTokenHash = cardTokenHash,
                    targetUserId = targetUserId,
                    action = action,
                    now = now,
                )
            }
        }

    suspend fun rewindSwipeAction(
        actionId: UUID,
        actorUserId: UUID,
        targetUserId: UUID,
        session: SwipeDeckSessionRecord,
        card: SwipeDeckSessionCardRecord,
        rewoundAt: Instant,
    ): Boolean =
        dataSource.withConnection { connection ->
            connection.rewindSwipeActionTransaction(
                actionId = actionId,
                actorUserId = actorUserId,
                targetUserId = targetUserId,
                session = session,
                card = card,
                rewoundAt = rewoundAt,
            )
        }
}
