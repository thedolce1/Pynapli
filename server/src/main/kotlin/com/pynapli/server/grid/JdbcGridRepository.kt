package com.pynapli.server.grid

import com.pynapli.server.discovery.DiscoveryFilters
import com.pynapli.server.profiling.profileBlocking
import com.pynapli.server.profiling.profileCount
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.Connection
import java.time.Duration
import java.util.UUID
import javax.sql.DataSource

internal class JdbcGridRepository(
    private val dataSource: DataSource,
    rankCacheTtl: Duration = Duration.ofSeconds(5),
    rankCacheMaxEntries: Int = 128,
    private val rankCacheWindow: Int = 128,
    // Defaults to a per-instance in-memory cache (tests, single-instance). Production
    // injects RedisGridCandidateRankCache via ServerDependencies for cross-instance
    // sharing. The cached window is exclusion-free either way, so swapping the impl
    // never changes correctness — only how widely the window is shared.
    private val candidateRankCache: GridCandidateRankCache =
        InMemoryGridCandidateRankCache(
            ttl = rankCacheTtl,
            maxEntries = rankCacheMaxEntries,
        ),
) : GridRepository {
    override suspend fun queryNearby(
        viewerUserId: UUID,
        radiusMeters: Int,
        limit: Int,
        filters: DiscoveryFilters,
        cursor: GridCursorAnchor?,
        hiddenFromViewerIds: Set<UUID>,
    ): GridQueryResult =
        withContext(Dispatchers.IO) {
            profileBlocking("grid.db.connectionWait") { dataSource.connection }.use { connection ->
                val viewerLocationKey =
                    profileBlocking("grid.db.viewerLocation") {
                        connection.findGridViewerLocationCacheKey(viewerUserId)
                    }

                if (viewerLocationKey == null) {
                    return@withContext GridQueryResult(viewerHadLocation = false, cards = emptyList())
                }

                // Phase 1 (cacheable): rank an exclusion-free candidate window.
                // Phase 2 (cheap): apply cursor slice + self-exclusion in Kotlin,
                // then hydrate ONLY the resulting page user ids.
                val rankedRows =
                    profileBlocking("grid.db.candidateQuery") {
                        connection.queryGridRankCached(
                            viewerLocationKey = viewerLocationKey,
                            viewerUserId = viewerUserId,
                            radiusMeters = radiusMeters,
                            limit = limit,
                            filters = filters,
                            cursor = cursor,
                            hiddenFromViewerIds = hiddenFromViewerIds,
                        )
                    }

                // G2-2 ticket 5: a page served from the shared window re-reads owner visibility now
                // (incognito, grid_visible, status, profile visibility, travel visibility, risk tier), so
                // nothing the window baked in outlives a change by up to its TTL. The next cursor still
                // anchors on the last RANKED row, so a dropped row never skips the rows after it.
                val pageRows =
                    if (rankedRows.fromSharedWindow && rankedRows.cards.isNotEmpty()) {
                        val visible =
                            profileBlocking("grid.db.ownerRecheck") {
                                connection.stillDiscoverableOnGrid(rankedRows.cards.map { it.userId })
                            }
                        rankedRows.cards.filter { it.userId in visible }
                    } else {
                        rankedRows.cards
                    }
                val rankedAnchor = rankedRows.cards.lastOrNull()?.takeIf { rankedRows.hasMore }?.toCursorAnchor()
                if (pageRows.isEmpty()) {
                    return@withContext GridQueryResult(viewerHadLocation = true, cards = emptyList(), nextCursorAnchor = rankedAnchor)
                }

                val hydration =
                    profileBlocking("grid.db.hydrate") {
                        connection.hydrateGridCards(
                            viewerUserId = viewerUserId,
                            userIds = pageRows.map { it.userId },
                        )
                    }

                val cards =
                    pageRows.map { row ->
                        val h = hydration[row.userId]
                        GridCardRecord(
                            userId = row.userId,
                            displayName = row.displayName,
                            thumbnailMediaAssetId = h?.thumbnailMediaAssetId,
                            showDistance = row.showDistance,
                            distanceMeters = row.distanceMeters,
                            boostBucket = row.boostBucket,
                            orderBucket = row.orderBucket,
                            orderTiebreak = row.orderTiebreak,
                            linkedPartnerUserId = h?.linkedPartnerUserId,
                            linkedPartnerDisplayName = h?.linkedPartnerDisplayName,
                            linkedPartnerThumbnailMediaAssetId = h?.linkedPartnerThumbnailMediaAssetId,
                            visiting = row.visiting,
                            tonightActive = h?.tonightActive ?: false,
                            verified = h?.verified ?: false,
                            coupleVerified = h?.coupleVerified ?: false,
                            fresh = h?.fresh ?: false,
                            lastActiveAt = h?.lastActiveAt,
                            activityHidden = h?.activityHidden ?: true,
                        )
                    }

                GridQueryResult(
                    viewerHadLocation = true,
                    cards = cards,
                    nextCursorAnchor = rankedAnchor,
                )
            }
        }

    override suspend fun queryVisitingSoon(
        viewerUserId: UUID,
        radiusMeters: Int,
        limit: Int,
        filters: DiscoveryFilters,
        cursor: GridCursorAnchor?,
        hiddenFromViewerIds: Set<UUID>,
    ): GridQueryResult =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                val viewerLocationKey =
                    connection.findGridViewerLocationCacheKey(viewerUserId)
                        ?: return@withContext GridQueryResult(viewerHadLocation = false, cards = emptyList())
                // One extra row says whether a next page exists. Per viewer, never cached.
                val ranked =
                    connection.queryVisitingSoonRank(
                        viewerUserId = viewerUserId,
                        radiusMeters = radiusMeters,
                        limit = limit + 1,
                        filters = filters,
                        orderSeed = viewerLocationKey.orderSeed,
                        hiddenFromViewerIds = hiddenFromViewerIds,
                        cursor = cursor,
                    )
                val rows = ranked.take(limit)
                if (rows.isEmpty()) return@withContext GridQueryResult(viewerHadLocation = true, cards = emptyList())
                val hydration = connection.hydrateGridCards(viewerUserId = viewerUserId, userIds = rows.map { it.userId })
                val cards =
                    rows.map { row ->
                        val h = hydration[row.userId]
                        GridCardRecord(
                            userId = row.userId,
                            displayName = row.displayName,
                            thumbnailMediaAssetId = h?.thumbnailMediaAssetId,
                            // They aren't here yet: no distance to show.
                            showDistance = false,
                            distanceMeters = 0.0,
                            // Keyset position (arrival day, order bucket, id): see queryVisitingSoonRank.
                            boostBucket = row.arrivalDay,
                            orderBucket = row.orderBucket,
                            orderTiebreak = 0,
                            linkedPartnerUserId = h?.linkedPartnerUserId,
                            linkedPartnerDisplayName = h?.linkedPartnerDisplayName,
                            linkedPartnerThumbnailMediaAssetId = h?.linkedPartnerThumbnailMediaAssetId,
                            visitingFrom = row.startsOn,
                            visitingUntil = row.endsOn,
                            tonightActive = h?.tonightActive ?: false,
                            verified = h?.verified ?: false,
                            coupleVerified = h?.coupleVerified ?: false,
                            fresh = h?.fresh ?: false,
                            lastActiveAt = h?.lastActiveAt,
                            activityHidden = h?.activityHidden ?: true,
                        )
                    }
                GridQueryResult(
                    viewerHadLocation = true,
                    cards = cards,
                    nextCursorAnchor = if (ranked.size > limit) cards.last().toCursorAnchor() else null,
                )
            }
        }

    /**
     * Returns a [GridRankPage]: the keyset-sliced page of [limit] ranked rows plus
     * whether more rows follow (drives the next cursor). Mirrors Swipe's
     * `queryDeckRankCached`: bypass the shared cache for linkedOnly, disabled
     * cache, or travel-active viewers; otherwise load+cache an exclusion-free
     * window and apply cursor slice + self-exclusion in Kotlin, falling back to
     * the uncached path when the window can't settle the page and whether more
     * rows follow it.
     */
    private fun Connection.queryGridRankCached(
        viewerLocationKey: GridLocationCacheKey,
        viewerUserId: UUID,
        radiusMeters: Int,
        limit: Int,
        filters: DiscoveryFilters,
        cursor: GridCursorAnchor?,
        hiddenFromViewerIds: Set<UUID>,
    ): GridRankPage {
        val viewerTravelActive = viewerHasActiveTravelLocation(viewerUserId)

        if (
            !filters.isGridRankCacheEligible() ||
            !candidateRankCache.isEnabled() ||
            viewerTravelActive
        ) {
            profileCount("grid.rank.uncached")
            return queryGridRankUncached(viewerUserId, viewerLocationKey, radiusMeters, limit, filters, cursor, hiddenFromViewerIds)
        }

        val cacheWindow = rankCacheWindow.coerceAtLeast(limit)
        val cacheKey =
            GridRankCacheKey(
                location = viewerLocationKey,
                radiusMeters = radiusMeters,
                filterKey = filters.gridRankCacheKey(),
                window = cacheWindow,
            )
        // G2-2 ticket 7: hit vs miss, read off the profiled request (X-Pynapli-Profile-Counts).
        var rankedNow = false
        val cachedRows =
            candidateRankCache.load(cacheKey) {
                rankedNow = true
                queryGridRank(
                    viewerUserId = viewerUserId,
                    radiusMeters = radiusMeters,
                    limit = cacheWindow,
                    filters = filters,
                    // EXCLUSION-FREE window: no self filter, no per-viewer block.
                    userSpecificViewerId = null,
                    orderSeed = viewerLocationKey.orderSeed,
                )
            }
        profileCount(if (rankedNow) "grid.rank.cacheMiss" else "grid.rank.cacheHit")

        // Cursor slice + self-exclusion in Kotlin over the shared window. Block +
        // hidden are applied post-repository in GridService; self is excluded here
        // because the cached window deliberately keeps the viewer in.
        val sliced =
            cachedRows
                .asSequence()
                .filter { cursor == null || it.afterCursor(cursor) }
                .filterNot { it.userId == viewerUserId }
                .toList()
        // Serve from the window only when it can answer both the page AND whether
        // more rows follow: either a row of the window remains past this page, or
        // the window holds everyone in range (it came back short). A full window
        // whose remaining rows fill the page exactly (or not at all) cannot tell
        // whether more rows lie beyond it, so rank this page for the viewer
        // directly. Mirrors Swipe's `windowRows < cacheWindow` guard.
        if (sliced.size > limit || cachedRows.size < cacheWindow) {
            return GridRankPage(cards = sliced.take(limit), hasMore = sliced.size > limit, fromSharedWindow = true)
        }

        // The window could not settle this page: a second rank pass, ranked for this viewer.
        profileCount("grid.rank.windowFallback")
        return queryGridRankUncached(viewerUserId, viewerLocationKey, radiusMeters, limit, filters, cursor, hiddenFromViewerIds)
    }

    /**
     * Uncached path: rank one page directly with per-viewer self/block AND the
     * keyset cursor predicate applied in SQL (matching the original single query),
     * over a [limit]+1 fetch. Used for linkedOnly, disabled cache, travel-active
     * viewers, and as the window-exhausted fallback. Preserves the original
     * query's semantics exactly.
     */
    private fun Connection.queryGridRankUncached(
        viewerUserId: UUID,
        viewerLocationKey: GridLocationCacheKey,
        radiusMeters: Int,
        limit: Int,
        filters: DiscoveryFilters,
        cursor: GridCursorAnchor?,
        hiddenFromViewerIds: Set<UUID>,
    ): GridRankPage {
        // The keyset cursor predicate is pushed into the SQL here (matching the
        // original single query), so a `limit + 1` fetch is sufficient: the extra
        // row signals a following page. Self/block exclusion is applied in SQL via
        // userSpecificViewerId.
        val ranked =
            queryGridRank(
                viewerUserId = viewerUserId,
                radiusMeters = radiusMeters,
                limit = limit + 1,
                filters = filters,
                userSpecificViewerId = viewerUserId,
                orderSeed = viewerLocationKey.orderSeed,
                cursor = cursor,
                hiddenFromViewerIds = hiddenFromViewerIds,
            )
        val page = ranked.take(limit)
        val hasMore = ranked.size > limit
        return GridRankPage(cards = page, hasMore = hasMore)
    }
}

