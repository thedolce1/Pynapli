package com.pynapli.server.favorites

import com.pynapli.server.discovery.DiscoveryCursorCodec
import com.pynapli.server.discovery.DiscoveryCursorException
import com.pynapli.server.grid.GridCardRecord
import com.pynapli.server.grid.GridCursorAnchor
import com.pynapli.server.grid.GridException
import com.pynapli.server.grid.toGridCardDto
import com.pynapli.server.media.MediaAssetUrlResolver
import com.pynapli.server.presence.PresenceService
import com.pynapli.server.safety.BlockRepository
import com.pynapli.server.safety.RateLimitService
import com.pynapli.server.safety.RateLimitedAction
import com.pynapli.shared.network.dto.FavoriteStatusDto
import com.pynapli.shared.network.dto.GridQueryResponseDto
import java.util.UUID

/**
 * Grid Favorites (GRID_IMPLEMENTATION_SPEC "Favorites"). A private star: adding or removing one never
 * creates activity, a notification or a push, and nothing the other person can read reveals it. The
 * list returns the same minimal Grid card, online first and then coarse distance, and drops anyone the
 * Grid would not show the viewer right now (blocked either way, hidden, incognito, banned, deleted).
 * Free for every tier, capped at [MAX_FAVORITES].
 */
class FavoriteService internal constructor(
    private val repository: FavoriteRepository,
    private val cursorCodec: DiscoveryCursorCodec,
    private val presenceService: PresenceService? = null,
    private val mediaAssetUrlResolver: MediaAssetUrlResolver? = null,
    private val blockRepository: BlockRepository? = null,
    private val rateLimitService: RateLimitService? = null,
) {
    suspend fun add(
        viewerUserId: UUID,
        targetUserIdText: String,
    ): FavoriteStatusDto {
        val target = parseTarget(viewerUserId, targetUserIdText)
        requireRateLimit(viewerUserId)
        // Same answer as for a missing account, so a star can't probe who blocked whom.
        if (blockRepository?.isBlockedEitherWay(viewerUserId, target) == true) throw GridException("PROFILE_NOT_FOUND")
        when (repository.add(viewerUserId, target, MAX_FAVORITES)) {
            FavoriteAddResult.ADDED, FavoriteAddResult.ALREADY_FAVORITE -> Unit
            FavoriteAddResult.LIMIT_REACHED -> throw GridException("FAVORITES_LIMIT_REACHED")
            FavoriteAddResult.TARGET_NOT_FOUND -> throw GridException("PROFILE_NOT_FOUND")
        }
        return FavoriteStatusDto(userId = target.toString(), favorite = true)
    }

    suspend fun remove(
        viewerUserId: UUID,
        targetUserIdText: String,
    ): FavoriteStatusDto {
        val target = parseTarget(viewerUserId, targetUserIdText)
        requireRateLimit(viewerUserId)
        repository.remove(viewerUserId, target)
        return FavoriteStatusDto(userId = target.toString(), favorite = false)
    }

    suspend fun isFavorite(
        viewerUserId: UUID,
        targetUserId: UUID,
    ): Boolean = viewerUserId != targetUserId && repository.isFavorite(viewerUserId, targetUserId)

    suspend fun list(
        viewerUserId: UUID,
        cursorText: String?,
        limitParam: Int?,
    ): GridQueryResponseDto {
        val limit = (limitParam ?: DEFAULT_PAGE_SIZE).coerceIn(MIN_PAGE_SIZE, MAX_PAGE_SIZE)
        val cursor =
            cursorText?.takeIf { it.isNotBlank() }?.let { token ->
                try {
                    cursorCodec.decodeFavoritesCursor(token, viewerUserId)
                } catch (_: DiscoveryCursorException) {
                    throw GridException("INVALID_GRID_CURSOR")
                }
            }
        val rows = repository.listVisible(viewerUserId, MAX_FAVORITES)
        if (rows.isEmpty()) return GridQueryResponseDto(items = emptyList())

        val online =
            runCatching { presenceService?.onlineUserIds(rows.map { it.userId }).orEmpty() }
                .getOrDefault(emptySet())
        // Online first, then the coarse order bucket, then the stable tiebreak and id (total order).
        val ordered =
            rows
                .map { row -> row to row.anchor(online) }
                .sortedWith { a, b -> a.second.compareTo(b.second) }
        val remaining = if (cursor == null) ordered else ordered.filter { (_, anchor) -> anchor.compareTo(cursor) > 0 }
        val page = remaining.take(limit)
        val hydration = repository.hydrate(viewerUserId, page.map { it.first.userId })
        val cards =
            page.map { (row, _) ->
                val h = hydration[row.userId]
                GridCardRecord(
                    userId = row.userId,
                    displayName = row.displayName,
                    thumbnailMediaAssetId = h?.thumbnailMediaAssetId,
                    showDistance = row.showDistance && row.distanceMeters != null,
                    distanceMeters = row.distanceMeters ?: 0.0,
                    linkedPartnerUserId = h?.linkedPartnerUserId,
                    linkedPartnerDisplayName = h?.linkedPartnerDisplayName,
                    linkedPartnerThumbnailMediaAssetId = h?.linkedPartnerThumbnailMediaAssetId,
                    visiting = row.visiting,
                    tonightActive = h?.tonightActive ?: false,
                    verified = h?.verified ?: false,
                    coupleVerified = h?.coupleVerified ?: false,
                ).toGridCardDto(online, mediaAssetUrlResolver)
            }
        val nextCursor =
            if (remaining.size > limit) {
                cursorCodec.encodeFavoritesCursor(viewerUserId, page.last().second)
            } else {
                null
            }
        return GridQueryResponseDto(items = cards, nextCursor = nextCursor)
    }

    private fun parseTarget(
        viewerUserId: UUID,
        text: String,
    ): UUID {
        val target = runCatching { UUID.fromString(text.trim()) }.getOrNull() ?: throw GridException("PROFILE_NOT_FOUND")
        if (target == viewerUserId) throw GridException("INVALID_FAVORITE")
        return target
    }

    private suspend fun requireRateLimit(viewerUserId: UUID) {
        val decision = rateLimitService?.check(viewerUserId, RateLimitedAction.FAVORITE_UPDATE) ?: return
        if (!decision.allowed) throw GridException("RATE_LIMITED")
    }

    private fun FavoriteRankRow.anchor(online: Set<UUID>): GridCursorAnchor =
        GridCursorAnchor(
            boostBucket = if (userId in online) 0 else 1,
            orderBucket = orderBucket,
            userId = userId,
            orderTiebreak = orderTiebreak,
        )

    companion object {
        /** Server cap on stored favorites per person (free for every tier). */
        const val MAX_FAVORITES = 500
        private const val DEFAULT_PAGE_SIZE = 60
        private const val MIN_PAGE_SIZE = 12
        private const val MAX_PAGE_SIZE = 120
    }
}

/** Keyset order over (online rank, order bucket, tiebreak, id); ids compare as Postgres does (unsigned). */
private fun GridCursorAnchor.compareTo(other: GridCursorAnchor): Int =
    compareValuesBy(this, other, { it.boostBucket }, { it.orderBucket }, { it.orderTiebreak })
        .takeIf { it != 0 }
        ?: java.lang.Long
            .compareUnsigned(userId.mostSignificantBits, other.userId.mostSignificantBits)
            .takeIf { it != 0 }
        ?: java.lang.Long.compareUnsigned(userId.leastSignificantBits, other.userId.leastSignificantBits)
