package com.pynapli.server.grid

import com.pynapli.shared.network.dto.GridCardDto
import com.pynapli.shared.network.dto.GridQueryRequestDto
import com.pynapli.shared.network.dto.GridQueryResponseDto
import com.pynapli.server.media.MediaAssetUrlResolver
import com.pynapli.server.presence.PresenceService
import com.pynapli.server.safety.BlockRepository
import java.util.UUID
import kotlin.math.roundToInt

class GridService(
    private val repository: GridRepository,
    private val presenceService: PresenceService? = null,
    private val mediaAssetUrlResolver: MediaAssetUrlResolver? = null,
    private val blockRepository: BlockRepository? = null
) {
    suspend fun queryGrid(
        viewerUserId: UUID,
        request: GridQueryRequestDto
    ): GridQueryResponseDto {
        val radiusMeters = request.radiusMeters.coerceIn(MIN_RADIUS_METERS, MAX_RADIUS_METERS)
        val limit = request.limit.coerceIn(1, MAX_LIMIT)
        val result = repository.queryNearby(viewerUserId, radiusMeters, limit)
        if (!result.viewerHadLocation) throw GridException("LOCATION_REQUIRED")

        val blockedRelatedIds = blockRepository?.listBlockedRelatedUserIds(viewerUserId).orEmpty()
        val visibleCards = if (blockedRelatedIds.isEmpty()) {
            result.cards
        } else {
            result.cards.filterNot { it.userId in blockedRelatedIds }
        }

        refreshViewerPresence(viewerUserId)
        val onlineUserIds = onlineUserIdsFor(visibleCards)

        return GridQueryResponseDto(
            items = visibleCards.map { it.toDto(onlineUserIds) },
            nextCursor = null
        )
    }

    private suspend fun refreshViewerPresence(viewerUserId: UUID) {
        runCatching { presenceService?.refreshOnline(viewerUserId) }
    }

    private suspend fun onlineUserIdsFor(cards: List<GridCardRecord>): Set<UUID> =
        runCatching {
            presenceService?.onlineUserIds(cards.map { it.userId }).orEmpty()
        }.getOrDefault(emptySet())

    private fun GridCardRecord.toDto(onlineUserIds: Set<UUID>): GridCardDto = GridCardDto(
        userId = userId.toString(),
        displayName = displayName,
        thumbnailUrl = thumbnailMediaAssetId?.let { mediaAssetUrlResolver?.publicContentUrl(it) },
        online = userId in onlineUserIds,
        distanceLabel = if (showDistance) distanceMeters.toDistanceLabel() else null,
        rankToken = "grid:v1:$userId"
    )

    private fun Double.toDistanceLabel(): String {
        if (this < 1_000.0) {
            val roundedMeters = ((this / 100.0).roundToInt() * 100).coerceAtLeast(100)
            return "$roundedMeters m"
        }

        val km = this / 1_000.0
        return if (km < 10) {
            "%.1f km".format(km)
        } else {
            "${km.roundToInt()} km"
        }
    }

    companion object {
        private const val MIN_RADIUS_METERS = 1_000
        private const val MAX_RADIUS_METERS = 100_000
        private const val MAX_LIMIT = 120
    }
}

class GridException(val code: String) : RuntimeException(code)
