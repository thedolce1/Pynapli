package com.pynapli.server.grid

import com.pynapli.server.contactblocks.ContactBlockVisibility
import com.pynapli.server.discovery.DiscoveryCursorCodec
import com.pynapli.server.discovery.DiscoveryCursorException
import com.pynapli.server.discovery.DiscoveryFilters
import com.pynapli.server.discovery.discoveryFiltersFrom
import com.pynapli.server.distance.CoarseLocation
import com.pynapli.server.distance.toPublicDistanceLabel
import com.pynapli.server.distance.toPublicDistanceMeters
import com.pynapli.server.media.MediaAssetUrlResolver
import com.pynapli.server.presence.GridActiveAgo
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
import java.time.Clock
import java.time.Duration
import java.time.Instant
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
    // "Hide from people you know": people hiding from this viewer, applied with blocks and hides
    // after the shared ranked window (never inside it).
    private val contactBlockVisibility: ContactBlockVisibility? = null,
    // Buckets the cards' activeAgo keys (plan G11).
    private val clock: Clock = Clock.systemUTC(),
    // Section 3: opaque per-viewer ids for locked cards. Production derives it from the cursor secret.
    private val lockedCardTokens: LockedGridCardTokens = LockedGridCardTokens("pynapli-local-dev-discovery-cursor-secret"),
) {
    suspend fun queryGrid(
        viewerUserId: UUID,
        request: GridQueryRequestDto,
    ): GridQueryResponseDto {
        profile("grid.rateLimit") { requireGridQueryRateLimit(viewerUserId) }
        val radiusMeters =
            profile("grid.radius") {
                // L-09: snap UP to a fixed step so the radius cannot be binary-searched.
                CoarseLocation.snapRadiusMeters(request.radiusMeters.coerceIn(MIN_RADIUS_METERS, MAX_RADIUS_METERS))
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
                    boundaryItems = request.boundaryItems,
                    logisticsTags = request.logisticsTags,
                    tonightAvailableOnly = request.tonightAvailableOnly,
                    activeTodayOnly = request.activeTodayOnly,
                    // Fresh replaced "Recently joined"; an old client's field means Fresh now.
                    freshOnly = request.freshOnly || request.recentlyJoinedOnly,
                    travelingHereOnly = request.travelingHereOnly,
                    withPhotosOnly = request.withPhotosOnly,
                    hasPrivateAlbumOnly = request.hasPrivateAlbumOnly,
                    intents = request.intents,
                    genders = request.genders,
                    orientations = request.orientations,
                    verifiedCouplesOnly = request.verifiedCouplesOnly,
                    partnerGenders = request.partnerGenders,
                    partnerMinimumAge = request.partnerMinimumAge,
                    partnerMaximumAge = request.partnerMaximumAge,
                    partnerOrientations = request.partnerOrientations,
                    partnerVerifiedOnly = request.partnerVerifiedOnly,
                    excludeSingleMen = request.excludeSingleMen,
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
        // Section 3 paywall on tap. The tier depth is where locking starts; a client that accepts locked
        // cards keeps paging past it, with locked cards (no identity), up to the next tier's depth.
        // Everything here comes from the server-read entitlement and the sealed seen count, never from
        // the client. A client that doesn't opt in stops at the depth exactly as before.
        val lockedFrom = policy.gridResultDepthLimit
        val lockingApplies = request.acceptLockedCards && policy.gridLockedPreviewDepth > lockedFrom
        val servedDepth = if (lockingApplies) policy.gridLockedPreviewDepth else lockedFrom
        val depthLimited = servedDepth != Int.MAX_VALUE

        val cursorScopeHash =
            profile("grid.cursorScope") {
                DiscoveryCursorCodec.gridScopeHash(
                    viewerUserId = viewerUserId,
                    radiusMeters = radiusMeters,
                    limit = pageLimit,
                    filters = filters,
                    onlineOnly = request.onlineOnly,
                    acceptLockedCards = request.acceptLockedCards,
                    // Its own keyset order (arrival day first), so its cursors never continue Nearby.
                    visitingSoonOnly = request.visitingSoonOnly,
                )
            }
        // Sparse-area widening (plan G10) pages under its own scope: the widened radius plus the radius
        // the viewer asked for. The client keeps sending its own radius, so a follow-up cursor that does
        // not decode under the normal scope is tried under the widened one. Both are sealed, so neither
        // can be forged, and a widened cursor continues only the request that produced it.
        val canWiden = !request.visitingSoonOnly && radiusMeters < MAX_RADIUS_METERS
        val widenedScopeHash =
            if (canWiden) {
                DiscoveryCursorCodec.gridScopeHash(
                    viewerUserId = viewerUserId,
                    radiusMeters = MAX_RADIUS_METERS,
                    limit = pageLimit,
                    filters = filters,
                    onlineOnly = request.onlineOnly,
                    widenedFromRadiusMeters = radiusMeters,
                    acceptLockedCards = request.acceptLockedCards,
                )
            } else {
                null
            }
        var continuingWidened = false
        val cursor =
            profile("grid.cursorDecode") {
                request.cursor?.let { token ->
                    try {
                        cursorCodec.decodeGridCursor(token, cursorScopeHash)
                    } catch (_: DiscoveryCursorException) {
                        val widenedScope = widenedScopeHash ?: throw GridException("INVALID_GRID_CURSOR")
                        try {
                            cursorCodec.decodeGridCursor(token, widenedScope).also { continuingWidened = true }
                        } catch (_: DiscoveryCursorException) {
                            throw GridException("INVALID_GRID_CURSOR")
                        }
                    }
                }
            }
        val seenBefore = cursor?.seenCount ?: 0
        val remainingVisible = remainingGridResults(servedDepth, seenBefore)
        val limit = pageLimit.coerceAtMost(remainingVisible)
        if (limit <= 0) {
            return GridQueryResponseDto(
                items = emptyList(),
                nextCursor = null,
                advancedFiltersApplied = advancedFiltersApplied,
                resultLimitReached = depthLimited,
                resultLimitTier = policy.tier.takeIf { depthLimited },
                lockedFromIndex = lockedFrom.takeIf { lockingApplies },
                unlockTier = policy.gridUnlockTier.takeIf { lockingApplies },
            )
        }

        // Section 3 scraping guard: a page reaching into the locked window also spends the tighter
        // locked-page budget (on top of GRID_QUERY), before any ranking work is done.
        if (lockingApplies && seenBefore + limit > lockedFrom) {
            profile("grid.lockedRateLimit") { requireLockedPageRateLimit(viewerUserId) }
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

        val blockedRelatedIds =
            profile("grid.blockList") {
                blockRepository?.listBlockedRelatedUserIds(viewerUserId).orEmpty()
            }
        // Profiles the viewer hid, plus anyone hiding from the viewer (contact blocks): both drop the
        // card and strip the partner mini, the same way.
        val hiddenFromViewerIds =
            profile("grid.contactBlocks") {
                contactBlockVisibility?.hiddenFrom(viewerUserId).orEmpty()
            }
        val hiddenProfileIds =
            profile("grid.hiddenProfiles") {
                hiddenProfileRepository?.listHiddenProfileIds(viewerUserId).orEmpty() + hiddenFromViewerIds
            }
        profile("grid.presenceRefresh") { refreshViewerPresence(viewerUserId) }

        // One ranked page at [queryRadius] with every per-viewer exclusion applied: what the viewer
        // would actually see. Run once, or twice when a sparse first page widens.
        suspend fun visiblePage(queryRadius: Int): VisiblePage {
            val result =
                profile("grid.repository") {
                    if (request.visitingSoonOnly) {
                        // Every Grid filter, soonest arrival first, paged by its own cursor. Blocks, hides
                        // and contact blocks are applied in SQL (so pages fill) and again below.
                        repository.queryVisitingSoon(viewerUserId, queryRadius, fetchLimit, filters, cursor, hiddenFromViewerIds)
                    } else {
                        // The contact-block set goes in too: "Linked" must treat a partner hiding from
                        // the viewer as not shown, exactly as the card below strips their mini.
                        repository.queryNearby(viewerUserId, queryRadius, fetchLimit, filters, cursor, hiddenFromViewerIds)
                    }
                }
            if (!result.viewerHadLocation) throw GridException("LOCATION_REQUIRED")
            val visibleCards =
                profile("grid.blockFilter") {
                    if (blockedRelatedIds.isEmpty()) {
                        result.cards
                    } else {
                        result.cards.filterNot { it.userId in blockedRelatedIds }
                    }
                }
            val discoverableCards =
                profile("grid.hiddenFilter") {
                    if (hiddenProfileIds.isEmpty()) {
                        visibleCards
                    } else {
                        visibleCards
                            .filterNot { it.userId in hiddenProfileIds }
                            // "Verified couples only" means the badge this viewer sees: a couple whose
                            // partner is stripped below no longer qualifies.
                            .filterNot { filters.verifiedCouplesOnly && it.linkedPartnerUserId in hiddenProfileIds }
                            // Partner filters (PartnerFilterSql) likewise need the partner this viewer
                            // sees. The SQL gate already leaves out these partners; this is the backstop.
                            // "No single men" likewise: the card has no gender here, so any card losing
                            // its partner is dropped (stricter, never a leak).
                            .filterNot {
                                (filters.hasPartnerFilters || filters.excludeSingleMen) && it.linkedPartnerUserId in hiddenProfileIds
                            }.map { card ->
                                if (card.linkedPartnerUserId in hiddenProfileIds) {
                                    card.copy(
                                        linkedPartnerUserId = null,
                                        linkedPartnerDisplayName = null,
                                        linkedPartnerThumbnailMediaAssetId = null,
                                        linkedPartnerAge = null,
                                        linkedPartnerGender = null,
                                        // The badge needs a visible partner, as on Swipe cards.
                                        coupleVerified = false,
                                    )
                                } else {
                                    card
                                }
                            }
                    }
                }
            val onlineUserIds = profile("grid.presenceLookup") { onlineUserIdsFor(discoverableCards) }
            val filteredCards =
                profile("grid.onlineFilter") {
                    if (request.onlineOnly) {
                        discoverableCards.filter { it.userId in onlineUserIds }
                    } else {
                        discoverableCards
                    }
                }
            return VisiblePage(result = result, filteredCards = filteredCards, onlineUserIds = onlineUserIds)
        }

        // Plan G10 sparse areas (GRID_IMPLEMENTATION_SPEC "Default Deck / Never-Empty"): when the first
        // page at the requested radius would show fewer than SPARSE_FIRST_PAGE people, rank again at
        // the maximum radius and serve that instead. Distance-first ordering makes it a superset that
        // starts with the same nearby people. The widened answer pages normally under its own cursor
        // scope (above), and every filter still applies: only distance relaxes. Location privacy is
        // unchanged: the wide query is the same cell-to-cell rank at a fixed radius step.
        val queryRadius = if (continuingWidened) MAX_RADIUS_METERS else radiusMeters
        val first = visiblePage(queryRadius)
        val widenNow = canWiden && cursor == null && first.filteredCards.size < minOf(SPARSE_FIRST_PAGE, limit)
        val page =
            if (widenNow) {
                profile("grid.repositoryWiden") { visiblePage(MAX_RADIUS_METERS) }
            } else {
                first
            }
        val widened = widenNow || continuingWidened
        val result = page.result
        val filteredCards = page.filteredCards
        val onlineUserIds = page.onlineUserIds

        // Over-fetch reconciliation: return exactly the requested page and rebuild the cursor from the
        // last KEPT card. When more matches remain in the fetched window, the next page resumes after
        // the last kept card; otherwise it resumes past the fetched window (the repository anchor).
        val pageCards = filteredCards.take(limit)
        val moreInFetchedWindow = filteredCards.size > limit
        val now = clock.instant()
        val dtoCards =
            profile("grid.dto") {
                pageCards.mapIndexed { index, card ->
                    // Locked by overall position, so a page can straddle the boundary (Social's 600 is
                    // not a multiple of every page size).
                    if (lockingApplies && seenBefore + index >= lockedFrom) {
                        card.toLockedGridCardDto(
                            token = lockedCardTokens.tokenFor(viewerUserId, card.userId),
                            onlineUserIds = onlineUserIds,
                            mediaAssetUrlResolver = mediaAssetUrlResolver,
                        )
                    } else {
                        card.toGridCardDto(onlineUserIds, mediaAssetUrlResolver, now)
                    }
                }
            }
        val seenCount = seenBefore + dtoCards.size
        val effectiveNextAnchor =
            when {
                moreInFetchedWindow -> pageCards.lastOrNull()?.toCursorAnchor()
                else -> result.nextCursorAnchor
            }
        // For a client that doesn't opt in this is the tier depth, as before. For one that does, it is
        // the end of the locked window (lockedFromIndex/unlockTier mark where the paywall starts).
        val resultLimitReached =
            effectiveNextAnchor != null &&
                depthLimited &&
                seenCount >= servedDepth
        val nextScopeHash = if (widened) widenedScopeHash ?: cursorScopeHash else cursorScopeHash
        val nextCursor =
            effectiveNextAnchor
                ?.takeUnless { resultLimitReached }
                ?.let { cursorCodec.encodeGridCursor(nextScopeHash, it.copy(seenCount = seenCount)) }
        // Signalled on the page holding a locked card, every later page, and the page whose next page
        // starts locked (an unlocked page ending exactly at the depth).
        val lockSignal =
            lockingApplies &&
                (seenCount > lockedFrom || (seenCount >= lockedFrom && nextCursor != null))

        return GridQueryResponseDto(
            items = dtoCards,
            nextCursor = nextCursor,
            advancedFiltersApplied = advancedFiltersApplied,
            resultLimitReached = resultLimitReached,
            resultLimitTier = policy.tier.takeIf { resultLimitReached },
            widened = widened,
            lockedFromIndex = lockedFrom.takeIf { lockSignal },
            unlockTier = policy.gridUnlockTier.takeIf { lockSignal },
        )
    }

    private suspend fun requireGridQueryRateLimit(viewerUserId: UUID) {
        val decision = rateLimitService?.check(viewerUserId, RateLimitedAction.GRID_QUERY) ?: return
        if (!decision.allowed) throw GridException("RATE_LIMITED")
    }

    private suspend fun requireLockedPageRateLimit(viewerUserId: UUID) {
        val decision = rateLimitService?.check(viewerUserId, RateLimitedAction.GRID_LOCKED_PAGE) ?: return
        if (!decision.allowed) throw GridException("RATE_LIMITED")
    }

    private suspend fun refreshViewerPresence(viewerUserId: UUID) {
        // Throttled (one write a minute); the auth hook usually claimed it already for this request.
        runCatching { presenceService?.touch(viewerUserId) }
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

    companion object {
        private const val MIN_RADIUS_METERS = 1_000
        private const val MAX_RADIUS_METERS = 100_000
        private const val MAX_LIMIT = 120
        const val MIN_PAGE_SIZE = 12

        // Plan G10: a first page showing fewer people than this widens to MAX_RADIUS_METERS.
        const val SPARSE_FIRST_PAGE = 12

        // Online-only over-fetch: presence is filtered after SQL, so fetch this many times the page
        // size (bounded) to fill the page from the online subset without unbounded DB work.
        private const val ONLINE_OVERFETCH_MULTIPLIER = 4
        private const val MAX_ONLINE_FETCH = 240
    }
}

/** One ranked page after block, hide and online filtering, plus the presence it was filtered with. */
private class VisiblePage(
    val result: GridQueryResult,
    val filteredCards: List<GridCardRecord>,
    val onlineUserIds: Set<UUID>,
)

class GridException(
    val code: String,
) : RuntimeException(code)

private fun remainingGridResults(
    depth: Int,
    seenCount: Int,
): Int =
    if (depth == Int.MAX_VALUE) {
        Int.MAX_VALUE
    } else {
        (depth - seenCount).coerceAtLeast(0)
    }

/**
 * The one Grid card mapping, shared by the Grid and Favorites so both lists return the same minimal
 * card. Distance is the public bucket of the coarse cell-to-cell distance, and none when hidden.
 */
internal fun GridCardRecord.toGridCardDto(
    onlineUserIds: Set<UUID>,
    mediaAssetUrlResolver: MediaAssetUrlResolver?,
    now: Instant = Instant.now(),
): GridCardDto =
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
                    // Only inside the shown mini: a stripped partner takes these with it.
                    age = linkedPartnerAge,
                    gender = linkedPartnerGender,
                )
            },
        rankToken = "grid:v1:$userId",
        visiting = visiting,
        tonightActive = tonightActive,
        verified = verified,
        coupleVerified = coupleVerified,
        fresh = fresh,
        activeAgo =
            GridActiveAgo.keyFor(
                online = userId in onlineUserIds,
                activityHidden = activityHidden,
                sinceLastActive = lastActiveAt?.let { Duration.between(it, now) },
            ),
    )
