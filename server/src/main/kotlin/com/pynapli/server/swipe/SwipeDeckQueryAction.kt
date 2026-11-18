package com.pynapli.server.swipe

import com.pynapli.server.distance.CoarseLocation
import com.pynapli.server.presence.PresenceService
import com.pynapli.server.profiling.profile
import com.pynapli.server.safety.BlockRepository
import com.pynapli.server.subscriptions.EntitlementPolicy
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
) {
    suspend fun queryDeck(
        viewerUserId: UUID,
        request: SwipeDeckRequestDto,
    ): SwipeDeckResponseDto {
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
        val policy =
            profile("swipe.deck.policy") {
                subscriptionService?.getPolicy(viewerUserId) ?: EntitlementPolicy.unrestricted()
            }
        if (filters.requiresAdvancedEntitlement && !policy.advancedFiltersAllowed) {
            throw SwipeException("ENTITLEMENT_REQUIRED")
        }
        val now = clock.instant()
        val scopeHash =
            SwipeDeckTokenCodec.deckScopeHash(
                viewerUserId = viewerUserId,
                radiusMeters = radiusMeters,
                limit = limit,
                filters = filters,
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
        val excludedTargetUserIds = existingSessionState?.targetUserIds.orEmpty()
        val startPosition = cursor?.nextPosition ?: 0

        val firstPass =
            profile("swipe.deck.repo.queryDeck") {
                repository.queryDeck(
                    viewerUserId = viewerUserId,
                    radiusMeters = radiusMeters,
                    limit = limit + 1,
                    filters = filters,
                    excludedTargetUserIds = excludedTargetUserIds,
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
                        )
                    }
                val merged = (firstPass.cards + secondPass.cards).distinctBy { it.userId }
                firstPass.copy(cards = merged)
            } else {
                firstPass
            }
        if (!result.viewerHadLocation) throw SwipeException("LOCATION_REQUIRED")

        val blockedRelatedIds =
            profile("swipe.deck.blocks") {
                blockRepository?.listBlockedRelatedUserIds(viewerUserId).orEmpty()
            }
        val visible =
            if (blockedRelatedIds.isEmpty()) {
                result.cards
            } else {
                result.cards.filterNot { it.userId in blockedRelatedIds }
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
            advancedFiltersAllowed = policy.advancedFiltersAllowed,
            advancedFiltersApplied = filters.requiresAdvancedEntitlement,
        )
    }
}
