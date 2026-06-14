package com.pynapli.server.dependencies

import com.pynapli.server.config.ServerConfig
import com.pynapli.server.grid.JdbcGridViewerTrustRepository
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
): CommerceTravelDependencies {
    val subscriptionService =
        SubscriptionService(
            repository = JdbcSubscriptionRepository(dataSource),
            playStorePackageName = config.playStorePackageName,
            playStorePurchaseVerifier = selectPlayStorePurchaseVerifier(config),
            playStoreSyncStaleAfter = Duration.ofHours(config.playStoreSubscriptionSyncStaleAfterHours),
            playStoreSyncRenewalLookahead = Duration.ofHours(config.playStoreSubscriptionSyncRenewalLookaheadHours),
            rateLimitService = rateLimitService,
        )
    val travelLocationService =
        TravelLocationService(
            repository = travelLocationRepository,
            subscriptionService = subscriptionService,
            // Production default is EmptyTravelRegionPolicy (no destinations require extra
            // verification). Ops swaps via DI when the region safety review is in place.
            viewerTrustRepository = JdbcGridViewerTrustRepository(dataSource),
        )
    val travelPlaceRepository = JdbcTravelPlaceRepository(dataSource)
    // One-time per dataset version (~77k rows, a few seconds); steady restarts skip it.
    TravelPlaceDatasetLoader(travelPlaceRepository).loadIfStale()
    val travelPlaceSearchService =
        TravelPlaceSearchService(
            repository = travelPlaceRepository,
            rateLimitService = rateLimitService,
        )
    val playStoreSubscriptionSyncWorker =
        if (config.playStoreVerificationEnabled && config.playStoreSubscriptionSyncEnabled) {
            PlayStoreSubscriptionSyncWorker(
                service = subscriptionService,
                intervalMillis = config.playStoreSubscriptionSyncIntervalMillis,
                batchSize = config.playStoreSubscriptionSyncBatchSize,
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
