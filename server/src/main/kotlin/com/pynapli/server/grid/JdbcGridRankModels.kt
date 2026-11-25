package com.pynapli.server.grid

import java.time.Instant
import java.util.UUID

/**
 * Exclusion-free identity + ranking/cursor row produced by the cacheable Grid
 * rank phase. Mirrors Swipe's `DeckRankRow`: it carries ONLY the cheap ranking
 * and keyset-cursor columns the page hydration needs, never per-viewer block /
 * hide / self state. The cached window is shared cross-viewer, so anything
 * viewer-specific is applied AFTER the cache (see [JdbcGridRepository]).
 */
internal data class GridRankRow(
    val userId: UUID,
    val displayName: String,
    val showDistance: Boolean,
    val boostBucket: Int,
    val orderBucket: Int,
    val distanceMeters: Double,
    val orderTiebreak: Long = 0,
    val visiting: Boolean,
)

/**
 * Hydration payload for the small page of user ids that survive cursor slicing.
 * Mirrors Swipe's `DeckHydration`, scoped to the fields a Grid card needs.
 */
internal data class GridHydration(
    val thumbnailMediaAssetId: UUID?,
    val linkedPartnerUserId: UUID?,
    val linkedPartnerDisplayName: String?,
    val linkedPartnerThumbnailMediaAssetId: UUID?,
    val tonightActive: Boolean = false,
    // The card owner is photo-verified (profiles.verified_at).
    val verified: Boolean = false,
    // The linked partner shown in the mini is photo-verified too.
    val linkedPartnerVerified: Boolean = false,
    // Plan G4 Fresh rule (GridActivitySql.fresh): drives the card's "New" marker.
    val fresh: Boolean = false,
    // Plan G11: last activity (GridActivitySql.lastActiveAt) for the server-side activeAgo bucket,
    // and whether the member hides it. Never serialized.
    val lastActiveAt: Instant? = null,
    val activityHidden: Boolean = false,
) {
    /** Couple verified: both linked partners verified, and the partner is visible to this viewer. */
    val coupleVerified: Boolean get() = verified && linkedPartnerUserId != null && linkedPartnerVerified
}

/**
 * The viewer's location CELL (effective location snapped to the 0.01-degree grid, in hundredths of a
 * degree). The rank window is ranked from this cell's centre, so every viewer sharing the key gets
 * byte-identical distances and order (L-14); it also seeds the ordering tiebreak ([orderSeed]).
 */
internal data class GridLocationCacheKey(
    val longitudeE2: Int,
    val latitudeE2: Int,
) {
    val orderSeed: String get() = "cell:${longitudeE2}_$latitudeE2"
}

/** Cache key for the ranked candidate window — copy of Swipe's `DeckRankCacheKey`. */
internal data class GridRankCacheKey(
    val location: GridLocationCacheKey,
    val radiusMeters: Int,
    val filterKey: String,
    val window: Int,
)

internal data class CachedGridRankRows(
    val rows: List<GridRankRow>,
    val expiresAt: Instant,
)
