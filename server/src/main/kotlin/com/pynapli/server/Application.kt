package com.pynapli.server

import com.pynapli.server.account.configureAccountLifecycleRoutes
import com.pynapli.server.activity.configureActivityRoutes
import com.pynapli.server.activity.configureActivityStreamRoutes
import com.pynapli.server.admin.configureAdminDashboardRoutes
import com.pynapli.server.admin.configureAdminMediaReviewRoutes
import com.pynapli.server.admin.configureAdminPartnerLinkReviewRoutes
import com.pynapli.server.admin.configureAdminSubscriptionSupportRoutes
import com.pynapli.server.admin.configureAdminUserLookupRoutes
import com.pynapli.server.auth.configureAuthRoutes
import com.pynapli.server.auth.configureEmailSignupRoutes
import com.pynapli.server.config.ServerConfig
import com.pynapli.server.dependencies.ServerDependencies
import com.pynapli.server.dependencies.closeDependenciesOnStop
import com.pynapli.server.favorites.configureFavoriteRoutes
import com.pynapli.server.grid.configureGridRoutes
import com.pynapli.server.grid.configureSavedGridFilterRoutes
import com.pynapli.server.location.configureLocationRoutes
import com.pynapli.server.location.configureTravelLocationRoutes
import com.pynapli.server.media.configureMediaRoutes
import com.pynapli.server.media.configureRequestMediaBaseUrl
import com.pynapli.server.messaging.configureMessageStreamRoutes
import com.pynapli.server.messaging.configureMessagingRoutes
import com.pynapli.server.metrics.OperationalMetricsSources
import com.pynapli.server.metrics.configureProductMetricsRoutes
import com.pynapli.server.metrics.configurePrometheusMetrics
import com.pynapli.server.moderation.configureAdminAppealRoutes
import com.pynapli.server.moderation.configureAdminModerationRoutes
import com.pynapli.server.moderation.configureAdminReportRoutes
import com.pynapli.server.moderation.configureAdminRiskRoutes
import com.pynapli.server.notifications.configureAdminMetricsRoutes
import com.pynapli.server.notifications.configureNotificationRoutes
import com.pynapli.server.plugins.LogFormat
import com.pynapli.server.plugins.configureAdminAudit
import com.pynapli.server.plugins.configureAppCheck
import com.pynapli.server.plugins.configureHTTP
import com.pynapli.server.plugins.configureMonitoring
import com.pynapli.server.plugins.configureSecurityHeaders
import com.pynapli.server.plugins.configureSerialization
import com.pynapli.server.plugins.configureStatusPages
import com.pynapli.server.plugins.selectAppCheckVerifier
import com.pynapli.server.presence.configurePresenceStreamRoutes
import com.pynapli.server.profile.configureProfileRoutes
import com.pynapli.server.profilelink.configureProfileLinkRoutes
import com.pynapli.server.psync.configurePsyncRoutes
import com.pynapli.server.psync.configurePsyncStreamRoutes
import com.pynapli.server.routing.configureHealthRoutes
import com.pynapli.server.safety.configureAppealRoutes
import com.pynapli.server.safety.configureRequestLimits
import com.pynapli.server.safety.configureSafetyRoutes
import com.pynapli.server.subscriptions.configurePlayStoreRtdnRoutes
import com.pynapli.server.subscriptions.configureSubscriptionAdminRoutes
import com.pynapli.server.subscriptions.configureSubscriptionRoutes
import com.pynapli.server.swipe.configureMatchCelebrationRoutes
import com.pynapli.server.swipe.configureSavedSwipeFilterRoutes
import com.pynapli.server.swipe.configureSwipeRoutes
import com.pynapli.server.tonight.configureTonightFeedRoutes
import com.pynapli.server.tonight.configureTonightSessionRoutes
import com.pynapli.server.verification.configureAdminPhotoVerificationRoutes
import com.pynapli.server.verification.configureAgeVerificationAdminRoutes
import com.pynapli.server.verification.configureAgeVerificationCallbackRoute
import com.pynapli.server.verification.configureAgeVerificationRoutes
import com.pynapli.server.verification.configurePhotoVerificationRoutes
import io.ktor.server.application.Application
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty

