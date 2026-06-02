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
    val distanceBucketMeters: Int,
    val distanceMeters: Double,
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
)

/** Coarse location bucket (lat/lng rounded to E3 ~= 111m) — copy of Swipe's `DeckLocationCacheKey`. */
internal data class GridLocationCacheKey(
    val longitudeE3: Int,
    val latitudeE3: Int,
)

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
