package com.pynapli.server.dependencies

import com.pynapli.server.config.ServerConfig
import com.pynapli.server.db.PostgresJobLock
import com.pynapli.server.grid.JdbcGridViewerTrustRepository
import com.pynapli.server.location.ConfiguredTravelRegionPolicy
import com.pynapli.server.location.JdbcTravelPlaceRepository
import com.pynapli.server.location.TravelLocationRepository
import com.pynapli.server.location.TravelLocationService
import com.pynapli.server.location.TravelPlaceDatasetLoader
import com.pynapli.server.location.TravelPlaceSearchService
import com.pynapli.server.safety.RateLimitService
import com.pynapli.server.subscriptions.JdbcSubscriptionRepository
import com.pynapli.server.subscriptions.PlayStoreSubscriptionSyncWorker
import com.pynapli.server.subscriptions.SubscriptionService
import com.pynapli.server.subscriptions.selectPlayStorePurchaseVerifier
import java.time.Duration
import javax.sql.DataSource

internal class CommerceTravelDependencies(
    val subscriptionService: SubscriptionService,
    val travelLocationService: TravelLocationService,
    val travelPlaceSearchService: TravelPlaceSearchService,
    val playStoreSubscriptionSyncWorker: PlayStoreSubscriptionSyncWorker?,
)

internal fun buildCommerceTravelDependencies(
    config: ServerConfig,
    dataSource: DataSource,
    travelLocationRepository: TravelLocationRepository,
    rateLimitService: RateLimitService,
    productMetrics: com.pynapli.server.metrics.ProductMetrics,
    riskService: com.pynapli.server.safety.RiskService? = null,
    riskPolicyService: com.pynapli.server.safety.RiskPolicyService? = null,
): CommerceTravelDependencies {
    val subscriptionService =
        SubscriptionService(
            repository = JdbcSubscriptionRepository(dataSource),
            playStorePackageName = config.playStorePackageName,
            playStorePurchaseVerifier = selectPlayStorePurchaseVerifier(config),
            playStoreSyncStaleAfter = Duration.ofHours(config.playStoreSubscriptionSyncStaleAfterHours),
            playStoreSyncRenewalLookahead = Duration.ofHours(config.playStoreSubscriptionSyncRenewalLookaheadHours),
            rateLimitService = rateLimitService,
            productMetrics = productMetrics,
        )
    // Destination rules come from PYNAPLI_TRAVEL_REGION_POLICY (empty = none); a malformed value
    // fails startup instead of silently allowing a destination ops meant to restrict.
    val travelRegionPolicy = ConfiguredTravelRegionPolicy.parse(config.travelRegionPolicy)
    val travelPlaceRepository = JdbcTravelPlaceRepository(dataSource)
    val travelLocationService =
        TravelLocationService(
            repository = travelLocationRepository,
            subscriptionService = subscriptionService,
            regionPolicy = travelRegionPolicy,
            viewerTrustRepository = JdbcGridViewerTrustRepository(dataSource),
            placeRepository = travelPlaceRepository,
            riskPolicyService = riskPolicyService,
            riskSignals = riskService,
        )
    // One-time per dataset version (~77k rows, a few seconds); steady restarts skip it.
    TravelPlaceDatasetLoader(travelPlaceRepository).loadIfStale()
    val travelPlaceSearchService =
        TravelPlaceSearchService(
            repository = travelPlaceRepository,
            rateLimitService = rateLimitService,
            regionPolicy = travelRegionPolicy,
        )
    val playStoreSubscriptionSyncWorker =
        if (config.playStoreVerificationEnabled && config.playStoreSubscriptionSyncEnabled) {
            PlayStoreSubscriptionSyncWorker(
                service = subscriptionService,
                intervalMillis = config.playStoreSubscriptionSyncIntervalMillis,
                batchSize = config.playStoreSubscriptionSyncBatchSize,
                jobLock = PostgresJobLock(dataSource),
            )
        } else {
            null
        }

    return CommerceTravelDependencies(
        subscriptionService = subscriptionService,
        travelLocationService = travelLocationService,
        travelPlaceSearchService = travelPlaceSearchService,
        playStoreSubscriptionSyncWorker = playStoreSubscriptionSyncWorker,
    )
}
