package com.pynapli.server.grid

import com.pynapli.server.discovery.DiscoveryFilters
import java.util.UUID

interface GridRepository {
    suspend fun queryNearby(
        viewerUserId: UUID,
        radiusMeters: Int,
        limit: Int,
        filters: DiscoveryFilters = DiscoveryFilters()
    ): GridQueryResult
}

data class GridQueryResult(
    val viewerHadLocation: Boolean,
    val cards: List<GridCardRecord>
)

data class GridCardRecord(
    val userId: UUID,
    val displayName: String,
    val thumbnailMediaAssetId: UUID? = null,
    val showDistance: Boolean,
    val distanceMeters: Double,
    val linkedPartnerUserId: UUID? = null,
    val linkedPartnerDisplayName: String? = null,
    val linkedPartnerThumbnailMediaAssetId: UUID? = null
)