fun main() {
    LogFormat.apply()
    val config = ServerConfig.fromEnvironment()
    val dependencies = ServerDependencies.fromEnvironment(config)

    val server =
        embeddedServer(
            factory = Netty,
            port = config.port,
            module = {
                module(dependencies)
            },
        )
    // Cloud Run sends SIGTERM and allows ~10 s: stop taking new requests, let in-flight ones finish
    // for up to SHUTDOWN_GRACE_MS, then stop (which closes workers and pools via closeDependenciesOnStop).
    Runtime.getRuntime().addShutdownHook(
        Thread {
            server.stop(gracePeriodMillis = SHUTDOWN_GRACE_MS, timeoutMillis = SHUTDOWN_TIMEOUT_MS)
        },
    )
    server.start(wait = true)
}

fun Application.module(dependencies: ServerDependencies = ServerDependencies.fromEnvironment()) {
    closeDependenciesOnStop(dependencies)
    dependencies.notificationDispatchWorker?.start()
    dependencies.activityRetentionWorker?.start()
    dependencies.dataRetentionWorker?.start()
    dependencies.mediaCleanupWorker?.start()
    dependencies.photoVerificationRetentionWorker?.start()
    dependencies.tonightSessionExpiryWorker.start()
    dependencies.psyncSessionExpiryWorker?.start()
    dependencies.matchSideEffectDrainWorker?.start()
    dependencies.psyncPresenceWatcher?.start()
    dependencies.travelTripWorker?.start()
    dependencies.accountDataExportWorker?.start()
    dependencies.playStoreSubscriptionSyncWorker?.start()
    dependencies.presenceOfflineScanner.start()
    configureSerialization()
    configureStatusPages()
    configureMonitoring()
    configureHTTP(dependencies.config.corsAllowedOrigins)
    configureSecurityHeaders(dependencies.config.environment)
    dependencies.rateLimitService?.let {
        configureRequestLimits(
            rateLimitService = it,
            trustedProxyHops = dependencies.config.trustedProxyHops,
            perIpLimits = dependencies.config.environment != "local",
            // Signed-in limits follow the account, so a fresh sign-in doesn't reset them.
            resolveUserId = { token -> runCatching { dependencies.authService.authenticate(token).id }.getOrNull() },
        )
    }
    configureRequestMediaBaseUrl()
    configureAppCheck(dependencies.config.appCheckEnforced, selectAppCheckVerifier(dependencies.config))
    configureAdminAudit(dependencies.adminRequestAuditRecorder)
    configureHealthRoutes(dependencies.config.environment, dependencies::checkAll)
    configurePrometheusMetrics(
        token = dependencies.config.metricsToken,
        sources =
            OperationalMetricsSources(
                poolSnapshot = dependencies.database::poolSnapshot,
                realtimeSubscribers =
                    mapOf(
                        "messages" to { dependencies.messageBroadcaster.subscriptionCount.value },
                        "activity" to { dependencies.activityBroadcaster.subscriptionCount.value },
                        "presence" to { dependencies.presenceBroadcaster.subscriptionCount.value },
                        "psync" to { dependencies.psyncBroadcaster.subscriptionCount.value },
                    ),
                dataSource = dependencies.database.dataSource,
                gauges =
                    dependencies.rateLimitService
                        ?.outageFallback
                        ?.let { fallback ->
                            mapOf(
                                "pynapli_rate_limit_fallback_active" to ("1 while Redis rate limiting is down and in-process limits apply." to { if (fallback.active) 1 else 0 }),
                                "pynapli_rate_limit_fallback_checks" to ("Rate-limit checks answered in-process since start." to { fallback.fallbackChecks }),
                            )
                        }.orEmpty(),
            ),
    )
    configureAdminAppealRoutes(dependencies.config, dependencies.adminAppealService)
    configureAdminModerationRoutes(dependencies.config, dependencies.messageModerationService)
    configureAdminReportRoutes(dependencies.config, dependencies.adminReportService)
    configureAdminRiskRoutes(dependencies.config, dependencies.adminRiskService, dependencies.authService::invalidateCachedSessionsForUser)
    configureAdminMetricsRoutes(dependencies.config, dependencies.notificationDispatchWorker)
    configureProductMetricsRoutes(dependencies.config, dependencies.productMetrics)
    configureAdminUserLookupRoutes(dependencies.config, dependencies.adminUserLookupService)
    configureAdminPartnerLinkReviewRoutes(dependencies.config, dependencies.adminPartnerLinkReviewService)
    configureAdminSubscriptionSupportRoutes(dependencies.config, dependencies.adminSubscriptionSupportService)
    configureAdminDashboardRoutes(dependencies.config, dependencies.adminDashboardService)
    configureAdminMediaReviewRoutes(dependencies.config, dependencies.adminMediaReviewService)
    configureAgeVerificationAdminRoutes(dependencies.config, dependencies.ageVerificationService)
    configureAgeVerificationCallbackRoute(dependencies.config, dependencies.ageVerificationService)
    configureAdminPhotoVerificationRoutes(dependencies.config, dependencies.photoVerificationService)
    configureSubscriptionAdminRoutes(dependencies.config, dependencies.subscriptionService)
    configurePlayStoreRtdnRoutes(dependencies.config, dependencies.subscriptionService)
    configureAuthRoutes(
        dependencies.authService,
        dependencies.passwordRecoveryService,
        dependencies.signedInDevicesService,
        trustedProxyHops = dependencies.config.trustedProxyHops,
    )
    dependencies.emailSignupService?.let { signup ->
        configureEmailSignupRoutes(
            signupService = signup,
            trustedProxyHops = dependencies.config.trustedProxyHops,
            devMailbox = dependencies.devSignupMailbox,
        )
    }
    configureAccountLifecycleRoutes(dependencies.authService, dependencies.accountLifecycleService)
    configureProfileRoutes(dependencies.authService, dependencies.profileService, dependencies.requestProfilerFactory)
    configureAgeVerificationRoutes(dependencies.authService, dependencies.ageVerificationService)
    configurePhotoVerificationRoutes(dependencies.authService, dependencies.photoVerificationService)
    configureProfileLinkRoutes(dependencies.authService, dependencies.profileLinkService)
    configureLocationRoutes(dependencies.authService, dependencies.locationService)
    configureTravelLocationRoutes(
        dependencies.authService,
        dependencies.travelLocationService,
        dependencies.travelPlaceSearchService,
        dependencies.travelTripService,
    )
    configurePresenceStreamRoutes(
        authService = dependencies.authService,
        broadcaster = dependencies.presenceBroadcaster,
        blockRepository = dependencies.blockRepository,
    )
    configureGridRoutes(dependencies.authService, dependencies.gridService, dependencies.requestProfilerFactory)
    dependencies.favoriteService?.let { service -> configureFavoriteRoutes(dependencies.authService, service) }
    configureSavedGridFilterRoutes(dependencies.authService, dependencies.savedGridFilterService)
    configureTonightSessionRoutes(dependencies.authService, dependencies.tonightSessionService)
    configureTonightFeedRoutes(dependencies.authService, dependencies.tonightFeedService)
    configurePsyncRoutes(dependencies.authService, dependencies.psyncSessionService)
    configurePsyncStreamRoutes(dependencies.authService, dependencies.psyncBroadcaster, dependencies.psyncPresenceWatcher)
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
    configureAppealRoutes(dependencies.authService, dependencies.appealService, dependencies.config.trustedProxyHops)
    configureSwipeRoutes(
        authService = dependencies.authService,
        swipeService = dependencies.swipeService,
        requestProfilerFactory = dependencies.requestProfilerFactory,
    )
    configureSavedSwipeFilterRoutes(dependencies.authService, dependencies.savedSwipeFilterService)
    dependencies.matchCelebrationService?.let { configureMatchCelebrationRoutes(dependencies.authService, it) }
}

private const val SHUTDOWN_GRACE_MS = 3_000L
private const val SHUTDOWN_TIMEOUT_MS = 8_000L
