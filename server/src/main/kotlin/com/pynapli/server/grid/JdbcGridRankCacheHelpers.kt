package com.pynapli.server.grid

import com.pynapli.server.discovery.DiscoveryFilters
import com.pynapli.server.distance.CoarseLocation
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
 * The viewer's location cell, derived from their effective discovery location: an active
 * travel_locations row overrides the real GPS point (passport mode), matching the `viewer_location`
 * CTE of the rank query. The cell is snapped IN SQL with the same `pynapli_location_cell` the rank
 * query ranks from, so two viewers share a cache key exactly when they share a rank origin (L-14).
 *
 * Returns null when the viewer has no location row at all.
 */
internal fun Connection.findGridViewerLocationCacheKey(viewerUserId: UUID): GridLocationCacheKey? =
    prepareStatement(
        """
        SELECT ST_X(pynapli_location_cell(geom)::geometry) AS longitude,
               ST_Y(pynapli_location_cell(geom)::geometry) AS latitude
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
                val (longitudeE2, latitudeE2) =
                    CoarseLocation.cellKey(snappedLongitude = rs.getDouble("longitude"), snappedLatitude = rs.getDouble("latitude"))
                GridLocationCacheKey(longitudeE2 = longitudeE2, latitudeE2 = latitudeE2)
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
 * Grid is cache-eligible unless `linkedOnly` or `verifiedCouplesOnly` is set. Mirrors Swipe's
 * `DiscoveryFilters.isRankCacheEligible()`: both bake per-viewer state (blocks; for verified
 * couples also the owner's partner visibility toward this viewer and the viewer's hidden list)
 * into the linked-partner test, so the window is not shareable cross-viewer. The partner filters
 * and "No single men" (PartnerFilterSql) run the same per-viewer partner gate as linkedOnly, so
 * they never share a window either (plan G2-3). "Haven't chatted" tests this viewer's own messages, so
 * it is per viewer too (plan G2-4).
 */
internal fun DiscoveryFilters.isGridRankCacheEligible(): Boolean =
    linkedOnly == null && !verifiedCouplesOnly && !hasPartnerFilters && !excludeSingleMen && !haventChattedOnly

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
        append(boundaryItems.sorted().joinToString("|"))
        append(';')
        append(logisticsTags.sorted().joinToString("|"))
        append(';')
        append(tonightAvailableOnly)
        append(';')
        append(activeTodayOnly)
        append(';')
        append(freshOnly)
        append(';')
        append(travelingHereOnly)
        append(';')
        append(withPhotosOnly)
        append(';')
        append(hasPrivateAlbumOnly)
        append(';')
        append(intents.sorted().joinToString("|"))
        append(';')
        append(genders.sorted().joinToString("|"))
        append(';')
        append(orientations.sorted().joinToString("|"))
        append(';')
        // Never cached (see isGridRankCacheEligible); keyed anyway so a future eligibility change
        // cannot let filtered and unfiltered viewers share a window.
        append(verifiedCouplesOnly)
        // Never cached either; keyed for the same reason. Empty when unset.
        append(partnerScopeKey)
        // Never cached either (per viewer); appended only when on.
        if (haventChattedOnly) append(";haventChatted")
        // Any-of and all-of rank different sets: never one window. Appended only when on.
        if (lifestyleMatchAll) append(";lifestyleAllOf")
    }
