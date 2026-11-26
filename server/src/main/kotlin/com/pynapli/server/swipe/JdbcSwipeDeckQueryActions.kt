package com.pynapli.server.swipe

import com.pynapli.server.discovery.DiscoveryFilters
import com.pynapli.server.distance.CoarseLocation
import com.pynapli.server.profiling.profile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.Connection
import java.time.Duration
import java.util.UUID
import javax.sql.DataSource

internal class JdbcSwipeDeckQueryActions(
    private val dataSource: DataSource,
    rankCacheTtl: Duration,
    rankCacheMaxEntries: Int,
    private val rankCacheWindow: Int,
) {
    private val deckRankCache =
        JdbcSwipeDeckRankCache(
            ttl = rankCacheTtl,
            maxEntries = rankCacheMaxEntries,
        )

    suspend fun queryDeck(
        viewerUserId: UUID,
        radiusMeters: Int,
        limit: Int,
        filters: DiscoveryFilters,
        excludedTargetUserIds: Set<UUID>,
        hiddenFromViewerIds: Set<UUID> = emptySet(),
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
                //   Phase 1 ranks candidates with cheap row-level filters and base profile columns only.
                //   Phase 2 hydrates only the returned LIMIT N rows.
                val rankedRows =
                    profile("swipe.deck.repo.rank") {
                        connection.queryDeckRankCached(
                            viewerLocationKey = viewerLocationKey,
                            viewerUserId = viewerUserId,
                            // Only the fixed radius steps are ever tested (L-09), whoever calls.
                            radiusMeters = CoarseLocation.snapRadiusMeters(radiusMeters),
                            limit = limit,
                            filters = filters,
                            excludedTargetUserIds = discoveryExcludedTargetIds,
                            hiddenFromViewerIds = hiddenFromViewerIds,
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
                                linkedPartnerVerified = h?.linkedPartnerVerified ?: false,
                                visiting = row.visiting,
                            )
                        }.map { card ->
                            if (card.linkedPartnerUserId in hiddenTargetUserIds) {
                                card.copy(
                                    linkedPartnerUserId = null,
                                    linkedPartnerDisplayName = null,
                                    linkedPartnerThumbnailMediaAssetId = null,
                                    linkedPartnerVerified = false,
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
        hiddenFromViewerIds: Set<UUID>,
    ): List<DeckRankRow> {
        if (filters.linkedOnly == true) {
            val linkedTargetIds = listLinkedCandidateUserIdsNear(viewerUserId, radiusMeters, hiddenFromViewerIds)
            if (linkedTargetIds.isEmpty()) return emptyList()
            val linkedRows =
                queryDeckRank(
                    viewerUserId = viewerUserId,
                    radiusMeters = radiusMeters,
                    limit = limit,
                    filters = filters.copy(linkedOnly = null),
                    excludedTargetUserIds = excludedTargetUserIds,
                    userSpecificViewerId = viewerUserId,
                    forcedTargetUserIds = linkedTargetIds,
                    hiddenFromViewerIds = hiddenFromViewerIds,
                )
            return SwipeDeckBoostPlacement.arrange(linkedRows, limit)
        }

        if (!filters.isRankCacheEligible() || !deckRankCache.isEnabled()) {
            return SwipeDeckBoostPlacement.arrange(queryDeckRankForViewer(viewerUserId, radiusMeters, limit, filters, excludedTargetUserIds, hiddenFromViewerIds), limit)
        }

        // One ranked window per (viewer cell, radius step, filter class), shared by every viewer in
        // that cell; this viewer's own exclusions are applied below.
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
                )
            }
        val swipedTargetIds = listActiveSwipedTargetIds(viewerUserId, cachedRows.mapTo(mutableSetOf()) { it.userId })
        val excluded = excludedTargetUserIds + swipedTargetIds + viewerUserId
        val remaining = cachedRows.filterNot { it.userId in excluded }

        // The window (natural ranks 1..cacheWindow) plus the boosted candidates beyond it. Enough of
        // the window left for a page, or a window that holds everyone in range, serves this viewer;
        // otherwise the next candidates lie beyond it, so rank them for this viewer directly.
        val windowRows = cachedRows.count { it.naturalRank <= cacheWindow }
        val windowRowsLeft = remaining.count { it.naturalRank <= cacheWindow }
        if (windowRowsLeft >= limit || windowRows < cacheWindow) {
            return SwipeDeckBoostPlacement.arrange(remaining, limit)
        }

        return SwipeDeckBoostPlacement.arrange(queryDeckRankForViewer(viewerUserId, radiusMeters, limit, filters, excludedTargetUserIds, hiddenFromViewerIds), limit)
    }

    // Uncached rank for one viewer: self, already-swiped and session exclusions applied in SQL.
    private fun Connection.queryDeckRankForViewer(
        viewerUserId: UUID,
        radiusMeters: Int,
        limit: Int,
        filters: DiscoveryFilters,
        excludedTargetUserIds: Set<UUID>,
        hiddenFromViewerIds: Set<UUID>,
    ): List<DeckRankRow> =
        queryDeckRank(
            viewerUserId = viewerUserId,
            radiusMeters = radiusMeters,
            limit = limit,
            filters = filters,
            excludedTargetUserIds = excludedTargetUserIds,
            userSpecificViewerId = viewerUserId,
            hiddenFromViewerIds = hiddenFromViewerIds,
        )

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
}
