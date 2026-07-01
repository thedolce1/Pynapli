package com.pynapli.server

import com.pynapli.server.account.configureAccountLifecycleRoutes
import com.pynapli.server.activity.configureActivityRoutes
import com.pynapli.server.activity.configureActivityStreamRoutes
import com.pynapli.server.admin.configureAdminPartnerLinkReviewRoutes
import com.pynapli.server.admin.configureAdminSubscriptionSupportRoutes
import com.pynapli.server.admin.configureAdminUserLookupRoutes
import com.pynapli.server.auth.configureAuthRoutes
import com.pynapli.server.config.ServerConfig
import com.pynapli.server.dependencies.ServerDependencies
import com.pynapli.server.dependencies.closeDependenciesOnStop
import com.pynapli.server.grid.configureGridRoutes
import com.pynapli.server.grid.configureSavedGridFilterRoutes
import com.pynapli.server.location.configureLocationRoutes
import com.pynapli.server.location.configureTravelLocationRoutes
import com.pynapli.server.media.configureMediaRoutes
import com.pynapli.server.media.configureRequestMediaBaseUrl
import com.pynapli.server.messaging.configureMessageStreamRoutes
import com.pynapli.server.messaging.configureMessagingRoutes
import com.pynapli.server.metrics.configureProductMetricsRoutes
import com.pynapli.server.moderation.configureAdminAppealRoutes
import com.pynapli.server.moderation.configureAdminModerationRoutes
import com.pynapli.server.moderation.configureAdminReportRoutes
import com.pynapli.server.moderation.configureAdminRiskRoutes
import com.pynapli.server.notifications.configureAdminMetricsRoutes
import com.pynapli.server.notifications.configureNotificationRoutes
import com.pynapli.server.plugins.configureAppCheck
import com.pynapli.server.plugins.configureHTTP
import com.pynapli.server.plugins.configureMonitoring
import com.pynapli.server.plugins.configureSerialization
import com.pynapli.server.plugins.configureStatusPages
import com.pynapli.server.plugins.selectAppCheckVerifier
import com.pynapli.server.presence.configurePresenceRoutes
import com.pynapli.server.presence.configurePresenceStreamRoutes
import com.pynapli.server.profile.configureProfileRoutes
import com.pynapli.server.profile.configureTonightAvailabilityRoutes
import com.pynapli.server.profilelink.configureProfileLinkRoutes
import com.pynapli.server.psync.configurePsyncRoutes
import com.pynapli.server.psync.configurePsyncStreamRoutes
import com.pynapli.server.routing.configureHealthRoutes
import com.pynapli.server.safety.configureAppealRoutes
import com.pynapli.server.safety.configureSafetyRoutes
import com.pynapli.server.subscriptions.configurePlayStoreRtdnRoutes
import com.pynapli.server.subscriptions.configureSubscriptionAdminRoutes
import com.pynapli.server.subscriptions.configureSubscriptionRoutes
import com.pynapli.server.swipe.configureSavedSwipeFilterRoutes
import com.pynapli.server.swipe.configureSwipeRoutes
import com.pynapli.server.swipe.configureTopPicksRoutes
import com.pynapli.server.tonight.configureTonightFeedRoutes
import com.pynapli.server.tonight.configureTonightSessionRoutes
import com.pynapli.server.verification.configureAgeVerificationAdminRoutes
import com.pynapli.server.verification.configureAgeVerificationRoutes
import io.ktor.server.application.Application
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty

fun main() {
    val config = ServerConfig.fromEnvironment()
    val dependencies = ServerDependencies.fromEnvironment(config)

    embeddedServer(
        factory = Netty,
        port = config.port,
        module = {
            module(dependencies)
        },
    ).start(wait = true)
}

