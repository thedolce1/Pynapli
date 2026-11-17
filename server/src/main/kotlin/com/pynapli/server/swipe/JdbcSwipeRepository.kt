package com.pynapli.server.swipe

import com.pynapli.server.discovery.DiscoveryFilters
import java.time.Duration
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

class JdbcSwipeRepository(
    dataSource: DataSource,
    rankCacheTtl: Duration = Duration.ofSeconds(5),
    rankCacheMaxEntries: Int = 128,
    rankCacheWindow: Int = 128,
) : SwipeRepository {
    private val deckQueryActions =
        JdbcSwipeDeckQueryActions(
            dataSource = dataSource,
            rankCacheTtl = rankCacheTtl,
            rankCacheMaxEntries = rankCacheMaxEntries,
            rankCacheWindow = rankCacheWindow,
        )
    private val deckSessionActions = JdbcSwipeDeckSessionActions(dataSource)
    private val actionActions = JdbcSwipeActionPersistenceActions(dataSource)

    override suspend fun queryDeck(
        viewerUserId: UUID,
        radiusMeters: Int,
        limit: Int,
        filters: DiscoveryFilters,
        excludedTargetUserIds: Set<UUID>,
    ): SwipeDeckResult =
        deckQueryActions.queryDeck(
            viewerUserId = viewerUserId,
            radiusMeters = radiusMeters,
            limit = limit,
            filters = filters,
            excludedTargetUserIds = excludedTargetUserIds,
        )

    override suspend fun upsertDeckSession(
        session: SwipeDeckSessionRecord,
        cards: List<SwipeDeckSessionCardRecord>,
    ): Unit =
        deckSessionActions.upsertDeckSession(
            session = session,
            cards = cards,
        )

    override suspend fun findDeckSession(
        sessionId: UUID,
        viewerUserId: UUID,
    ): SwipeDeckSessionRecord? =
        deckSessionActions.findDeckSession(
            sessionId = sessionId,
            viewerUserId = viewerUserId,
        )

    override suspend fun findDeckSessionState(
        sessionId: UUID,
        viewerUserId: UUID,
    ): SwipeDeckSessionState? =
        deckSessionActions.findDeckSessionState(
            sessionId = sessionId,
            viewerUserId = viewerUserId,
        )

    override suspend fun listDeckSessionTargetIds(sessionId: UUID): Set<UUID> = deckSessionActions.listDeckSessionTargetIds(sessionId)

    override suspend fun isDeckCardActionable(
        sessionId: UUID,
        cardTokenHash: String,
        viewerUserId: UUID,
        targetUserId: UUID,
        now: Instant,
    ): Boolean =
        deckSessionActions.isDeckCardActionable(
            sessionId = sessionId,
            cardTokenHash = cardTokenHash,
            viewerUserId = viewerUserId,
            targetUserId = targetUserId,
            now = now,
        )

    override suspend fun markDeckCardActionRecorded(
        sessionId: UUID,
        cardTokenHash: String,
        targetUserId: UUID,
        action: String,
        now: Instant,
    ): Unit =
        deckSessionActions.markDeckCardActionRecorded(
            sessionId = sessionId,
            cardTokenHash = cardTokenHash,
            targetUserId = targetUserId,
            action = action,
            now = now,
        )

    override suspend fun isActionTargetEligible(
        viewerUserId: UUID,
        targetUserId: UUID,
        radiusMeters: Int,
        requireIntroNotesAllowed: Boolean,
    ): Boolean =
        actionActions.isActionTargetEligible(
            viewerUserId = viewerUserId,
            targetUserId = targetUserId,
            radiusMeters = radiusMeters,
            requireIntroNotesAllowed = requireIntroNotesAllowed,
        )

    override suspend fun checkActionPreconditions(
        sessionId: UUID,
        cardTokenHash: String,
        viewerUserId: UUID,
        targetUserId: UUID,
        radiusMeters: Int,
        requireIntroNotesAllowed: Boolean,
        now: Instant,
    ): SwipeActionPreconditionCheck =
        actionActions.checkActionPreconditions(
            sessionId = sessionId,
            cardTokenHash = cardTokenHash,
            viewerUserId = viewerUserId,
            targetUserId = targetUserId,
            radiusMeters = radiusMeters,
            requireIntroNotesAllowed = requireIntroNotesAllowed,
            now = now,
        )

    override suspend fun findSwipeActionById(
        actionId: UUID,
        actorUserId: UUID,
    ): SwipeActionRecord? =
        actionActions.findSwipeActionById(
            actionId = actionId,
            actorUserId = actorUserId,
        )

    override suspend fun findLatestRewindableAction(
        viewerUserId: UUID,
        radiusMeters: Int,
    ): SwipeRewindCandidateRecord? =
        actionActions.findLatestRewindableAction(
            viewerUserId = viewerUserId,
            radiusMeters = radiusMeters,
        )

    override suspend fun recordSwipeAction(
        actionId: UUID,
        actorUserId: UUID,
        targetUserId: UUID,
        action: String,
        noteText: String?,
        consumedInventoryId: UUID?,
        travelLocationId: UUID?,
        matchIdFactory: () -> UUID,
    ): SwipeActionResult =
        actionActions.recordSwipeAction(
            actionId = actionId,
            actorUserId = actorUserId,
            targetUserId = targetUserId,
            action = action,
            noteText = noteText,
            consumedInventoryId = consumedInventoryId,
            travelLocationId = travelLocationId,
            matchIdFactory = matchIdFactory,
        )

    override suspend fun likeBack(
        actionId: UUID,
        viewerUserId: UUID,
        targetUserId: UUID,
        travelLocationId: UUID?,
        matchIdFactory: () -> UUID,
    ): SwipeActionResult? = actionActions.likeBack(actionId, viewerUserId, targetUserId, travelLocationId, matchIdFactory)

    override suspend fun recordSwipeActionAndMarkCard(
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
        actionActions.recordSwipeActionAndMarkCard(
            actionId = actionId,
            actorUserId = actorUserId,
            targetUserId = targetUserId,
            action = action,
            sessionId = sessionId,
            cardTokenHash = cardTokenHash,
            now = now,
            noteText = noteText,
            travelLocationId = travelLocationId,
            spendInventory = spendInventory,
            matchIdFactory = matchIdFactory,
        )

    override suspend fun rewindSwipeAction(
        actionId: UUID,
        actorUserId: UUID,
        targetUserId: UUID,
        session: SwipeDeckSessionRecord,
        card: SwipeDeckSessionCardRecord,
        rewoundAt: Instant,
    ): Boolean =
        actionActions.rewindSwipeAction(
            actionId = actionId,
            actorUserId = actorUserId,
            targetUserId = targetUserId,
            session = session,
            card = card,
            rewoundAt = rewoundAt,
        )
}
