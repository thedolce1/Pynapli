package com.pynapli.server.dependencies

import com.pynapli.server.activity.ActivityService
import com.pynapli.server.config.ServerConfig
import com.pynapli.server.db.PostgresJobLock
import com.pynapli.server.location.TravelLocationRepository
import com.pynapli.server.media.MediaAssetUrlResolver
import com.pynapli.server.notifications.NotificationService
import com.pynapli.server.presence.PresenceService
import com.pynapli.server.psync.JdbcPsyncSessionRepository
import com.pynapli.server.psync.PsyncBroadcaster
import com.pynapli.server.psync.PsyncSessionExpiryWorker
import com.pynapli.server.psync.PsyncSessionService
import com.pynapli.server.psync.SwipeServicePsyncDeckGenerator
import com.pynapli.server.safety.BlockRepository
import com.pynapli.server.safety.HiddenProfileRepository
import com.pynapli.server.safety.RateLimitService
import com.pynapli.server.safety.RiskPolicyService
import com.pynapli.server.subscriptions.SubscriptionService
import com.pynapli.server.swipe.JdbcSwipeRepository
import com.pynapli.server.swipe.JdbcTopPicksRepository
import com.pynapli.server.swipe.MatchSideEffectDelivery
import com.pynapli.server.swipe.MatchSideEffectDrainWorker
import com.pynapli.server.swipe.MatchSideEffectDrainer
import com.pynapli.server.swipe.SwipeDeckTokenCodec
import com.pynapli.server.swipe.SwipeService
import com.pynapli.server.swipe.TopPicksService
import javax.sql.DataSource

/**
 * Swipe subsystem wiring extracted from `ServerDependencies.fromEnvironment` to keep the DI root a
 * thin orchestrator. Builds the swipe service, the Psync session service (whose deck generator wraps
 * the swipe service), and the top-picks service; all three are consumed only by the
 * `ServerDependencies` constructor, so the shared upstream dependencies are threaded in as
 * parameters. Behavior matches the original inline construction.
 */
internal class SwipeDependencies(
    val swipeService: SwipeService,
    val psyncSessionService: PsyncSessionService,
    val psyncSessionExpiryWorker: PsyncSessionExpiryWorker,
    val topPicksService: TopPicksService,
    val matchCelebrationService: com.pynapli.server.swipe.MatchCelebrationService,
    val matchSideEffectDrainWorker: MatchSideEffectDrainWorker,
)

internal fun buildSwipeDependencies(
    config: ServerConfig,
    dataSource: DataSource,
    mediaAssetUrlResolver: MediaAssetUrlResolver?,
    blockRepository: BlockRepository,
    hiddenProfileRepository: HiddenProfileRepository,
    notificationService: NotificationService,
    rateLimitService: RateLimitService,
    riskPolicyService: RiskPolicyService,
    subscriptionService: SubscriptionService,
    presenceService: PresenceService,
    travelLocationRepository: TravelLocationRepository,
    activityService: ActivityService,
    psyncBroadcaster: PsyncBroadcaster,
    productMetrics: com.pynapli.server.metrics.ProductMetrics,
    matchConversationOpener: com.pynapli.server.messaging.MatchConversationOpener? = null,
): SwipeDependencies {
    val swipeRepository = JdbcSwipeRepository(dataSource)
    val swipeService =
        SwipeService(
            repository = swipeRepository,
            mediaAssetUrlResolver = mediaAssetUrlResolver,
            blockRepository = blockRepository,
            notificationPublisher = notificationService,
            rateLimitService = rateLimitService,
            riskPolicyService = riskPolicyService,
            subscriptionService = subscriptionService,
            presenceService = presenceService,
            travelLocationRepository = travelLocationRepository,
            deckTokenCodec = SwipeDeckTokenCodec(config.swipeDeckTokenSecret),
            activityService = activityService,
            productMetrics = productMetrics,
            matchConversationOpener = matchConversationOpener,
        )
    val psyncSessionService =
        PsyncSessionService(
            repository = JdbcPsyncSessionRepository(dataSource),
            deckGenerator = SwipeServicePsyncDeckGenerator(swipeService),
            blockRepository = blockRepository,
            hiddenProfileRepository = hiddenProfileRepository,
            presenceService = presenceService,
            mediaAssetUrlResolver = mediaAssetUrlResolver,
            travelLocationRepository = travelLocationRepository,
            matchConversationOpener = matchConversationOpener,
            activityService = activityService,
            notificationPublisher = notificationService,
            broadcaster = psyncBroadcaster,
            rateLimitService = rateLimitService,
            riskPolicyService = riskPolicyService,
            subscriptionService = subscriptionService,
        )
    val topPicksService =
        TopPicksService(
            topPicksRepository = JdbcTopPicksRepository(dataSource),
            swipeRepository = swipeRepository,
            subscriptionService = subscriptionService,
            presenceService = presenceService,
            mediaAssetUrlResolver = mediaAssetUrlResolver,
            deckTokenCodec = SwipeDeckTokenCodec(config.swipeDeckTokenSecret),
        )
    return SwipeDependencies(
        swipeService = swipeService,
        psyncSessionService = psyncSessionService,
        // Closes out sessions nobody comes back to (abandoned mid-deck, partner link ended) so
        // their likes are still delivered. Requests do the same sweep, so 5 minutes is plenty.
        psyncSessionExpiryWorker = PsyncSessionExpiryWorker(psyncSessionService, intervalMillis = 5 * 60 * 1000L),
        topPicksService = topPicksService,
        matchCelebrationService =
            com.pynapli.server.swipe.MatchCelebrationService(
                repository = com.pynapli.server.swipe.JdbcMatchCelebrationRepository(dataSource),
                mediaAssetUrlResolver = mediaAssetUrlResolver,
            ),
        // Delivers match side effects (Activity rows, match conversation, match push) whose request
        // died between its commit and its fast-path delivery. One replica per tick.
        matchSideEffectDrainWorker =
            MatchSideEffectDrainWorker(
                drainer =
                    MatchSideEffectDrainer(
                        dataSource = dataSource,
                        delivery = MatchSideEffectDelivery(activityService, notificationService, matchConversationOpener),
                    ),
                intervalMillis = 30 * 1000L,
                jobLock = PostgresJobLock(dataSource),
            ),
    )
}
