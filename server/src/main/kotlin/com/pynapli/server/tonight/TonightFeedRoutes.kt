package com.pynapli.server.tonight

import com.pynapli.server.auth.AuthException
import com.pynapli.server.auth.AuthService
import io.ktor.http.HttpHeaders
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing

/**
 * Public Playing Tonight feed route (PT-3). Bearer-authed; thin — all rules live in
 * [TonightFeedService]. `GET /v1/tonight/feed?radiusMeters=&intent=&limit=`.
 */
fun Application.configureTonightFeedRoutes(
    authService: AuthService?,
    service: TonightFeedService?,
) {
    if (authService == null || service == null) return

    routing {
        get("/v1/tonight/feed") {
            val user = authService.authenticate(call.requiredBearerToken())
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
    }
}

private const val DEFAULT_RADIUS_METERS = 50_000
private const val DEFAULT_LIMIT = 100

private fun ApplicationCall.requiredBearerToken(): String {
    val value = request.headers[HttpHeaders.Authorization] ?: throw AuthException("UNAUTHENTICATED")
    if (!value.startsWith("Bearer ", ignoreCase = true)) throw AuthException("UNAUTHENTICATED")
    return value.substringAfter("Bearer ").trim().takeIf { it.isNotEmpty() } ?: throw AuthException("UNAUTHENTICATED")
}
