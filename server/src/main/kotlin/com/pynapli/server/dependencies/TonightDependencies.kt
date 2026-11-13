package com.pynapli.server.dependencies

import com.pynapli.server.db.PostgresJobLock
import com.pynapli.server.media.MediaAssetUrlResolver
import com.pynapli.server.notifications.NotificationService
import com.pynapli.server.presence.PresenceService
import com.pynapli.server.profile.TonightAvailabilityService
import com.pynapli.server.safety.BlockRepository
import com.pynapli.server.safety.HiddenProfileRepository
import com.pynapli.server.safety.RiskPolicyService
import com.pynapli.server.subscriptions.SubscriptionService
import com.pynapli.server.tonight.JdbcTonightFeedRepository
import com.pynapli.server.tonight.JdbcTonightSessionRepository
import com.pynapli.server.tonight.TonightFeedService
import com.pynapli.server.tonight.TonightSessionExpiryWorker
import com.pynapli.server.tonight.TonightSessionService
import javax.sql.DataSource

/**
 * Playing-Tonight subsystem wiring extracted from `ServerDependencies.fromEnvironment` to keep the
 * DI root a thin orchestrator. Builds the availability/session/feed services and the expiry worker;
 * all four are consumed only by the `ServerDependencies` constructor, so the shared upstream
 * dependencies are threaded in as parameters. Behavior matches the original inline construction.
 */
internal class TonightDependencies(
    val tonightAvailabilityService: TonightAvailabilityService,
    val tonightSessionService: TonightSessionService,
    val tonightFeedService: TonightFeedService,
    val tonightSessionExpiryWorker: TonightSessionExpiryWorker,
)

internal fun buildTonightDependencies(
    dataSource: DataSource,
    subscriptionService: SubscriptionService,
    notificationService: NotificationService,
    presenceService: PresenceService,
    mediaAssetUrlResolver: MediaAssetUrlResolver?,
    blockRepository: BlockRepository,
    hiddenProfileRepository: HiddenProfileRepository,
    riskPolicyService: RiskPolicyService,
): TonightDependencies {
    val tonightAvailabilityService = TonightAvailabilityService(dataSource)
    val tonightSessionService =
        TonightSessionService(
            repository = JdbcTonightSessionRepository(dataSource),
            subscriptionService = subscriptionService,
            notificationPublisher = notificationService,
            presenceService = presenceService,
            riskPolicyService = riskPolicyService,
        )
    val tonightFeedService =
        TonightFeedService(
            repository = JdbcTonightFeedRepository(dataSource),
            presenceService = presenceService,
            mediaAssetUrlResolver = mediaAssetUrlResolver,
            blockRepository = blockRepository,
            hiddenProfileRepository = hiddenProfileRepository,
        )
    // Hygiene sweep so elapsed sessions move to 'expired' (reads already filter on
    // expires_at, so this is not load-bearing for correctness). Default every 5 minutes.
    // One replica per tick (PostgresJobLock).
    val tonightSessionExpiryWorker =
        TonightSessionExpiryWorker(tonightSessionService, intervalMillis = 5 * 60 * 1000L, jobLock = PostgresJobLock(dataSource))
    return TonightDependencies(
        tonightAvailabilityService = tonightAvailabilityService,
        tonightSessionService = tonightSessionService,
        tonightFeedService = tonightFeedService,
        tonightSessionExpiryWorker = tonightSessionExpiryWorker,
    )
}
