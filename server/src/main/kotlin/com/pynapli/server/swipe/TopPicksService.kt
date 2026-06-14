package com.pynapli.server.swipe

import com.pynapli.server.media.MediaAssetUrlResolver
import com.pynapli.server.presence.PresenceService
import com.pynapli.server.subscriptions.EntitlementPolicy
import com.pynapli.server.subscriptions.SubscriptionService
import com.pynapli.shared.network.dto.SubscriptionTier
import com.pynapli.shared.network.dto.TopPicksResponseDto
import java.time.Clock
import java.time.Duration
import java.util.UUID

/**
 * Top Picks daily refresh (SWIPE_IMPLEMENTATION_SPEC L293-313).
 *
 * Find-or-generate flow keyed by viewer: the same curated set is returned for
 * every read inside the 24h window so the user sees stable picks until expiry.
 */
class TopPicksService(
    private val topPicksRepository: TopPicksRepository,
    swipeRepository: SwipeRepository,
    private val subscriptionService: SubscriptionService? = null,
    presenceService: PresenceService? = null,
    mediaAssetUrlResolver: MediaAssetUrlResolver? = null,
    deckTokenCodec: SwipeDeckTokenCodec = SwipeDeckTokenCodec(),
    private val clock: Clock = Clock.systemUTC(),
    refreshInterval: Duration = REFRESH_INTERVAL,
    pickRadiusMeters: Int = PICK_RADIUS_METERS,
    private val freeTierLimit: Int = FREE_TIER_LIMIT,
    private val paidTierLimit: Int = PAID_TIER_LIMIT,
    sessionIdFactory: () -> UUID = UUID::randomUUID,
    cardIdFactory: () -> UUID = UUID::randomUUID,
) {
    private val cardMapper = TopPicksCardMapper(presenceService, mediaAssetUrlResolver)
    private val generator =
        TopPicksGenerationAction(
            topPicksRepository = topPicksRepository,
            swipeRepository = swipeRepository,
            deckTokenCodec = deckTokenCodec,
            cardMapper = cardMapper,
            clock = clock,
            refreshInterval = refreshInterval,
            pickRadiusMeters = pickRadiusMeters,
            sessionIdFactory = sessionIdFactory,
            cardIdFactory = cardIdFactory,
        )
    private val cachedResponse =
        TopPicksCachedResponseAction(
            swipeRepository = swipeRepository,
            deckTokenCodec = deckTokenCodec,
            cardMapper = cardMapper,
            pickRadiusMeters = pickRadiusMeters,
        )

    suspend fun getCurrentPicks(viewerUserId: UUID): TopPicksResponseDto {
        val now = clock.instant()
        val policy = subscriptionService?.getPolicy(viewerUserId) ?: EntitlementPolicy.unrestricted()
        val tierLimit = if (policy.tier == SubscriptionTier.FREE) freeTierLimit else paidTierLimit

        val cached = topPicksRepository.findCurrentSession(viewerUserId, now)
        if (cached != null) {
            return cachedResponse.toResponse(cached, viewerUserId, tierLimit)
        }
        return generator.generateFreshSession(viewerUserId, now, tierLimit)
    }

    companion object {
        val REFRESH_INTERVAL: Duration = TOP_PICKS_REFRESH_INTERVAL
        const val PICK_RADIUS_METERS: Int = TOP_PICKS_RADIUS_METERS
        const val FREE_TIER_LIMIT: Int = TOP_PICKS_FREE_TIER_LIMIT
        const val PAID_TIER_LIMIT: Int = TOP_PICKS_PAID_TIER_LIMIT
    }
}

class TopPicksException(
    val code: String,
) : RuntimeException(code)
