package com.pynapli.server.grid

import com.pynapli.server.discovery.DiscoveryCursorCodec
import com.pynapli.server.discovery.DiscoveryCursorException
import com.pynapli.server.discovery.DiscoveryFilters
import com.pynapli.server.discovery.discoveryFiltersFrom
import com.pynapli.server.distance.toPublicDistanceLabel
import com.pynapli.server.distance.toPublicDistanceMeters
import com.pynapli.server.media.MediaAssetUrlResolver
import com.pynapli.server.presence.PresenceService
import com.pynapli.server.profiling.profile
import com.pynapli.server.safety.BlockRepository
import com.pynapli.server.safety.HiddenProfileRepository
import com.pynapli.server.safety.RateLimitService
import com.pynapli.server.safety.RateLimitedAction
import com.pynapli.server.subscriptions.EntitlementPolicy
import com.pynapli.server.subscriptions.SubscriptionService
import com.pynapli.shared.network.dto.GridCardDto
import com.pynapli.shared.network.dto.GridQueryRequestDto
import com.pynapli.shared.network.dto.GridQueryResponseDto
import com.pynapli.shared.network.dto.LinkedPartnerMiniDto
import java.util.UUID

class GridService(
    private val repository: GridRepository,
    private val cursorCodec: DiscoveryCursorCodec = DiscoveryCursorCodec("pynapli-local-dev-discovery-cursor-secret"),
    private val presenceService: PresenceService? = null,
    private val mediaAssetUrlResolver: MediaAssetUrlResolver? = null,
    private val blockRepository: BlockRepository? = null,
    private val hiddenProfileRepository: HiddenProfileRepository? = null,
    private val subscriptionService: SubscriptionService? = null,
    private val viewerTrustRepository: GridViewerTrustRepository? = null,
    private val rateLimitService: RateLimitService? = null,
) {
    suspend fun queryGrid(
        viewerUserId: UUID,
        request: GridQueryRequestDto,
    ): GridQueryResponseDto {
        profile("grid.rateLimit") { requireGridQueryRateLimit(viewerUserId) }
        val radiusMeters =
            profile("grid.radius") {
                request.radiusMeters.coerceIn(MIN_RADIUS_METERS, MAX_RADIUS_METERS)
            }
        val filters =
            profile("grid.filters") {
                discoveryFiltersFrom(
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
                    intents = request.intents,
                    genders = request.genders,
                    orientations = request.orientations,
                ) { _ -> GridException("INVALID_GRID_FILTER") }
            }
        profile("grid.lifestyleGate") { requireLifestyleFilterAccess(viewerUserId, filters) }
        val advancedFiltersApplied = filters.requiresAdvancedEntitlement
        val policy =
            profile("grid.entitlement") {
                subscriptionService?.getPolicy(viewerUserId) ?: EntitlementPolicy.unrestricted()
            }
        if (advancedFiltersApplied && !policy.advancedFiltersAllowed) {
            throw GridException("ENTITLEMENT_REQUIRED")
        }
        val pageLimit = request.limit.coerceIn(1, minOf(MAX_LIMIT, policy.gridPageLimit))

        val cursorScopeHash =
            profile("grid.cursorScope") {
                DiscoveryCursorCodec.gridScopeHash(
                    viewerUserId = viewerUserId,
                    radiusMeters = radiusMeters,
                    limit = pageLimit,
                    filters = filters,
                    onlineOnly = request.onlineOnly,
                )
            }
        val cursor =
            profile("grid.cursorDecode") {
                request.cursor?.let { token ->
                    try {
                        cursorCodec.decodeGridCursor(token, cursorScopeHash)
                    } catch (_: DiscoveryCursorException) {
                        throw GridException("INVALID_GRID_CURSOR")
                    }
                }
            }
        val remainingVisible = policy.remainingGridResults(cursor?.seenCount ?: 0)
        val limit = pageLimit.coerceAtMost(remainingVisible)
        if (limit <= 0) {
            return GridQueryResponseDto(
                items = emptyList(),
                nextCursor = null,
                adSupported = policy.adSupported,
                advancedFiltersApplied = advancedFiltersApplied,
                resultLimitReached = policy.gridResultDepthLimit != Int.MAX_VALUE,
                resultLimitTier = policy.tier.takeIf { policy.gridResultDepthLimit != Int.MAX_VALUE },
            )
        }

        val result =
            profile("grid.repository") {
                repository.queryNearby(viewerUserId, radiusMeters, limit, filters, cursor)
            }
        if (!result.viewerHadLocation) throw GridException("LOCATION_REQUIRED")

        val blockedRelatedIds =
            profile("grid.blockList") {
                blockRepository?.listBlockedRelatedUserIds(viewerUserId).orEmpty()
            }
        val visibleCards =
            profile("grid.blockFilter") {
                if (blockedRelatedIds.isEmpty()) {
                    result.cards
                } else {
                    result.cards.filterNot { it.userId in blockedRelatedIds }
                }
            }
        val hiddenProfileIds =
            profile("grid.hiddenProfiles") {
                hiddenProfileRepository?.listHiddenProfileIds(viewerUserId).orEmpty()
            }
        val discoverableCards =
            profile("grid.hiddenFilter") {
                if (hiddenProfileIds.isEmpty()) {
                    visibleCards
                } else {
                    visibleCards
                        .filterNot { it.userId in hiddenProfileIds }
                        .map { card ->
                            if (card.linkedPartnerUserId in hiddenProfileIds) {
                                card.copy(
                                    linkedPartnerUserId = null,
                                    linkedPartnerDisplayName = null,
                                    linkedPartnerThumbnailMediaAssetId = null,
                                )
                            } else {
                                card
                            }
                        }
                }
            }

        profile("grid.presenceRefresh") { refreshViewerPresence(viewerUserId) }
        val onlineUserIds = profile("grid.presenceLookup") { onlineUserIdsFor(discoverableCards) }
        val filteredCards =
            profile("grid.onlineFilter") {
                if (request.onlineOnly) {
                    discoverableCards.filter { it.userId in onlineUserIds }
                } else {
                    discoverableCards
                }
            }
        val dtoCards = profile("grid.dto") { filteredCards.map { it.toDto(onlineUserIds) } }
        val seenCount = (cursor?.seenCount ?: 0) + dtoCards.size
        val resultLimitReached =
            result.nextCursorAnchor != null &&
                policy.gridResultDepthLimit != Int.MAX_VALUE &&
                seenCount >= policy.gridResultDepthLimit

        return GridQueryResponseDto(
            items = dtoCards,
            nextCursor =
                result.nextCursorAnchor
                    ?.takeUnless { resultLimitReached }
                    ?.let { cursorCodec.encodeGridCursor(cursorScopeHash, it.copy(seenCount = seenCount)) },
            adSupported = policy.adSupported,
            advancedFiltersApplied = advancedFiltersApplied,
            resultLimitReached = resultLimitReached,
            resultLimitTier = policy.tier.takeIf { resultLimitReached },
        )
    }

    private suspend fun requireGridQueryRateLimit(viewerUserId: UUID) {
        val decision = rateLimitService?.check(viewerUserId, RateLimitedAction.GRID_QUERY) ?: return
        if (!decision.allowed) throw GridException("RATE_LIMITED")
    }

    private suspend fun refreshViewerPresence(viewerUserId: UUID) {
        runCatching { presenceService?.refreshOnline(viewerUserId) }
    }

    private suspend fun onlineUserIdsFor(cards: List<GridCardRecord>): Set<UUID> =
        runCatching {
            presenceService?.onlineUserIds(cards.map { it.userId }).orEmpty()
        }.getOrDefault(emptySet())

    private suspend fun requireLifestyleFilterAccess(
        viewerUserId: UUID,
        filters: DiscoveryFilters,
    ) {
        if (filters.lifestyleTags.isEmpty()) return
        val verified = viewerTrustRepository?.isAgeVerified(viewerUserId) ?: true
        if (!verified) throw GridException("AGE_VERIFICATION_REQUIRED")
    }

    private fun GridCardRecord.toDto(onlineUserIds: Set<UUID>): GridCardDto =
        GridCardDto(
            userId = userId.toString(),
            displayName = displayName,
            thumbnailUrl = thumbnailMediaAssetId?.let { mediaAssetUrlResolver?.publicContentUrl(it) },
            online = userId in onlineUserIds,
            distanceLabel = if (showDistance) distanceMeters.toPublicDistanceLabel() else null,
            distanceMeters = if (showDistance) distanceMeters.toPublicDistanceMeters() else null,
            linkedPartner =
                linkedPartnerUserId?.let { partnerUserId ->
                    LinkedPartnerMiniDto(
                        userId = partnerUserId.toString(),
                        displayName = linkedPartnerDisplayName,
                        thumbnailUrl =
                            linkedPartnerThumbnailMediaAssetId
                                ?.let { mediaAssetUrlResolver?.publicContentUrl(it) },
                    )
                },
            rankToken = "grid:v1:$userId",
            visiting = visiting,
            tonightActive = tonightActive,
        )

    companion object {
        private const val MIN_RADIUS_METERS = 1_000
        private const val MAX_RADIUS_METERS = 100_000
        private const val MAX_LIMIT = 120
    }
}

class GridException(
    val code: String,
) : RuntimeException(code)

private fun EntitlementPolicy.remainingGridResults(seenCount: Int): Int =
    if (gridResultDepthLimit == Int.MAX_VALUE) {
        Int.MAX_VALUE
    } else {
        (gridResultDepthLimit - seenCount).coerceAtLeast(0)
    }
