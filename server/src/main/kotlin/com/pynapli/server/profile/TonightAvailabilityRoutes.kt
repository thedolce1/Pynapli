package com.pynapli.server.profile

import com.pynapli.server.auth.AuthException
import com.pynapli.server.auth.AuthService
import com.pynapli.shared.network.dto.SetTonightAvailableRequestDto
import io.ktor.http.HttpHeaders
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.receiveNullable
import io.ktor.server.response.respond
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing

fun Application.configureTonightAvailabilityRoutes(
    authService: AuthService?,
    service: TonightAvailabilityService?
) {
    if (authService == null || service == null) return

    routing {
        get("/v1/me/availability/tonight") {
            val user = authService.authenticate(call.requiredBearerToken())
            call.respond(service.current(user.id))
        }

        post("/v1/me/availability/tonight") {
            val user = authService.authenticate(call.requiredBearerToken())
            val body = call.receiveNullable<SetTonightAvailableRequestDto>() ?: SetTonightAvailableRequestDto()
            call.respond(service.setAvailable(user.id, body.durationHours))
        }

        delete("/v1/me/availability/tonight") {
            val user = authService.authenticate(call.requiredBearerToken())
            call.respond(service.clear(user.id))
        }
    }
}

private fun ApplicationCall.requiredBearerToken(): String {
    val value = request.headers[HttpHeaders.Authorization] ?: throw AuthException("UNAUTHENTICATED")
    if (!value.startsWith("Bearer ", ignoreCase = true)) throw AuthException("UNAUTHENTICATED")
    return value.substringAfter("Bearer ").trim().takeIf { it.isNotEmpty() } ?: throw AuthException("UNAUTHENTICATED")
}
