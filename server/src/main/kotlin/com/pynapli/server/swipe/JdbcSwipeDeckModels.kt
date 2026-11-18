package com.pynapli.server.swipe

import java.time.Instant
import java.util.UUID

internal data class DeckRankRow(
    val userId: UUID,
    val displayName: String,
    val displayAge: Int?,
    val bio: String?,
    val tags: List<String>,
    val verified: Boolean,
    val visiting: Boolean,
    val showDistance: Boolean,
    val distanceMeters: Double,
    val isBoosted: Boolean,
    val softScore: Int,
    /** L-08 coarse ordering key; see `pynapli_order_bucket_m` (V95). */
    val orderBucket: Int = 0,
    val orderTiebreak: Long = 0,
    /** 1-based place in the deck's natural order, before any Boost lift. */
    val naturalRank: Long = 0,
)

internal data class DeckHydration(
    val thumbnailMediaAssetId: UUID?,
    val thumbnailMediaAssetIds: List<UUID>,
    val linkedPartnerUserId: UUID?,
    val linkedPartnerDisplayName: String?,
    val linkedPartnerThumbnailMediaAssetId: UUID?,
    /** The visible linked partner is photo-verified (false without a visible partner). */
    val linkedPartnerVerified: Boolean = false,
)

/** The viewer's location cell (CoarseLocation.cellKey): hundredths of a degree. */
internal data class DeckLocationCacheKey(
    val cellLongitude: Int,
    val cellLatitude: Int,
)

internal data class DeckRankCacheKey(
    val location: DeckLocationCacheKey,
    val radiusMeters: Int,
    val filterKey: String,
    val window: Int,
)

internal data class CachedDeckRankRows(
    val rows: List<DeckRankRow>,
    val expiresAt: Instant,
)
