package com.pynapli.server.tonight

import com.pynapli.server.auth.AuthService
import com.pynapli.server.auth.authenticatedUser
import com.pynapli.shared.network.dto.TonightFeedQueryRequestDto
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing

/**
 * Public Playing Tonight feed routes (PT-3). Bearer-authed; thin — all rules live in
 * [TonightFeedService].
 *  - `GET /v1/tonight/feed?radiusMeters=&intent=&limit=`: the unfiltered feed.
 *  - `POST /v1/tonight/feed/query` ([TonightFeedQueryRequestDto]): the feed with orientation /
 *    lifestyle / boundary filters. Those values travel in the body, never the query string, so they
 *    stay out of URLs and proxy access logs (the same reason Grid and Swipe queries are POSTs).
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
            call.respond(
                service.feed(
                    viewerUserId = user.id,
                    radiusMeters = radiusMeters,
                    intent = intent,
                    limit = limit,
                ),
            )
        }

        post("/v1/tonight/feed/query") {
            val user = call.authenticatedUser(authService)
            val request = call.receive<TonightFeedQueryRequestDto>()
            call.respond(
                service.feed(
                    viewerUserId = user.id,
                    radiusMeters = request.radiusMeters,
                    intent = request.intent?.trim()?.takeIf { it.isNotEmpty() },
                    limit = request.limit,
                    orientations = request.filters.orientations,
                    lifestyleTags = request.filters.lifestyleTags,
                    boundaryItems = request.filters.boundaryItems,
                ),
            )
        }
    }
}

private const val DEFAULT_RADIUS_METERS = 50_000
private const val DEFAULT_LIMIT = 100
