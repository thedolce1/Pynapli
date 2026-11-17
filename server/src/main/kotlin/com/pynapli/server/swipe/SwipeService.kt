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
    private val travelLocationRepository: com.pynapli.server.location.TravelLocationRepository? = null,
    private val deckTokenCodec: SwipeDeckTokenCodec = SwipeDeckTokenCodec(),
    private val clock: Clock = Clock.systemUTC(),
    activityService: ActivityService? = null,
    private val productMetrics: com.pynapli.server.metrics.ProductMetrics? = null,
    matchConversationOpener: com.pynapli.server.messaging.MatchConversationOpener? = null,
) {
    private val dtoMapper = SwipeDtoMapper(mediaAssetUrlResolver)
    private val sideEffects = SwipeActionSideEffects(activityService, notificationPublisher, matchConversationOpener)
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

    /**
     * Like back someone who liked the viewer, from Activity. Without this a received like could
     * only become a match if that person happened to come up in the viewer's Swipe deck, so likes
     * (including ones delivered from Psync) often went nowhere. Same rules as a Swipe like: blocks,
     * risk, rate limit, the 24h travel like tail, activity, match push and match conversation.
     */
    suspend fun likeBack(
        viewerUserId: UUID,
        request: com.pynapli.shared.network.dto.LikeBackRequestDto,
    ): SwipeActionResponseDto {
        val targetUserId =
            runCatching { UUID.fromString(request.targetUserId.trim()) }.getOrNull()
                ?: throw SwipeException("INVALID_SWIPE_TARGET")
        if (targetUserId == viewerUserId) throw SwipeException("INVALID_SWIPE_TARGET")
        if (riskPolicyService?.isSocialActionRestricted(viewerUserId) == true) throw SwipeException("RISK_RESTRICTED")
        if (rateLimitService?.check(viewerUserId, com.pynapli.server.safety.RateLimitedAction.SWIPE_ACTION)?.allowed == false) {
            throw SwipeException("RATE_LIMITED")
        }
        if (blockRepository?.isBlockedEitherWay(viewerUserId, targetUserId) == true) throw SwipeException("INVALID_SWIPE_TARGET")
        val travelLocationId = travelLocationRepository?.findActive(viewerUserId)?.id
        val result =
            repository.likeBack(UUID.randomUUID(), viewerUserId, targetUserId, travelLocationId)
                ?: throw SwipeException("LIKE_BACK_NOT_AVAILABLE")
        if (result.newlyRecorded) {
            sideEffects.recordActivityForAction(viewerUserId, targetUserId, com.pynapli.shared.network.dto.SwipeAction.LIKE, result.match)
        }
        val match = result.match
        if (match != null) {
            sideEffects.notifyMatch(viewerUserId, targetUserId, match)
            if (result.newlyRecorded) productMetrics?.record(com.pynapli.server.metrics.ProductMetrics.MATCH_CREATED)
        }
        return SwipeActionResponseDto(recorded = true, match = match?.toDto(viewerUserId))
    }

    suspend fun listMatches(
        viewerUserId: UUID,
        cursorText: String? = null,
    ): MatchesListResponseDto = matchList.listMatches(viewerUserId, cursorText)
}

class SwipeException(
    val code: String,
) : RuntimeException(code)
