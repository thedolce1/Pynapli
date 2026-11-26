package com.pynapli.server.swipe

import com.pynapli.server.activity.ActivityService
import com.pynapli.server.media.MediaAssetUrlResolver
import com.pynapli.server.notifications.NotificationPublisher
import com.pynapli.server.presence.PresenceService
import com.pynapli.server.safety.BlockRepository
import com.pynapli.server.safety.RateLimitService
import com.pynapli.server.safety.RiskPolicyService
import com.pynapli.server.subscriptions.SubscriptionService
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
    // "Hide from people you know": viewer-side only (the deck, rewind and like-back of the person
    // someone hides from), never the hiding member's own deck.
    private val contactBlockVisibility: com.pynapli.server.contactblocks.ContactBlockVisibility? = null,
    // G2-2: partner minis use the Grid thumbnail when ready; photos carry stable cache keys.
    mediaImages: com.pynapli.server.media.MediaImageResolver? = null,
) {
    private val dtoMapper = SwipeDtoMapper(mediaAssetUrlResolver, mediaImages)
    private val sideEffects =
        SwipeActionSideEffects(
            repository = repository,
            delivery = MatchSideEffectDelivery(activityService, notificationPublisher, matchConversationOpener),
            clock = clock,
        )
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
            contactBlockVisibility = contactBlockVisibility,
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
            contactBlockVisibility = contactBlockVisibility,
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
            contactBlockVisibility = contactBlockVisibility,
        )

    suspend fun queryDeck(
        viewerUserId: UUID,
        request: SwipeDeckRequestDto,
    ): SwipeDeckResponseDto = deckQuery.queryDeck(viewerUserId, request)

    /**
     * The ranked deck a Psync session is dealt from. Unlike [queryDeck] it does not leave out people
     * hiding from [viewerUserId] ("Hide from people you know"): the deck is shared with the partner, and
     * each partner's view of it leaves out the people hiding from them (PsyncDeckCoordinator).
     */
    internal suspend fun queryDeckForPsync(
        viewerUserId: UUID,
        request: SwipeDeckRequestDto,
    ): SwipeDeckResponseDto = deckQuery.queryDeck(viewerUserId, request, applyContactBlocks = false)

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
        // Someone hiding from the viewer: the same answer as when there is no like to answer.
        if (contactBlockVisibility?.isHiddenFrom(viewerUserId, targetUserId) == true) throw SwipeException("LIKE_BACK_NOT_AVAILABLE")
        val travelLocationId = travelLocationRepository?.findActive(viewerUserId)?.id
        val result =
            repository.likeBack(UUID.randomUUID(), viewerUserId, targetUserId, travelLocationId)
                ?: throw SwipeException("LIKE_BACK_NOT_AVAILABLE")
        contactBlockVisibility?.noteActToward(viewerUserId, targetUserId)
        sideEffects.deliver(result, actorUserId = viewerUserId, targetUserId = targetUserId, action = com.pynapli.shared.network.dto.SwipeAction.LIKE)
        val match = result.match
        if (match != null && result.newlyRecorded) productMetrics?.record(com.pynapli.server.metrics.ProductMetrics.MATCH_CREATED)
        return SwipeActionResponseDto(recorded = true, match = match?.toDto(viewerUserId))
    }
}

class SwipeException(
    val code: String,
) : RuntimeException(code)
