package com.pynapli.server.swipe

import com.pynapli.server.discovery.DiscoveryFilters
import com.pynapli.server.profiling.profile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.Connection
import java.time.Duration
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

class JdbcSwipeRepository(
    private val dataSource: DataSource,
    rankCacheTtl: Duration = Duration.ofSeconds(5),
    rankCacheMaxEntries: Int = 128,
    private val rankCacheWindow: Int = 128,
) : SwipeRepository {
    private val deckRankCache =
        JdbcSwipeDeckRankCache(
            ttl = rankCacheTtl,
            maxEntries = rankCacheMaxEntries,
        )

    override suspend fun queryDeck(
        viewerUserId: UUID,
        radiusMeters: Int,
        limit: Int,
        filters: DiscoveryFilters,
        excludedTargetUserIds: Set<UUID>,
    ): SwipeDeckResult =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                val viewerLocationKey =
                    profile("swipe.deck.repo.viewerLocation") {
                        connection.findViewerLocationCacheKey(viewerUserId)
                    }

                if (viewerLocationKey == null) {
                    return@withContext SwipeDeckResult(viewerHadLocation = false, cards = emptyList())
                }
                val hiddenTargetUserIds = connection.listHiddenProfileIds(viewerUserId)
                val discoveryExcludedTargetIds = excludedTargetUserIds + hiddenTargetUserIds

                // Two-phase deck query (mirrors the Grid pattern from IMPLEMENTATION_AUDIT L70):
                //   Phase 1 ranks candidates with the cheap row-level filters and base profile
                //   columns only. The photo carousel and linked-partner mini hydration LATERALs
                //   are deferred to Phase 2 so we evaluate them for the LIMIT N rows we will
                //   actually return, not every candidate inside ST_DWithin.
                val rankedRows =
                    profile("swipe.deck.repo.rank") {
                        connection.queryDeckRankCached(
                            viewerLocationKey = viewerLocationKey,
                            viewerUserId = viewerUserId,
                            radiusMeters = radiusMeters,
                            limit = limit,
                            filters = filters,
                            excludedTargetUserIds = discoveryExcludedTargetIds,
                        )
                    }

                if (rankedRows.isEmpty()) {
                    return@withContext SwipeDeckResult(viewerHadLocation = true, cards = emptyList())
                }

                val hydration =
                    profile("swipe.deck.repo.hydrate") {
                        connection.hydrateDeckCards(
                            viewerUserId = viewerUserId,
                            userIds = rankedRows.map { it.userId },
                        )
                    }

                val cards =
                    rankedRows
                        .map { row ->
                            val h = hydration[row.userId]
                            SwipeCardRecord(
                                userId = row.userId,
                                displayName = row.displayName,
                                displayAge = row.displayAge,
                                thumbnailMediaAssetId = h?.thumbnailMediaAssetId,
                                showDistance = row.showDistance,
                                distanceMeters = row.distanceMeters,
                                linkedPartnerUserId = h?.linkedPartnerUserId,
                                linkedPartnerDisplayName = h?.linkedPartnerDisplayName,
                                linkedPartnerThumbnailMediaAssetId = h?.linkedPartnerThumbnailMediaAssetId,
                                bio = row.bio,
                                tags = row.tags,
                                thumbnailMediaAssetIds = h?.thumbnailMediaAssetIds.orEmpty(),
                                verified = row.verified,
                                visiting = row.visiting,
                            )
                        }.map { card ->
                            if (card.linkedPartnerUserId in hiddenTargetUserIds) {
                                card.copy(
                                    linkedPartnerUserId = null,
                                    linkedPartnerDisplayName = null,
                                    linkedPartnerThumbnailMediaAssetId = null,
                                )
                            } else {
                                card
                            }
                        }

                SwipeDeckResult(viewerHadLocation = true, cards = cards)
            }
        }

    private fun Connection.queryDeckRankCached(
        viewerLocationKey: DeckLocationCacheKey,
        viewerUserId: UUID,
        radiusMeters: Int,
        limit: Int,
        filters: DiscoveryFilters,
        excludedTargetUserIds: Set<UUID>,
    ): List<DeckRankRow> {
        val activeTravelLocationsPresent = hasActiveTravelLocations()
        if (filters.linkedOnly == true) {
            val linkedTargetIds = listLinkedCandidateUserIdsNear(viewerUserId, radiusMeters)
            if (linkedTargetIds.isEmpty()) return emptyList()
            return queryDeckRank(
                viewerUserId = viewerUserId,
                radiusMeters = radiusMeters,
                limit = limit,
                filters = filters.copy(linkedOnly = null),
                excludedTargetUserIds = excludedTargetUserIds,
                userSpecificViewerId = viewerUserId,
                forcedTargetUserIds = linkedTargetIds,
                includeTravelLocations = activeTravelLocationsPresent,
            )
        }

        if (
            !filters.isRankCacheEligible() ||
            !deckRankCache.isEnabled()
        ) {
            return queryDeckRank(
                viewerUserId = viewerUserId,
                radiusMeters = radiusMeters,
                limit = limit,
                filters = filters,
                excludedTargetUserIds = excludedTargetUserIds,
                userSpecificViewerId = viewerUserId,
                useNearestCandidateWindow = !activeTravelLocationsPresent,
            )
        }

        val activeTravelTargetIds =
            if (activeTravelLocationsPresent) {
                listActiveTravelCandidateUserIdsNear(viewerUserId, radiusMeters)
            } else {
                emptySet()
            }
        val cacheWindow = rankCacheWindow.coerceAtLeast(limit)
        val cacheKey =
            DeckRankCacheKey(
                location = viewerLocationKey,
                radiusMeters = radiusMeters,
                filterKey = filters.rankCacheKey(),
                window = cacheWindow,
            )
        val cachedRows =
            deckRankCache.load(cacheKey) {
                queryDeckRank(
                    viewerUserId = viewerUserId,
                    radiusMeters = radiusMeters,
                    limit = cacheWindow,
                    filters = filters,
                    excludedTargetUserIds = emptySet(),
                    userSpecificViewerId = null,
                    useNearestCandidateWindow = true,
                )
            }
        val cachedTargetIds = cachedRows.mapTo(mutableSetOf()) { it.userId }
        val targetIdsForSwipeExclusion = cachedTargetIds + activeTravelTargetIds
        val swipedTargetIds = listActiveSwipedTargetIds(viewerUserId, targetIdsForSwipeExclusion)
        val excluded = excludedTargetUserIds + swipedTargetIds + viewerUserId
        val localPage =
            cachedRows
                .asSequence()
                .filterNot { it.userId in excluded }
                .take(limit)
                .toList()
        val travelRows =
            if (activeTravelTargetIds.isNotEmpty()) {
                queryDeckRankForcedTargets(
                    viewerUserId = viewerUserId,
                    radiusMeters = radiusMeters,
                    limit = limit,
                    filters = filters,
                    excludedTargetUserIds = excluded + localPage.map { it.userId },
                    userSpecificViewerId = viewerUserId,
                    forcedTargetUserIds = activeTravelTargetIds - excluded,
                    includeTravelLocations = true,
                )
            } else {
                emptyList()
            }
        val page =
            if (travelRows.isEmpty()) {
                localPage
            } else {
                (localPage + travelRows)
                    .distinctBy { it.userId }
                    .sortedWith(
                        compareByDescending<DeckRankRow> { it.isBoosted }
                            .thenByDescending { it.softScore }
                            .thenBy { it.distanceMeters },
                    ).take(limit)
            }

        if (page.size >= limit || cachedRows.size < cacheWindow) {
            return page
        }

        return queryDeckRank(
            viewerUserId = viewerUserId,
            radiusMeters = radiusMeters,
            limit = limit,
            filters = filters,
            excludedTargetUserIds = excludedTargetUserIds,
            userSpecificViewerId = viewerUserId,
            useNearestCandidateWindow = false,
        )
    }

    override suspend fun upsertDeckSession(
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

    override suspend fun findDeckSession(
        sessionId: UUID,
        viewerUserId: UUID,
    ): SwipeDeckSessionRecord? =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection.findDeckSession(sessionId, viewerUserId)
            }
        }

    override suspend fun findDeckSessionState(
        sessionId: UUID,
        viewerUserId: UUID,
    ): SwipeDeckSessionState? =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection.findDeckSessionState(sessionId, viewerUserId)
            }
        }

    override suspend fun listDeckSessionTargetIds(sessionId: UUID): Set<UUID> =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection.listDeckSessionTargetIds(sessionId)
            }
        }

    override suspend fun isDeckCardActionable(
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

    override suspend fun markDeckCardActionRecorded(
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

    override suspend fun isActionTargetEligible(
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

    override suspend fun checkActionPreconditions(
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

    override suspend fun findSwipeActionById(
        actionId: UUID,
        actorUserId: UUID,
    ): SwipeActionRecord? =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection.findSwipeActionById(actionId, actorUserId)
            }
        }

    override suspend fun findLatestRewindableAction(
        viewerUserId: UUID,
        radiusMeters: Int,
    ): SwipeRewindCandidateRecord? =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection.findLatestRewindableAction(viewerUserId, radiusMeters)
            }
        }

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
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
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
        }

    override suspend fun recordSwipeActionAndMarkCard(
        actionId: UUID,
        actorUserId: UUID,
        targetUserId: UUID,
        action: String,
        sessionId: UUID,
        cardTokenHash: String,
        now: Instant,
        noteText: String?,
        consumedInventoryId: UUID?,
        travelLocationId: UUID?,
        matchIdFactory: () -> UUID,
    ): SwipeActionResult =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection.recordSwipeActionTransaction(
                    actionId = actionId,
                    actorUserId = actorUserId,
                    targetUserId = targetUserId,
                    action = action,
                    noteText = noteText,
                    consumedInventoryId = consumedInventoryId,
                    travelLocationId = travelLocationId,
                    matchIdFactory = matchIdFactory,
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
        }

    override suspend fun rewindSwipeAction(
        actionId: UUID,
        actorUserId: UUID,
        targetUserId: UUID,
        session: SwipeDeckSessionRecord,
        card: SwipeDeckSessionCardRecord,
        rewoundAt: Instant,
    ): Boolean =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
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

    override suspend fun listMatches(
        viewerUserId: UUID,
        limit: Int,
    ): List<MatchSummaryRecord> =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection.listMatchSummaries(viewerUserId, limit)
            }
        }
}

private fun Connection.listHiddenProfileIds(viewerUserId: UUID): Set<UUID> =
    prepareStatement(
        """
        SELECT hidden_user_id
        FROM user_hidden_profiles
        WHERE hider_user_id = ?
        """.trimIndent(),
    ).use { statement ->
        statement.setObject(1, viewerUserId)
        statement.executeQuery().use { rs ->
            buildSet {
                while (rs.next()) {
                    add(rs.getObject("hidden_user_id", UUID::class.java))
                }
            }
        }
    }
