package com.pynapli.server.grid

import com.pynapli.server.discovery.DiscoveryFilters
import java.util.UUID

interface GridRepository {
    suspend fun queryNearby(
        viewerUserId: UUID,
        radiusMeters: Int,
        limit: Int,
        filters: DiscoveryFilters = DiscoveryFilters(),
        cursor: GridCursorAnchor? = null,
    ): GridQueryResult
}

interface GridViewerTrustRepository {
    suspend fun isAgeVerified(userId: UUID): Boolean
}

data class GridQueryResult(
    val viewerHadLocation: Boolean,
    val cards: List<GridCardRecord>,
    val nextCursorAnchor: GridCursorAnchor? = null,
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
    val linkedPartnerThumbnailMediaAssetId: UUID? = null,
    /**
     * Target has an active travel_locations row. Surfaced as a "Visiting" pill
     * on the Grid card (SWIPE_IMPLEMENTATION_SPEC L348) so the viewer knows
     * the person is browsing their area in passport mode, not local.
     */
    val visiting: Boolean = false,
)

data class GridCursorAnchor(
    val boostBucket: Int,
    val distanceBucketMeters: Int,
    val userId: UUID,
    val seenCount: Int = 0,
)

fun GridCardRecord.toCursorAnchor(): GridCursorAnchor =
    GridCursorAnchor(
        boostBucket = boostBucket,
        distanceBucketMeters = distanceBucketMeters,
        userId = userId,
    )
