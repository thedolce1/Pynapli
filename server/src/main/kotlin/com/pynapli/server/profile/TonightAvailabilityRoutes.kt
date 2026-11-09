package com.pynapli.server.profile

import com.pynapli.server.auth.AuthService
import com.pynapli.server.auth.authenticatedUser
import com.pynapli.shared.network.dto.SetTonightAvailableRequestDto
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.request.receiveNullable
import io.ktor.server.response.respond
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing

fun Application.configureTonightAvailabilityRoutes(
    authService: AuthService,
    service: TonightAvailabilityService,
) {
    routing {
        get("/v1/me/availability/tonight") {
            val user = call.authenticatedUser(authService)
            call.respond(service.current(user.id))
        }

        post("/v1/me/availability/tonight") {
            val user = call.authenticatedUser(authService)
            val body = call.receiveNullable<SetTonightAvailableRequestDto>() ?: SetTonightAvailableRequestDto()
            call.respond(service.setAvailable(user.id, body.durationHours))
        }

        delete("/v1/me/availability/tonight") {
            val user = call.authenticatedUser(authService)
            call.respond(service.clear(user.id))
        }
    }
}
