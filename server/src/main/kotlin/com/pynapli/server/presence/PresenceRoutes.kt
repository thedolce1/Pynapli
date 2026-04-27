package com.pynapli.server.presence

import com.pynapli.server.auth.AuthException
import com.pynapli.server.auth.AuthService
import io.ktor.http.HttpHeaders
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.routing

fun Application.configurePresenceRoutes(
    authService: AuthService?,
    presenceService: PresenceService?
) {
    if (authService == null || presenceService == null) return

    routing {
        post("/v1/presence/heartbeat") {
            val user = authService.authenticate(call.requiredBearerToken())
            call.respond(presenceService.heartbeat(user.id))
        }
    }
}

private fun ApplicationCall.requiredBearerToken(): String {
    val value = request.headers[HttpHeaders.Authorization] ?: throw AuthException("UNAUTHENTICATED")
    if (!value.startsWith("Bearer ", ignoreCase = true)) throw AuthException("UNAUTHENTICATED")
    return value.substringAfter("Bearer ").trim().takeIf { it.isNotEmpty() } ?: throw AuthException("UNAUTHENTICATED")
}
