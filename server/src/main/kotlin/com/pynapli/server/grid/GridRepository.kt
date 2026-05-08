package com.pynapli.server.grid

import com.pynapli.server.discovery.DiscoveryFilters
import java.util.UUID

interface GridRepository {
    suspend fun queryNearby(
        viewerUserId: UUID,
        radiusMeters: Int,
        limit: Int,
        filters: DiscoveryFilters = DiscoveryFilters(),
        cursor: GridCursorAnchor? = null
    ): GridQueryResult
}

data class GridQueryResult(
    val viewerHadLocation: Boolean,
    val cards: List<GridCardRecord>,
    val nextCursorAnchor: GridCursorAnchor? = null
)

data class GridCardRecord(
    val userId: UUID,
    val displayName: String,
    val thumbnailMediaAssetId: UUID? = null,
    val showDistance: Boolean,
    val distanceMeters: Double,
    val boostBucket: Int = 1,
    val distanceBucketMeters: Int = distanceMeters.toInt(),
    val linkedPartnerUserId: UUID? = null,
    val linkedPartnerDisplayName: String? = null,
    val linkedPartnerThumbnailMediaAssetId: UUID? = null
)

data class GridCursorAnchor(
    val boostBucket: Int,
    val distanceBucketMeters: Int,
    val userId: UUID
)

fun GridCardRecord.toCursorAnchor(): GridCursorAnchor =
    GridCursorAnchor(
        boostBucket = boostBucket,
        distanceBucketMeters = distanceBucketMeters,
        userId = userId
    )
