package com.pynapli.server.grid

import com.pynapli.server.discovery.DiscoveryFilters
import com.pynapli.server.profiling.profileBlocking
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
                        )
                    }

                val pageRows = rankedRows.cards
                if (pageRows.isEmpty()) {
                    return@withContext GridQueryResult(viewerHadLocation = true, cards = emptyList())
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
                            distanceBucketMeters = row.distanceBucketMeters,
                            linkedPartnerUserId = h?.linkedPartnerUserId,
                            linkedPartnerDisplayName = h?.linkedPartnerDisplayName,
                            linkedPartnerThumbnailMediaAssetId = h?.linkedPartnerThumbnailMediaAssetId,
                            visiting = row.visiting,
                            tonightActive = h?.tonightActive ?: false,
                        )
                    }

                GridQueryResult(
                    viewerHadLocation = true,
                    cards = cards,
                    nextCursorAnchor = if (rankedRows.hasMore && cards.isNotEmpty()) cards.last().toCursorAnchor() else null,
                )
            }
        }

    override suspend fun queryVisitingSoon(
        viewerUserId: UUID,
        radiusMeters: Int,
        limit: Int,
    ): GridQueryResult =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                val rows =
                    connection.prepareStatement(VISITING_SOON_QUERY).use { statement ->
                        statement.setObject(1, viewerUserId)
                        statement.setInt(2, radiusMeters)
                        statement.setObject(3, viewerUserId)
                        statement.setInt(4, limit)
                        statement.executeQuery().use { rs ->
                            buildList {
                                while (rs.next()) {
                                    add(
                                        Triple(
                                            rs.getObject("user_id", UUID::class.java) to rs.getString("display_name"),
                                            rs.getDate("starts_on").toLocalDate(),
                                            rs.getDate("ends_on").toLocalDate(),
                                        ),
                                    )
                                }
                            }
                        }
                    }
                val located = connection.findGridViewerLocationCacheKey(viewerUserId) != null
                if (rows.isEmpty()) return@withContext GridQueryResult(viewerHadLocation = located, cards = emptyList())
                val hydration = connection.hydrateGridCards(viewerUserId = viewerUserId, userIds = rows.map { it.first.first })
                GridQueryResult(
                    viewerHadLocation = true,
                    cards =
                        rows.map { (who, from, until) ->
                            val h = hydration[who.first]
                            GridCardRecord(
                                userId = who.first,
                                displayName = who.second,
                                thumbnailMediaAssetId = h?.thumbnailMediaAssetId,
                                // They aren't here yet: no distance to show.
                                showDistance = false,
                                distanceMeters = 0.0,
                                linkedPartnerUserId = h?.linkedPartnerUserId,
                                linkedPartnerDisplayName = h?.linkedPartnerDisplayName,
                                linkedPartnerThumbnailMediaAssetId = h?.linkedPartnerThumbnailMediaAssetId,
                                visitingFrom = from,
                                visitingUntil = until,
                            )
                        },
                )
            }
        }

    /**
     * Returns a [GridRankPage]: the keyset-sliced page of [limit] ranked rows plus
     * whether more rows follow (drives the next cursor). Mirrors Swipe's
     * `queryDeckRankCached`: bypass the shared cache for linkedOnly, disabled
     * cache, or travel-active viewers; otherwise load+cache an exclusion-free
     * window and apply cursor slice + self-exclusion in Kotlin, falling back to
     * the uncached path when the page can't be filled from the window.
     */
    private fun Connection.queryGridRankCached(
        viewerLocationKey: GridLocationCacheKey,
        viewerUserId: UUID,
        radiusMeters: Int,
        limit: Int,
        filters: DiscoveryFilters,
        cursor: GridCursorAnchor?,
    ): GridRankPage {
        val viewerTravelActive = viewerHasActiveTravelLocation(viewerUserId)

        if (
            !filters.isGridRankCacheEligible() ||
            !candidateRankCache.isEnabled() ||
            viewerTravelActive
        ) {
            return queryGridRankUncached(viewerUserId, radiusMeters, limit, filters, cursor)
        }

        val cacheWindow = rankCacheWindow.coerceAtLeast(limit)
        val cacheKey =
            GridRankCacheKey(
                location = viewerLocationKey,
                radiusMeters = radiusMeters,
                filterKey = filters.gridRankCacheKey(),
                window = cacheWindow,
            )
        val cachedRows =
            candidateRankCache.load(cacheKey) {
                queryGridRank(
                    viewerUserId = viewerUserId,
                    radiusMeters = radiusMeters,
                    limit = cacheWindow,
                    filters = filters,
                    // EXCLUSION-FREE window: no self filter, no per-viewer block.
                    userSpecificViewerId = null,
                )
            }

        // Cursor slice + self-exclusion in Kotlin over the shared window. Block +
        // hidden are applied post-repository in GridService; self is excluded here
        // because the cached window deliberately keeps the viewer in.
        val sliced =
            cachedRows
                .asSequence()
                .filter { cursor == null || it.afterCursor(cursor) }
                .filterNot { it.userId == viewerUserId }
                .toList()
        val page = sliced.take(limit)
        val hasMore = sliced.size > limit

        // Fall back to the uncached path only when the window could not fill the
        // page AND the window was full (so more matching rows may exist in the DB
        // beyond the window). Mirrors Swipe's `cachedRows.size < cacheWindow` guard.
        if (page.size >= limit || cachedRows.size < cacheWindow) {
            return GridRankPage(cards = page, hasMore = hasMore)
        }

        return queryGridRankUncached(viewerUserId, radiusMeters, limit, filters, cursor)
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
        radiusMeters: Int,
        limit: Int,
        filters: DiscoveryFilters,
        cursor: GridCursorAnchor?,
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
                cursor = cursor,
            )
        val page = ranked.take(limit)
        val hasMore = ranked.size > limit
        return GridRankPage(cards = page, hasMore = hasMore)
    }
}

