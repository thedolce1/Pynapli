package com.pynapli.server.swipe

import com.pynapli.server.activity.ActivityService
import com.pynapli.server.media.MediaAssetUrlResolver
import com.pynapli.server.notifications.NotificationPublisher
import com.pynapli.server.presence.PresenceService
import com.pynapli.server.safety.BlockRepository
import com.pynapli.server.safety.RateLimitService
import com.pynapli.server.safety.RiskPolicyService
import com.pynapli.server.subscriptions.SubscriptionService
import com.pynapli.shared.network.dto.MatchesListResponseDto
import com.pynapli.shared.network.dto.SwipeActionRequestDto
import com.pynapli.shared.network.dto.SwipeActionResponseDto
import com.pynapli.shared.network.dto.SwipeDeckRequestDto
import com.pynapli.shared.network.dto.SwipeDeckResponseDto
import com.pynapli.shared.network.dto.SwipeRewindResponseDto
import java.time.Clock
import java.util.UUID

class SwipeService(
    private val repository: SwipeRepository,
    mediaAssetUrlResolver: MediaAssetUrlResolver? = null,
    private val blockRepository: BlockRepository? = null,
    notificationPublisher: NotificationPublisher? = null,
    private val rateLimitService: RateLimitService? = null,
    private val riskPolicyService: RiskPolicyService? = null,
    private val subscriptionService: SubscriptionService? = null,
    private val presenceService: PresenceService? = null,
    @Suppress("unused") private val travelLocationRepository: com.pynapli.server.location.TravelLocationRepository? = null,
    private val deckTokenCodec: SwipeDeckTokenCodec = SwipeDeckTokenCodec(),
    private val clock: Clock = Clock.systemUTC(),
    activityService: ActivityService? = null,
    private val productMetrics: com.pynapli.server.metrics.ProductMetrics? = null,
) {
    private val dtoMapper = SwipeDtoMapper(mediaAssetUrlResolver)
    private val sideEffects = SwipeActionSideEffects(activityService, notificationPublisher)
    private val deckPageBuilder = SwipeDeckPageBuilder(deckTokenCodec, dtoMapper)
    private val deckQuery =
        SwipeDeckQueryAction(
            repository = repository,
            blockRepository = blockRepository,
            subscriptionService = subscriptionService,
            presenceService = presenceService,
            deckTokenCodec = deckTokenCodec,
            deckPageBuilder = deckPageBuilder,
            clock = clock,
        )
    private val submitAction =
        SwipeSubmitAction(
            repository = repository,
            sideEffects = sideEffects,
            deckTokenCodec = deckTokenCodec,
            rateLimitService = rateLimitService,
            riskPolicyService = riskPolicyService,
            subscriptionService = subscriptionService,
            clock = clock,
        )
    private val rewindAction =
        SwipeRewindAction(
            repository = repository,
            dtoMapper = dtoMapper,
            blockRepository = blockRepository,
            rateLimitService = rateLimitService,
            riskPolicyService = riskPolicyService,
            subscriptionService = subscriptionService,
            presenceService = presenceService,
            deckTokenCodec = deckTokenCodec,
            clock = clock,
        )
    private val matchList =
        SwipeMatchListAction(
            repository = repository,
            dtoMapper = dtoMapper,
            blockRepository = blockRepository,
            riskPolicyService = riskPolicyService,
        )

    suspend fun queryDeck(
        viewerUserId: UUID,
        request: SwipeDeckRequestDto,
    ): SwipeDeckResponseDto = deckQuery.queryDeck(viewerUserId, request)

    suspend fun submitAction(
        viewerUserId: UUID,
        request: SwipeActionRequestDto,
    ): SwipeActionResponseDto {
        val response = submitAction.submitAction(viewerUserId, request)
        // Authoritative match KPI (ProductMetrics): a mutual like just created a match.
        if (response.match != null) productMetrics?.record(com.pynapli.server.metrics.ProductMetrics.MATCH_CREATED)
        return response
    }

    suspend fun rewind(viewerUserId: UUID): SwipeRewindResponseDto = rewindAction.rewind(viewerUserId)

    suspend fun listMatches(viewerUserId: UUID): MatchesListResponseDto = matchList.listMatches(viewerUserId)
}

class SwipeException(
    val code: String,
) : RuntimeException(code)
