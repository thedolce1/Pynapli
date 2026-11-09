package com.pynapli.server.location

import com.pynapli.server.auth.AuthService
import com.pynapli.server.auth.authenticatedUser
import com.pynapli.shared.network.dto.UpdateCurrentLocationRequestDto
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.routing

fun Application.configureLocationRoutes(
    authService: AuthService,
    locationService: LocationService,
) {
    routing {
        post("/v1/location/current") {
            val user = call.authenticatedUser(authService)
            val request = call.receive<UpdateCurrentLocationRequestDto>()
            call.respond(locationService.updateCurrentLocation(user.id, request))
        }
    }
}
