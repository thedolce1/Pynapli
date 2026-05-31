package com.pynapli.server.location

import com.pynapli.server.auth.AuthException
import com.pynapli.server.auth.AuthService
import com.pynapli.shared.network.dto.UpdateCurrentLocationRequestDto
import io.ktor.http.HttpHeaders
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.routing

fun Application.configureLocationRoutes(
    authService: AuthService?,
    locationService: LocationService?,
) {
    if (authService == null || locationService == null) return

    routing {
        post("/v1/location/current") {
            val user = authService.authenticate(call.requiredBearerToken())
            val request = call.receive<UpdateCurrentLocationRequestDto>()
            call.respond(locationService.updateCurrentLocation(user.id, request))
        }
    }
}

private fun ApplicationCall.requiredBearerToken(): String {
    val value = request.headers[HttpHeaders.Authorization] ?: throw AuthException("UNAUTHENTICATED")
    if (!value.startsWith("Bearer ", ignoreCase = true)) throw AuthException("UNAUTHENTICATED")
    return value.substringAfter("Bearer ").trim().takeIf { it.isNotEmpty() } ?: throw AuthException("UNAUTHENTICATED")
}
