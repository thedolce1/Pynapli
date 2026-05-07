package com.pynapli.server

import com.pynapli.server.config.ServerConfig
import com.pynapli.server.activity.configureActivityRoutes
import com.pynapli.server.auth.configureAuthRoutes
import com.pynapli.server.dependencies.ServerDependencies
import com.pynapli.server.dependencies.closeDependenciesOnStop
import com.pynapli.server.grid.configureGridRoutes
import com.pynapli.server.location.configureLocationRoutes
import com.pynapli.server.media.configureMediaRoutes
import com.pynapli.server.messaging.configureMessagingRoutes
import com.pynapli.server.moderation.configureAdminReportRoutes
import com.pynapli.server.moderation.configureAdminRiskRoutes
import com.pynapli.server.moderation.configureAdminModerationRoutes
import com.pynapli.server.notifications.configureNotificationRoutes
import com.pynapli.server.plugins.configureHTTP
import com.pynapli.server.plugins.configureMonitoring
import com.pynapli.server.plugins.configureSerialization
import com.pynapli.server.plugins.configureStatusPages
import com.pynapli.server.presence.configurePresenceRoutes
import com.pynapli.server.profile.configureProfileRoutes
import com.pynapli.server.profilelink.configureProfileLinkRoutes
import com.pynapli.server.routing.configureHealthRoutes
import com.pynapli.server.safety.configureSafetyRoutes
import com.pynapli.server.subscriptions.configureSubscriptionAdminRoutes
import com.pynapli.server.subscriptions.configureSubscriptionRoutes
import com.pynapli.server.swipe.configureSwipeRoutes
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
        }
    ).start(wait = true)
}

fun Application.module(dependencies: ServerDependencies = ServerDependencies.fromEnvironment()) {
    closeDependenciesOnStop(dependencies)
    dependencies.notificationDispatchWorker?.start()
    dependencies.playStoreSubscriptionSyncWorker?.start()
    configureSerialization()
    configureStatusPages()
    configureMonitoring()
    configureHTTP()
    configureHealthRoutes(dependencies)
    configureAdminModerationRoutes(dependencies.config, dependencies.messageModerationService)
    configureAdminReportRoutes(dependencies.config, dependencies.adminReportService)
    configureAdminRiskRoutes(dependencies.config, dependencies.adminRiskService)
    configureSubscriptionAdminRoutes(dependencies.config, dependencies.subscriptionService)
    configureAuthRoutes(dependencies.authService)
    configureProfileRoutes(dependencies.authService, dependencies.profileService)
    configureProfileLinkRoutes(dependencies.authService, dependencies.profileLinkService)
    configureLocationRoutes(dependencies.authService, dependencies.locationService)
    configurePresenceRoutes(dependencies.authService, dependencies.presenceService)
    configureGridRoutes(dependencies.authService, dependencies.gridService)
    configureActivityRoutes(dependencies.authService, dependencies.activityService)
    configureNotificationRoutes(dependencies.authService, dependencies.notificationService)
    configureSubscriptionRoutes(dependencies.authService, dependencies.subscriptionService)
    configureMessagingRoutes(dependencies.authService, dependencies.messagingService)
    configureMediaRoutes(
        authService = dependencies.authService,
        mediaService = dependencies.mediaService,
        localUploadStorage = dependencies.localMediaUploadStorage
    )
    configureSafetyRoutes(
        authService = dependencies.authService,
        blockService = dependencies.blockService,
        reportService = dependencies.reportService
    )
    configureSwipeRoutes(
        authService = dependencies.authService,
        swipeService = dependencies.swipeService
    )
}
