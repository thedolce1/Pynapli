package com.pynapli.server.grid

import com.pynapli.server.discovery.DiscoveryFilters
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.Types
import java.util.UUID

/** Grid-local copy of Swipe's `setNullableUuid` to avoid cross-package coupling. */
internal fun PreparedStatement.setNullableUuid(
    index: Int,
    value: UUID?,
) {
    if (value == null) {
        setNull(index, Types.OTHER)
    } else {
        setObject(index, value)
    }
}

/**
 * Coarse location bucket for the viewer, derived from their effective discovery
 * location. Mirrors Swipe's `findViewerLocationCacheKey`: an active
 * travel_locations row overrides the real GPS point (passport mode), so the
 * COALESCE matches the `viewer_location` CTE used by the rank query. The point
 * is rounded to E3 (~111m) so nearby viewers share the expensive spatial rank
 * window while card distances stay close enough for the Grid's coarse labels.
 *
 * Returns null when the viewer has no location row at all.
 */
internal fun Connection.findGridViewerLocationCacheKey(viewerUserId: UUID): GridLocationCacheKey? =
    prepareStatement(
        """
        SELECT ST_X(geom::geometry) AS longitude,
               ST_Y(geom::geometry) AS latitude
        FROM (
            SELECT COALESCE(tl.geom, ul.geom) AS geom
            FROM user_locations ul
            LEFT JOIN travel_locations tl
              ON tl.user_id = ul.user_id
             AND tl.status = 'active'
             AND tl.active_until IS NULL
            WHERE ul.user_id = ?
        ) viewer_location
        WHERE geom IS NOT NULL
        """.trimIndent(),
    ).use { statement ->
        statement.setObject(1, viewerUserId)
        statement.executeQuery().use { rs ->
            if (!rs.next()) {
                null
            } else {
                GridLocationCacheKey(
                    longitudeE3 = Math.round(rs.getDouble("longitude") * 1_000.0).toInt(),
                    latitudeE3 = Math.round(rs.getDouble("latitude") * 1_000.0).toInt(),
                )
            }
        }
    }

/**
 * True when the viewer has an active travel_locations row (passport mode). The
 * viewer's effective discovery center is then their travel point, which makes
 * the coarse cache bucket viewer-specific — so we bypass the shared cache and
 * fall back to the uncached single-query path, mirroring how Swipe excludes
 * travel-active viewers from the cross-viewer cache.
 */
internal fun Connection.viewerHasActiveTravelLocation(viewerUserId: UUID): Boolean =
    prepareStatement(
        """
        SELECT EXISTS (
            SELECT 1
            FROM travel_locations
            WHERE user_id = ?
              AND status = 'active'
              AND active_until IS NULL
        )
        """.trimIndent(),
    ).use { statement ->
        statement.setObject(1, viewerUserId)
        statement.executeQuery().use { rs ->
            rs.next()
            rs.getBoolean(1)
        }
    }

/**
 * Grid is cache-eligible unless `linkedOnly` is set. Mirrors Swipe's
 * `DiscoveryFilters.isRankCacheEligible()`: linkedOnly bakes a per-viewer block
 * subquery into the linked-partner existence test, so it is not shareable
 * cross-viewer.
 */
internal fun DiscoveryFilters.isGridRankCacheEligible(): Boolean = linkedOnly == null

/**
 * Stable cache-class key for the FILTER properties that define the ranked
 * candidate set. Mirrors Swipe's `DiscoveryFilters.rankCacheKey()`. Only
 * filter-class properties belong here; per-viewer identity exclusions
 * (block / hide / self) are NOT part of the key and are applied after the cache.
 * Grid ignores `verifiedPreferred` (soft ranking is Swipe-only), so it is
 * intentionally excluded.
 */
internal fun DiscoveryFilters.gridRankCacheKey(): String =
    buildString {
        append(minimumAge ?: "")
        append(';')
        append(maximumAge ?: "")
        append(';')
        append(relationshipContexts.sorted().joinToString("|"))
        append(';')
        append(linkedOnly ?: "")
        append(';')
        append(verifiedOnly)
        append(';')
        append(facePhotoVerifiedOnly)
        append(';')
        append(ageVerifiedOnly)
        append(';')
        append(discoveryStyles.sorted().joinToString("|"))
        append(';')
        append(lifestyleTags.sorted().joinToString("|"))
        append(';')
        append(logisticsTags.sorted().joinToString("|"))
        append(';')
        append(tonightAvailableOnly)
        append(';')
        append(intents.sorted().joinToString("|"))
    }
