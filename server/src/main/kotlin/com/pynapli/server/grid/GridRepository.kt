package com.pynapli.server.grid

import java.util.UUID

interface GridRepository {
    suspend fun queryNearby(
        viewerUserId: UUID,
        radiusMeters: Int,
        limit: Int
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
    val distanceMeters: Double
)
