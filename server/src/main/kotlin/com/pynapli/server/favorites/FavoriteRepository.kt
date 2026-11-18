package com.pynapli.server.favorites

import com.pynapli.server.grid.GridHydration
import java.util.UUID

internal interface FavoriteRepository {
    /**
     * Stars [favoriteUserId] for [userId] while [userId] has fewer than [cap] favorites. Re-adding an
     * existing favorite always succeeds (idempotent), even at the cap.
     */
    suspend fun add(
        userId: UUID,
        favoriteUserId: UUID,
        cap: Int,
    ): FavoriteAddResult

    /** Idempotent: removing a favorite that isn't there is not an error. */
    suspend fun remove(
        userId: UUID,
        favoriteUserId: UUID,
    )

    suspend fun isFavorite(
        userId: UUID,
        favoriteUserId: UUID,
    ): Boolean

    /**
     * Every favorite of [viewerUserId] that the Grid would show them right now (live, visible,
     * Grid-visible, not incognito, not hidden while travelling, not banned/quarantined, no block either
     * way, not hidden by the viewer), with the coarse fields the Favorites order is built from.
     */
    suspend fun listVisible(
        viewerUserId: UUID,
        limit: Int,
    ): List<FavoriteRankRow>

    suspend fun hydrate(
        viewerUserId: UUID,
        userIds: List<UUID>,
    ): Map<UUID, GridHydration>
}

enum class FavoriteAddResult {
    ADDED,
    ALREADY_FAVORITE,
    LIMIT_REACHED,
    TARGET_NOT_FOUND,
}

/**
 * One favorite with only coarse location data. [distanceMeters] is the cell-to-cell distance between
 * both effective locations (null when either side has no location); [orderBucket] is the public
 * distance bucket for someone who shows distance, and a bucket that does not depend on where they are
 * at all for someone who hides it (or has no location), so the list order says nothing finer than the
 * distance the card shows.
 */
data class FavoriteRankRow(
    val userId: UUID,
    val displayName: String,
    val showDistance: Boolean,
    val distanceMeters: Double?,
    val orderBucket: Int,
    val orderTiebreak: Long,
    val visiting: Boolean,
)
