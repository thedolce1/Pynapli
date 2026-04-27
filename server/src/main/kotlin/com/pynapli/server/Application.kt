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
import com.pynapli.server.plugins.configureHTTP
import com.pynapli.server.plugins.configureMonitoring
import com.pynapli.server.plugins.configureSerialization
import com.pynapli.server.plugins.configureStatusPages
import com.pynapli.server.presence.configurePresenceRoutes
import com.pynapli.server.profile.configureProfileRoutes
import com.pynapli.server.routing.configureHealthRoutes
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
    configureSerialization()
    configureStatusPages()
    configureMonitoring()
    configureHTTP()
    configureHealthRoutes(dependencies)
    configureAuthRoutes(dependencies.authService)
    configureProfileRoutes(dependencies.authService, dependencies.profileService)
    configureLocationRoutes(dependencies.authService, dependencies.locationService)
    configurePresenceRoutes(dependencies.authService, dependencies.presenceService)
    configureGridRoutes(dependencies.authService, dependencies.gridService)
    configureActivityRoutes(dependencies.authService, dependencies.activityService)
    configureMessagingRoutes(dependencies.authService, dependencies.messagingService)
    configureMediaRoutes(
        authService = dependencies.authService,
        mediaService = dependencies.mediaService,
        localUploadStorage = dependencies.localMediaUploadStorage
    )
}