private data class GridRankPage(
    val cards: List<GridRankRow>,
    val hasMore: Boolean,
)

/** Keyset comparison matching the SQL ORDER BY (boost_bucket, distance_bucket_meters, user_id ASC). */
private fun GridRankRow.afterCursor(cursor: GridCursorAnchor): Boolean =
    when {
        boostBucket != cursor.boostBucket -> boostBucket > cursor.boostBucket
        distanceBucketMeters != cursor.distanceBucketMeters -> distanceBucketMeters > cursor.distanceBucketMeters
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

/**
 * Announced planned trips (opt-in per trip) whose destination is within the radius of where the
 * viewer is browsing and which start in the next 14 days. Same visibility rules as the Grid: a live,
 * visible, non-incognito, Grid-visible account that isn't quarantined. Blocks and hides are removed
 * afterwards by GridService, exactly as for nearby results.
 */
private val VISITING_SOON_QUERY =
    """
    WITH viewer_location AS (
        SELECT COALESCE(tl.geom, ul.geom) AS geom
        FROM user_locations ul
        LEFT JOIN travel_locations tl
          ON tl.user_id = ul.user_id
         AND tl.status = 'active'
         AND tl.active_until IS NULL
        WHERE ul.user_id = ?
    )
    SELECT DISTINCT ON (u.id)
           u.id AS user_id,
           COALESCE(NULLIF(p.display_name, ''), 'Pynapli User') AS display_name,
           t.starts_on,
           t.ends_on
    FROM viewer_location v
    JOIN travel_trips t
      ON t.status = 'planned'
     AND t.announce_to_locals = true
     AND t.ends_on >= current_date
     AND t.starts_on <= current_date + 14
     AND ST_DWithin(v.geom, ST_SetSRID(ST_MakePoint(t.longitude, t.latitude), 4326)::geography, ?)
    JOIN users u ON u.id = t.user_id AND u.status = 'active' AND u.deleted_at IS NULL
    JOIN profiles p ON p.user_id = u.id AND p.visibility_status = 'visible'
    JOIN discovery_settings ds ON ds.user_id = u.id AND ds.grid_visible = true AND ds.incognito = false
    LEFT JOIN account_risk_states ars ON ars.user_id = u.id
    WHERE u.id <> ?
      AND COALESCE(ars.tier, 'clean') NOT IN ('quarantine', 'terminate')
    ORDER BY u.id, t.starts_on
    LIMIT ?
    """.trimIndent()
