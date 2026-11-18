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

    /** People whose announced planned trip reaches the viewer's area within two weeks, soonest first. */
    suspend fun queryVisitingSoon(
        viewerUserId: UUID,
        radiusMeters: Int,
        limit: Int,
    ): GridQueryResult = GridQueryResult(viewerHadLocation = true, cards = emptyList())
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
    /**
     * Ordering bucket (metres, L-08): whole-km coarse distance for people who show distance, a
     * distance-independent pseudo-random bucket for people who hide it. Never a displayed value.
     */
    val orderBucket: Int = distanceMeters.toInt(),
    /** Stable per-viewer-cell pseudo-random tiebreak inside an [orderBucket]. */
    val orderTiebreak: Long = 0,
    val linkedPartnerUserId: UUID? = null,
    val linkedPartnerDisplayName: String? = null,
    val linkedPartnerThumbnailMediaAssetId: UUID? = null,
    /**
     * Target has an active travel_locations row. Surfaced as a "Visiting" pill
     * on the Grid card (SWIPE_IMPLEMENTATION_SPEC L348) so the viewer knows
     * the person is browsing their area in passport mode, not local.
     */
    val visiting: Boolean = false,
    // An announced planned trip bringing this person to the viewer's area ("Visiting soon").
    val visitingFrom: java.time.LocalDate? = null,
    val visitingUntil: java.time.LocalDate? = null,
    val verified: Boolean = false,
    val coupleVerified: Boolean = false,
    /**
     * Individual has a live Playing Tonight session (PLAYING_TONIGHT_SPEC). Drives a
     * subtle per-tile indicator; hydrated per page (not part of the shared rank cache)
     * so it stays fresh as sessions start/expire.
     */
    val tonightActive: Boolean = false,
)

/** Keyset position: (boostBucket, orderBucket, orderTiebreak, userId). Travels only inside a sealed cursor. */
data class GridCursorAnchor(
    val boostBucket: Int,
    val orderBucket: Int,
    val userId: UUID,
    val seenCount: Int = 0,
    val orderTiebreak: Long = 0,
)

fun GridCardRecord.toCursorAnchor(): GridCursorAnchor =
    GridCursorAnchor(
        boostBucket = boostBucket,
        orderBucket = orderBucket,
        userId = userId,
        orderTiebreak = orderTiebreak,
    )