fun Application.module(dependencies: ServerDependencies = ServerDependencies.fromEnvironment()) {
    closeDependenciesOnStop(dependencies)
    dependencies.notificationDispatchWorker?.start()
    dependencies.activityRetentionWorker?.start()
    dependencies.mediaCleanupWorker?.start()
    dependencies.tonightSessionExpiryWorker?.start()
    dependencies.playStoreSubscriptionSyncWorker?.start()
    dependencies.presenceOfflineScanner?.start()
    configureSerialization()
    configureStatusPages()
    configureMonitoring()
    configureHTTP()
    configureRequestMediaBaseUrl()
    configureAppCheck(dependencies.config.appCheckEnforced, selectAppCheckVerifier(dependencies.config))
    configureHealthRoutes(dependencies)
    configureAdminAppealRoutes(dependencies.config, dependencies.adminAppealService)
    configureAdminModerationRoutes(dependencies.config, dependencies.messageModerationService)
    configureAdminReportRoutes(dependencies.config, dependencies.adminReportService)
    configureAdminRiskRoutes(dependencies.config, dependencies.adminRiskService)
    configureAdminMetricsRoutes(dependencies.config, dependencies.notificationDispatchWorker)
    configureProductMetricsRoutes(dependencies.config, dependencies.productMetrics)
    configureAdminUserLookupRoutes(dependencies.config, dependencies.adminUserLookupService)
    configureAdminPartnerLinkReviewRoutes(dependencies.config, dependencies.adminPartnerLinkReviewService)
    configureAdminSubscriptionSupportRoutes(dependencies.config, dependencies.adminSubscriptionSupportService)
    configureAgeVerificationAdminRoutes(dependencies.config, dependencies.ageVerificationService)
    configureSubscriptionAdminRoutes(dependencies.config, dependencies.subscriptionService)
    configurePlayStoreRtdnRoutes(dependencies.config, dependencies.subscriptionService)
    configureAuthRoutes(dependencies.authService)
    configureAccountLifecycleRoutes(dependencies.authService, dependencies.accountLifecycleService)
    configureProfileRoutes(dependencies.authService, dependencies.profileService, dependencies.requestProfilerFactory)
    configureAgeVerificationRoutes(dependencies.authService, dependencies.ageVerificationService)
    configureProfileLinkRoutes(dependencies.authService, dependencies.profileLinkService)
    configureLocationRoutes(dependencies.authService, dependencies.locationService)
    configureTravelLocationRoutes(
        dependencies.authService,
        dependencies.travelLocationService,
        dependencies.travelPlaceSearchService,
    )
    configurePresenceRoutes(dependencies.authService, dependencies.presenceService, dependencies.requestProfilerFactory)
    configurePresenceStreamRoutes(
        authService = dependencies.authService,
        broadcaster = dependencies.presenceBroadcaster,
        blockRepository = dependencies.blockRepository,
    )
    configureGridRoutes(dependencies.authService, dependencies.gridService, dependencies.requestProfilerFactory)
    configureSavedGridFilterRoutes(dependencies.authService, dependencies.savedGridFilterService)
    configureTonightAvailabilityRoutes(dependencies.authService, dependencies.tonightAvailabilityService)
    configureTonightSessionRoutes(dependencies.authService, dependencies.tonightSessionService)
    configureTonightFeedRoutes(dependencies.authService, dependencies.tonightFeedService)
    configurePsyncRoutes(dependencies.authService, dependencies.psyncSessionService)
    configurePsyncStreamRoutes(dependencies.authService, dependencies.psyncBroadcaster)
    configureActivityRoutes(dependencies.authService, dependencies.activityService)
    configureActivityStreamRoutes(dependencies.authService, dependencies.activityBroadcaster)
    configureNotificationRoutes(dependencies.authService, dependencies.notificationService)
    configureSubscriptionRoutes(dependencies.config, dependencies.authService, dependencies.subscriptionService)
    configureMessagingRoutes(dependencies.authService, dependencies.messagingService)
    configureMessageStreamRoutes(dependencies.authService, dependencies.messageBroadcaster)
    configureMediaRoutes(
        authService = dependencies.authService,
        mediaService = dependencies.mediaService,
        localUploadStorage = dependencies.localMediaUploadStorage,
    )
    configureSafetyRoutes(
        authService = dependencies.authService,
        blockService = dependencies.blockService,
        hiddenProfileService = dependencies.hiddenProfileService,
        reportService = dependencies.reportService,
    )
    configureAppealRoutes(dependencies.authService, dependencies.appealService)
    configureSwipeRoutes(
        authService = dependencies.authService,
        swipeService = dependencies.swipeService,
        requestProfilerFactory = dependencies.requestProfilerFactory,
    )
    configureSavedSwipeFilterRoutes(dependencies.authService, dependencies.savedSwipeFilterService)
    configureTopPicksRoutes(
        authService = dependencies.authService,
        topPicksService = dependencies.topPicksService,
        requestProfilerFactory = dependencies.requestProfilerFactory,
    )
}
