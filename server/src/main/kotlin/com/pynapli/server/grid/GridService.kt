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
                    activeTodayOnly = request.activeTodayOnly,
                    recentlyJoinedOnly = request.recentlyJoinedOnly,
                    travelingHereOnly = request.travelingHereOnly,
                    withPhotosOnly = request.withPhotosOnly,
                    hasPrivateAlbumOnly = request.hasPrivateAlbumOnly,
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
        // L-07: never fewer than MIN_PAGE_SIZE cards per request (a one-card page lets a client probe
        // the ranking card by card). Only the tier depth cap below can make a final page shorter.
        val pageLimit = request.limit.coerceIn(MIN_PAGE_SIZE, maxOf(MIN_PAGE_SIZE, minOf(MAX_LIMIT, policy.gridPageLimit)))

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
                advancedFiltersApplied = advancedFiltersApplied,
                resultLimitReached = policy.gridResultDepthLimit != Int.MAX_VALUE,
                resultLimitTier = policy.tier.takeIf { policy.gridResultDepthLimit != Int.MAX_VALUE },
            )
        }

        // Online-only pages under-fill because presence is filtered AFTER the SQL (presence lives in
        // Redis, not the DB): a page of `limit` ranked candidates can lose most rows to the online
        // filter. Over-fetch a bounded larger candidate window when onlineOnly so the page still fills
        // to `limit`; the cursor is rebuilt from the last KEPT card below. Default (non-online) queries
        // fetch exactly `limit`, unchanged.
        val fetchLimit =
            if (request.onlineOnly) {
                (limit * ONLINE_OVERFETCH_MULTIPLIER).coerceAtMost(MAX_ONLINE_FETCH)
            } else {
                limit
            }

        val initial =
            profile("grid.repository") {
                if (request.visitingSoonOnly) {
                    // One page, soonest trips first; blocks, hides and presence apply below as usual.
                    repository.queryVisitingSoon(viewerUserId, radiusMeters, fetchLimit)
                } else {
                    repository.queryNearby(viewerUserId, radiusMeters, fetchLimit, filters, cursor)
                }
            }
        if (!initial.viewerHadLocation) throw GridException("LOCATION_REQUIRED")

        // FILTERS_SPEC "Default deck / never-empty": when the first page at the requested
        // radius returns no candidates, widen once to the maximum radius so a viewer with no
        // (or sparse) matches still sees nearby-ish people rather than an empty grid. Mirrors
        // the Swipe deck's radius relaxation (SwipeDeckRelaxation.relaxRadius). Edge-fill only:
        // a widened page does not paginate (nextCursor stays null), so the wide radius never
        // leaks into the narrow-radius cursor scope on a follow-up page request.
        val widened = !request.visitingSoonOnly && cursor == null && initial.cards.isEmpty() && radiusMeters < MAX_RADIUS_METERS
        val result =
            if (widened) {
                profile("grid.repositoryWiden") {
                    repository.queryNearby(viewerUserId, MAX_RADIUS_METERS, fetchLimit, filters, cursor = null)
                }
            } else {
                initial
            }

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
        // Over-fetch reconciliation: return exactly the requested page and rebuild the cursor from the
        // last KEPT card. When more matches remain in the fetched window, the next page resumes after
        // the last kept card; otherwise it resumes past the fetched window (the repository anchor).
        val pageCards = filteredCards.take(limit)
        val moreInFetchedWindow = filteredCards.size > limit
        val dtoCards = profile("grid.dto") { pageCards.map { it.toDto(onlineUserIds) } }
        val seenCount = (cursor?.seenCount ?: 0) + dtoCards.size
        // A widened fallback page is edge-fill only: do not hand back a cursor scoped to the
        // max radius, since the next request still carries the viewer's narrow radius.
        val effectiveNextAnchor =
            when {
                widened -> null
                moreInFetchedWindow -> pageCards.lastOrNull()?.toCursorAnchor()
                else -> result.nextCursorAnchor
            }
        val resultLimitReached =
            effectiveNextAnchor != null &&
                policy.gridResultDepthLimit != Int.MAX_VALUE &&
                seenCount >= policy.gridResultDepthLimit

        return GridQueryResponseDto(
            items = dtoCards,
            nextCursor =
                effectiveNextAnchor
                    ?.takeUnless { resultLimitReached }
                    ?.let { cursorCodec.encodeGridCursor(cursorScopeHash, it.copy(seenCount = seenCount)) },
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
            visitingFrom = visitingFrom?.toString(),
            visitingUntil = visitingUntil?.toString(),
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
        const val MIN_PAGE_SIZE = 12

        // Online-only over-fetch: presence is filtered after SQL, so fetch this many times the page
        // size (bounded) to fill the page from the online subset without unbounded DB work.
        private const val ONLINE_OVERFETCH_MULTIPLIER = 4
        private const val MAX_ONLINE_FETCH = 240
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
