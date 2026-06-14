package com.pynapli.server.swipe

import com.pynapli.server.discovery.DiscoveryFilters
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

        if (!filters.isRankCacheEligible() || !deckRankCache.isEnabled()) {
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
