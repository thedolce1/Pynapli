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
)

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
