package com.pynapli.server.swipe

import com.pynapli.server.distance.CoarseLocation
import com.pynapli.server.presence.PresenceService
import com.pynapli.server.profiling.profile
import com.pynapli.server.safety.BlockRepository
import com.pynapli.server.subscriptions.SubscriptionService
import com.pynapli.shared.network.dto.SwipeDeckRequestDto
import com.pynapli.shared.network.dto.SwipeDeckResponseDto
import java.time.Clock
import java.util.UUID

internal class SwipeDeckQueryAction(
    private val repository: SwipeRepository,
    private val blockRepository: BlockRepository?,
    private val subscriptionService: SubscriptionService?,
    private val presenceService: PresenceService?,
    private val deckTokenCodec: SwipeDeckTokenCodec,
    private val deckPageBuilder: SwipeDeckPageBuilder,
    private val clock: Clock,
    private val contactBlockVisibility: com.pynapli.server.contactblocks.ContactBlockVisibility? = null,
    // Lifestyle tags need an age-verified viewer (safety, not payment), as on the Grid and Tonight.
    private val viewerTrustRepository: com.pynapli.server.grid.GridViewerTrustRepository? = null,
) {
    suspend fun queryDeck(
        viewerUserId: UUID,
        request: SwipeDeckRequestDto,
        // Set only for a Psync deck: the other partner of the session. That deck is shared by both, so
        // people hiding from one partner are still dealt (each partner's view leaves out who hides from
        // them, PsyncDeckCoordinator), but a candidate's PARTNER is shown, and counts for Linked /
        // verified couples, only when both partners may see them (PSYNC_SPEC, strictest view).
        psyncPartnerUserId: UUID? = null,
    ): SwipeDeckResponseDto {
        val applyContactBlocks = psyncPartnerUserId == null
        val deckRequest =
            SwipeDeckRequestParser.parse(
                request = request,
                minRadiusMeters = SWIPE_MIN_RADIUS_METERS,
                maxRadiusMeters = SWIPE_MAX_RADIUS_METERS,
                maxLimit = SWIPE_MAX_LIMIT,
            )
        // Fixed radius steps (L-09), as in the Grid: a radius between two steps returns its step, so a
        // slider moved metre by metre can never binary-search where someone is.
        val radiusMeters = CoarseLocation.snapRadiusMeters(deckRequest.radiusMeters)
        val limit = deckRequest.limit
        val filters = deckRequest.filters
        // Founder decision 2 (2026-11-26): every Swipe filter is free. Lifestyle tags still need age
        // verification. A Psync deck is gated when the session is created or its filters re-suggested
        // (PsyncLifecycleActions), never mid-deal, so it skips this check.
        if (psyncPartnerUserId == null && filters.lifestyleTags.isNotEmpty()) {
            val ageVerified = viewerTrustRepository?.isAgeVerified(viewerUserId) ?: true
            if (!ageVerified) throw SwipeException("AGE_VERIFICATION_REQUIRED")
        }
        val now = clock.instant()
        val scopeHash =
            SwipeDeckTokenCodec.deckScopeHash(
                viewerUserId = viewerUserId,
                radiusMeters = radiusMeters,
                limit = limit,
                filters = filters,
                dealbreakers = deckRequest.dealbreakers,
            )
        val cursor =
            request.cursor?.trim()?.takeIf { it.isNotEmpty() }?.let { token ->
                try {
                    deckTokenCodec.decodeCursor(token, viewerUserId, now)
                } catch (error: SwipeDeckTokenException) {
                    throw SwipeException(error.code)
                }
            }
        if (cursor != null && cursor.scopeHash != scopeHash) {
            throw SwipeException(SwipeDeckTokenCodec.INVALID_CURSOR)
        }
        val existingSessionState =
            cursor?.let { decoded ->
                val stored =
                    profile("swipe.deck.session.find") {
                        repository.findDeckSessionState(decoded.sessionId, viewerUserId)
                    } ?: throw SwipeException(SwipeDeckTokenCodec.INVALID_CURSOR)
                val session = stored.session
                if (
                    session.deckSessionTokenHash != decoded.deckSessionTokenHash ||
                    session.scopeHash != decoded.scopeHash ||
                    !session.expiresAt.isAfter(now)
                ) {
                    throw SwipeException(
                        if (!session.expiresAt.isAfter(now)) {
                            SwipeDeckTokenCodec.STALE_DECK_SESSION
                        } else {
                            SwipeDeckTokenCodec.INVALID_CURSOR
                        },
                    )
                }
                stored
            }
        val existingSession = existingSessionState?.session
        val deckSession =
            existingSession?.toIssuedSwipeDeckSession() ?: deckTokenCodec.issueDeckSession(
                viewerUserId = viewerUserId,
                scopeHash = scopeHash,
                now = now,
            )
        val blockedRelatedIds =
            profile("swipe.deck.blocks") {
                blockRepository?.listBlockedRelatedUserIds(viewerUserId).orEmpty()
            }
        // "Hide from people you know": people hiding from this viewer, excluded with the blocks (after
        // the shared ranked window, which stays free of per-viewer exclusions).
        val hiddenFromViewerIds =
            profile("swipe.deck.contactBlocks") {
                if (applyContactBlocks) contactBlockVisibility?.hiddenFrom(viewerUserId).orEmpty() else emptySet()
            }
        // Whose partner is never shown (nor counted for Linked / verified couples): people hiding from this
        // viewer; on a Psync deck, from EITHER partner, so neither can infer a partner hidden from them.
        val partnerHiddenIds =
            if (psyncPartnerUserId == null) {
                hiddenFromViewerIds
            } else {
                profile("swipe.deck.psyncContactBlocks") {
                    contactBlockVisibility?.let { it.hiddenFrom(viewerUserId) + it.hiddenFrom(psyncPartnerUserId) }.orEmpty()
                }
            }
        // Blocked people are excluded before ranking, not only from the finished page: a Boost lifts
        // candidates onto the page, and a blocked one must never take a slot.
        val excludedTargetUserIds = existingSessionState?.targetUserIds.orEmpty() + blockedRelatedIds + hiddenFromViewerIds
        val startPosition = cursor?.nextPosition ?: 0

        val firstPass =
            profile("swipe.deck.repo.queryDeck") {
                repository.queryDeck(
                    viewerUserId = viewerUserId,
                    radiusMeters = radiusMeters,
                    limit = limit + 1,
                    filters = filters,
                    excludedTargetUserIds = excludedTargetUserIds,
                    // "Linked" treats a partner hiding from the viewer as not shown, as the card does.
                    hiddenFromViewerIds = partnerHiddenIds,
                    coViewerUserId = psyncPartnerUserId,
                )
            }
        val dealbreakerSet = deckRequest.dealbreakers
        val lowWaterMark = SwipeDeckRelaxation.lowWaterMark(limit)
        val result =
            if (
                firstPass.viewerHadLocation &&
                firstPass.cards.size < lowWaterMark &&
                SwipeDeckRelaxation.hasRelaxableFilters(
                    filters = filters,
                    radiusMeters = radiusMeters,
                    maxRadiusMeters = SWIPE_MAX_RADIUS_METERS,
                    dealbreakers = dealbreakerSet,
                )
            ) {
                val relaxedFilters = SwipeDeckRelaxation.relaxFilters(filters, dealbreakerSet)
                val relaxedRadius =
                    CoarseLocation.snapRadiusMeters(
                        SwipeDeckRelaxation.relaxRadius(
                            radiusMeters = radiusMeters,
                            maxRadiusMeters = SWIPE_MAX_RADIUS_METERS,
                            dealbreakers = dealbreakerSet,
                        ),
                    )
                val alreadySeen = excludedTargetUserIds + firstPass.cards.mapTo(mutableSetOf()) { it.userId }
                val secondPass =
                    profile("swipe.deck.repo.queryDeckRelaxed") {
                        repository.queryDeck(
                            viewerUserId = viewerUserId,
                            radiusMeters = relaxedRadius,
                            limit = limit + 1,
                            filters = relaxedFilters,
                            excludedTargetUserIds = alreadySeen,
                            hiddenFromViewerIds = partnerHiddenIds,
                            coViewerUserId = psyncPartnerUserId,
                        )
                    }
                val merged = (firstPass.cards + secondPass.cards).distinctBy { it.userId }
                firstPass.copy(cards = merged)
            } else {
                firstPass
            }
        if (!result.viewerHadLocation) throw SwipeException("LOCATION_REQUIRED")

        // Backstop for a repository that does not apply the exclusion set.
        val visible =
            if (blockedRelatedIds.isEmpty() && hiddenFromViewerIds.isEmpty() && partnerHiddenIds.isEmpty()) {
                result.cards
            } else {
                result.cards
                    .filterNot { it.userId in blockedRelatedIds || it.userId in hiddenFromViewerIds }
                    // "Verified couples only" means the badge this viewer sees, which a stripped
                    // partner takes away.
                    .filterNot { filters.verifiedCouplesOnly && it.linkedPartnerUserId in partnerHiddenIds }
                    // Partner filters and "No single men" too (PartnerFilterSql already gates this
                    // partner out in SQL). For "No single men" the card carries no gender, so any card
                    // that loses its partner here is dropped: stricter, never a leak.
                    .filterNot {
                        (filters.hasPartnerFilters || filters.excludeSingleMen) && it.linkedPartnerUserId in partnerHiddenIds
                    }.map { it.withoutPartnerIn(partnerHiddenIds) }
            }
        val pageCards = visible.take(limit)
        val presenceLookupIds =
            buildSet {
                pageCards.forEach { card ->
                    add(card.userId)
                    card.linkedPartnerUserId?.let { add(it) }
                }
            }
        val onlineUserIds =
            profile("swipe.deck.presence") {
                onlineSwipeUserIdsFor(presenceService, presenceLookupIds)
            }
        val pageItems =
            deckPageBuilder.buildPageItems(
                pageCards = pageCards,
                deckSession = deckSession,
                viewerUserId = viewerUserId,
                scopeHash = scopeHash,
                startPosition = startPosition,
                onlineUserIds = onlineUserIds,
                dealtAt = now,
            )
        profile("swipe.deck.session.upsert") {
            repository.upsertDeckSession(
                session =
                    deckSession.toSwipeDeckSessionRecord(
                        viewerUserId = viewerUserId,
                        scopeHash = scopeHash,
                        radiusMeters = radiusMeters,
                        pageLimit = limit,
                        filterPayload = filters.toPersistedJson(),
                    ),
                cards = pageItems.map { it.persistedCard },
            )
        }
        val nextCursor =
            if (visible.size > limit) {
                deckTokenCodec.issueCursor(
                    deckSession = deckSession,
                    viewerUserId = viewerUserId,
                    scopeHash = scopeHash,
                    nextPosition = startPosition + pageItems.size,
                )
            } else {
                null
            }

        return SwipeDeckResponseDto(
            deckSessionId = deckSession.deckSessionId,
            generatedAt = deckSession.generatedAt.toString(),
            items = pageItems.map { it.dto },
            nextCursor = nextCursor,
            // Kept on the wire for shipped clients, which lock chips when it is false: always true now.
            advancedFiltersAllowed = true,
            advancedFiltersApplied = false,
        )
    }
}