private data class GridRankPage(
    val cards: List<GridRankRow>,
    val hasMore: Boolean,
    // Served from the shared, cross-viewer window (owner visibility as of the window's ranking).
    val fromSharedWindow: Boolean = false,
)

private fun GridRankRow.toCursorAnchor(): GridCursorAnchor =
    GridCursorAnchor(
        boostBucket = boostBucket,
        orderBucket = orderBucket,
        userId = userId,
        orderTiebreak = orderTiebreak,
    )

/** Keyset comparison matching the SQL ORDER BY (boost_bucket, order bucket, order_tiebreak, user_id ASC). */
private fun GridRankRow.afterCursor(cursor: GridCursorAnchor): Boolean =
    when {
        boostBucket != cursor.boostBucket -> boostBucket > cursor.boostBucket
        orderBucket != cursor.orderBucket -> orderBucket > cursor.orderBucket
        orderTiebreak != cursor.orderTiebreak -> orderTiebreak > cursor.orderTiebreak
        // Postgres orders uuid as 16 unsigned bytes (big-endian). Java's
        // UUID.compareTo treats the halves as signed longs, which disagrees once
        // the high bit is set — so we replicate Postgres' unsigned ordering to
        // keep the Kotlin keyset slice byte-identical to the SQL `user_id > ?`.
        else -> userId.compareUnsigned(cursor.userId) > 0
    }

private fun UUID.compareUnsigned(other: UUID): Int {
    val hi = java.lang.Long.compareUnsigned(mostSignificantBits, other.mostSignificantBits)
    if (hi != 0) return hi
    return java.lang.Long.compareUnsigned(leastSignificantBits, other.leastSignificantBits)
}
