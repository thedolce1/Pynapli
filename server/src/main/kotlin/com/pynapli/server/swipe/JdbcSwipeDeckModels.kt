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
)

internal data class DeckHydration(
    val thumbnailMediaAssetId: UUID?,
    val thumbnailMediaAssetIds: List<UUID>,
    val linkedPartnerUserId: UUID?,
    val linkedPartnerDisplayName: String?,
    val linkedPartnerThumbnailMediaAssetId: UUID?,
)

internal data class DeckLocationCacheKey(
    val longitudeE3: Int,
    val latitudeE3: Int,
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
