package com.pynapli.server.grid

import com.pynapli.server.discovery.DiscoveryCursorCodec
import com.pynapli.server.discovery.DiscoveryCursorException
import com.pynapli.server.discovery.DiscoveryFilters
import com.pynapli.server.discovery.discoveryFiltersFrom
import com.pynapli.server.distance.toPublicDistanceLabel
import com.pynapli.server.distance.toPublicDistanceMeters
import com.pynapli.shared.network.dto.GridCardDto
import com.pynapli.shared.network.dto.GridQueryRequestDto
import com.pynapli.shared.network.dto.GridQueryResponseDto
import com.pynapli.server.media.MediaAssetUrlResolver
import com.pynapli.server.presence.PresenceService
import com.pynapli.server.safety.BlockRepository
import com.pynapli.server.subscriptions.EntitlementPolicy
import com.pynapli.server.subscriptions.SubscriptionService
import java.util.UUID
import com.pynapli.shared.network.dto.LinkedPartnerMiniDto

class GridService(
    private val repository: GridRepository,
    private val cursorCodec: DiscoveryCursorCodec = DiscoveryCursorCodec("pynapli-local-dev-discovery-cursor-secret"),
    private val presenceService: PresenceService? = null,
    private val mediaAssetUrlResolver: MediaAssetUrlResolver? = null,
    private val blockRepository: BlockRepository? = null,
    private val subscriptionService: SubscriptionService? = null,
    private val viewerTrustRepository: GridViewerTrustRepository? = null
) {
    suspend fun queryGrid(
        viewerUserId: UUID,
        request: GridQueryRequestDto
    ): GridQueryResponseDto {
        val radiusMeters = request.radiusMeters.coerceIn(MIN_RADIUS_METERS, MAX_RADIUS_METERS)
        val limit = request.limit.coerceIn(1, MAX_LIMIT)
        val filters = discoveryFiltersFrom(
            minimumAge = request.minimumAge,
            maximumAge = request.maximumAge,
            relationshipContexts = request.relationshipContexts,
            linkedOnly = request.linkedOnly,
            verifiedOnly = request.verifiedOnly,
            facePhotoVerifiedOnly = request.facePhotoVerifiedOnly,
            ageVerifiedOnly = request.ageVerifiedOnly,
            discoveryStyles = request.discoveryStyles,
            lifestyleTags = request.lifestyleTags,
            logisticsTags = request.logisticsTags,
            tonightAvailableOnly = request.tonightAvailableOnly,
            intents = request.intents
        ) { _ -> GridException("INVALID_GRID_FILTER") }
        requireLifestyleFilterAccess(viewerUserId, filters)
        val advancedFiltersApplied = filters.requiresAdvancedEntitlement
        val policy = subscriptionService?.getPolicy(viewerUserId) ?: EntitlementPolicy.unrestricted()
        if (advancedFiltersApplied && !policy.advancedFiltersAllowed) {
            throw GridException("ENTITLEMENT_REQUIRED")
        }

        val cursorScopeHash = DiscoveryCursorCodec.gridScopeHash(
            viewerUserId = viewerUserId,
            radiusMeters = radiusMeters,
            limit = limit,
            filters = filters,
            onlineOnly = request.onlineOnly
        )
        val cursor = request.cursor?.let { token ->
            try {
                cursorCodec.decodeGridCursor(token, cursorScopeHash)
            } catch (_: DiscoveryCursorException) {
                throw GridException("INVALID_GRID_CURSOR")
            }
        }

        val result = repository.queryNearby(viewerUserId, radiusMeters, limit, filters, cursor)
        if (!result.viewerHadLocation) throw GridException("LOCATION_REQUIRED")

        val blockedRelatedIds = blockRepository?.listBlockedRelatedUserIds(viewerUserId).orEmpty()
        val visibleCards = if (blockedRelatedIds.isEmpty()) {
            result.cards
        } else {
            result.cards.filterNot { it.userId in blockedRelatedIds }
        }

        refreshViewerPresence(viewerUserId)
        val onlineUserIds = onlineUserIdsFor(visibleCards)
        val filteredCards = if (request.onlineOnly) {
            visibleCards.filter { it.userId in onlineUserIds }
        } else {
            visibleCards
        }

        return GridQueryResponseDto(
            items = filteredCards.map { it.toDto(onlineUserIds) },
            nextCursor = result.nextCursorAnchor?.let { cursorCodec.encodeGridCursor(cursorScopeHash, it) },
            adSupported = policy.adSupported,
            advancedFiltersApplied = advancedFiltersApplied
        )
    }

    private suspend fun refreshViewerPresence(viewerUserId: UUID) {
        runCatching { presenceService?.refreshOnline(viewerUserId) }
    }

    private suspend fun onlineUserIdsFor(cards: List<GridCardRecord>): Set<UUID> =
        runCatching {
            presenceService?.onlineUserIds(cards.map { it.userId }).orEmpty()
        }.getOrDefault(emptySet())

    private suspend fun requireLifestyleFilterAccess(viewerUserId: UUID, filters: DiscoveryFilters) {
        if (filters.lifestyleTags.isEmpty()) return
        val verified = viewerTrustRepository?.isAgeVerified(viewerUserId) ?: true
        if (!verified) throw GridException("AGE_VERIFICATION_REQUIRED")
    }

    private fun GridCardRecord.toDto(onlineUserIds: Set<UUID>): GridCardDto = GridCardDto(
        userId = userId.toString(),
        displayName = displayName,
        thumbnailUrl = thumbnailMediaAssetId?.let { mediaAssetUrlResolver?.publicContentUrl(it) },
        online = userId in onlineUserIds,
        distanceLabel = if (showDistance) distanceMeters.toPublicDistanceLabel() else null,
        distanceMeters = if (showDistance) distanceMeters.toPublicDistanceMeters() else null,
        linkedPartner = linkedPartnerUserId?.let { partnerUserId ->
            LinkedPartnerMiniDto(
                userId = partnerUserId.toString(),
                displayName = linkedPartnerDisplayName,
                thumbnailUrl = linkedPartnerThumbnailMediaAssetId
                    ?.let { mediaAssetUrlResolver?.publicContentUrl(it) }
            )
        },
        rankToken = "grid:v1:$userId"
    )

    companion object {
        private const val MIN_RADIUS_METERS = 1_000
        private const val MAX_RADIUS_METERS = 100_000
        private const val MAX_LIMIT = 120
    }
}

class GridException(val code: String) : RuntimeException(code)
