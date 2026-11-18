package com.pynapli.server.tonight

import com.pynapli.server.auth.AuthService
import com.pynapli.server.auth.authenticatedUser
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing

/**
 * Public Playing Tonight feed route (PT-3). Bearer-authed; thin — all rules live in
 * [TonightFeedService]. `GET /v1/tonight/feed?radiusMeters=&intent=&limit=&orientations=&lifestyleTags=&boundaryItems=`.
 */
fun Application.configureTonightFeedRoutes(
    authService: AuthService,
    service: TonightFeedService,
) {
    routing {
        get("/v1/tonight/feed") {
            val user = call.authenticatedUser(authService)
            val radiusMeters =
                call.request.queryParameters["radiusMeters"]?.toIntOrNull() ?: DEFAULT_RADIUS_METERS
            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: DEFAULT_LIMIT
            val intent =
                call.request.queryParameters["intent"]
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }

            // Repeated or comma-separated: ?orientations=bisexual&lifestyleTags=soft_swap,same_room
            fun listParam(name: String): List<String> =
                call.request.queryParameters
                    .getAll(name)
                    .orEmpty()
                    .flatMap { it.split(',') }
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
            call.respond(
                service.feed(
                    viewerUserId = user.id,
                    radiusMeters = radiusMeters,
                    intent = intent,
                    limit = limit,
                    orientations = listParam("orientations"),
                    lifestyleTags = listParam("lifestyleTags"),
                    boundaryItems = listParam("boundaryItems"),
                ),
            )
        }
    }
}

private const val DEFAULT_RADIUS_METERS = 50_000
private const val DEFAULT_LIMIT = 100
