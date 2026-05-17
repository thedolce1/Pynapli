package com.pynapli.server.location

import com.pynapli.server.auth.AuthException
import com.pynapli.server.auth.AuthService
import com.pynapli.shared.network.dto.SetTravelLocationRequestDto
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing

/**
 * Passport / travel mode endpoints per API_CONTRACT_SPEC L163-169.
 * `GET` returns null (or a 204) when no active travel row exists so the client
 * can render the local-only state without an error branch.
 */
fun Application.configureTravelLocationRoutes(
    authService: AuthService?,
    travelLocationService: TravelLocationService?
) {
    if (authService == null || travelLocationService == null) return

    routing {
        get("/v1/location/travel") {
            val user = authService.authenticate(call.requiredBearerToken())
            val current = travelLocationService.getCurrent(user.id)
            if (current == null) {
                call.respond(HttpStatusCode.NoContent)
            } else {
                call.respond(current)
            }
        }

        post("/v1/location/travel") {
            val user = authService.authenticate(call.requiredBearerToken())
            val request = call.receive<SetTravelLocationRequestDto>()
            call.respond(travelLocationService.set(user.id, request))
        }

        delete("/v1/location/travel") {
            val user = authService.authenticate(call.requiredBearerToken())
            val cleared = travelLocationService.clear(user.id)
            if (cleared) call.respond(HttpStatusCode.NoContent) else call.respond(HttpStatusCode.NotFound)
        }
    }
}

private fun ApplicationCall.requiredBearerToken(): String {
    val value = request.headers[HttpHeaders.Authorization] ?: throw AuthException("UNAUTHENTICATED")
    if (!value.startsWith("Bearer ", ignoreCase = true)) throw AuthException("UNAUTHENTICATED")
    return value.substringAfter("Bearer ").trim().takeIf { it.isNotEmpty() } ?: throw AuthException("UNAUTHENTICATED")
}
