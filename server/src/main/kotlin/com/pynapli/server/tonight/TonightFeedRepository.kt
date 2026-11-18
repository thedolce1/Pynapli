package com.pynapli.server.tonight

import com.pynapli.server.discovery.DiscoveryFilters
import java.time.Instant
import java.util.UUID

/**
 * Read-only storage for the public Playing Tonight feed (PT-3). Anchored on the
 * session OWNER participant; the discovery query mirrors the Grid's `ST_DWithin`/
 * `ST_Distance` candidate query against `user_locations.geom` (zone-only — precise
 * coordinates never leave this layer). The service applies block/hidden/online/
 * thumbnail resolution and the zone bucketing on top of these rows.
 */
interface TonightFeedRepository {
    /**
     * Live sessions near [viewerUserId]: each row is one active, non-expired,
     * map-visible session whose OWNER passes the same discovery eligibility gates
     * as the Grid and is within [radiusMeters]. [intent] (when non-null) narrows to
     * matching `tonight_sessions.intent`. Ordered by a coarse km bucket (distance-independent
     * when the owner hides distance), a stable pseudo-random tiebreak, then session id;
     * capped at [limit]. The caller must have already verified the viewer has
     * a location (an empty result here can also mean "no eligible sessions").
     */
    suspend fun feedNear(
        viewerUserId: UUID,
        radiusMeters: Int,
        intent: String?,
        limit: Int,
        now: Instant,
        filters: DiscoveryFilters = DiscoveryFilters(),
    ): List<TonightFeedRow>
}

/**
 * Server-internal feed row. Carries the OWNER identity, the cell-to-cell distance and the
 * coarse zone indices (0.02-degree grid) of the owner's EFFECTIVE location, plus the linked
 * partner (for `mode='together'`) as raw ids the service resolves. Precise coordinates are
 * NEVER read; the zone leaves the service only when the owner shows distance.
 */
data class TonightFeedRow(
    val sessionId: UUID,
    val mode: String,
    val intent: String,
    val hostMode: String,
    val note: String?,
    val photoMediaAssetId: UUID?,
    val ownerUserId: UUID,
    val ownerDisplayName: String,
    val ownerShowDistance: Boolean,
    val ownerDistanceMeters: Double,
    val ownerThumbnailMediaAssetId: UUID?,
    val ownerZoneX: Int,
    val ownerZoneY: Int,
    val partnerUserId: UUID?,
    val partnerDisplayName: String?,
    val partnerThumbnailMediaAssetId: UUID?,
)
