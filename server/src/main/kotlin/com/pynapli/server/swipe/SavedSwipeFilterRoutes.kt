package com.pynapli.server.swipe

import com.pynapli.server.auth.AuthException
import com.pynapli.server.auth.AuthService
import com.pynapli.shared.network.dto.CreateSavedSwipeFilterRequestDto
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

fun Application.configureSavedSwipeFilterRoutes(
    authService: AuthService?,
    savedSwipeFilterService: SavedSwipeFilterService?,
) {
    if (authService == null || savedSwipeFilterService == null) return

    routing {
        get("/v1/me/swipe-filters") {
            val user = authService.authenticate(call.requiredBearerToken())
            call.respond(savedSwipeFilterService.list(user.id))
        }

        post("/v1/me/swipe-filters") {
            val user = authService.authenticate(call.requiredBearerToken())
            val body = call.receive<CreateSavedSwipeFilterRequestDto>()
            val created = savedSwipeFilterService.create(user.id, body)
            call.respond(HttpStatusCode.Created, created)
        }

        delete("/v1/me/swipe-filters/{id}") {
            val user = authService.authenticate(call.requiredBearerToken())
            val id =
                call.parameters["id"]?.takeIf { it.isNotBlank() }
                    ?: throw SavedSwipeFilterException("INVALID_SAVED_FILTER_ID")
            val removed = savedSwipeFilterService.delete(user.id, id)
            if (removed) call.respond(HttpStatusCode.NoContent) else call.respond(HttpStatusCode.NotFound)
        }
    }
}

private fun ApplicationCall.requiredBearerToken(): String {
    val value = request.headers[HttpHeaders.Authorization] ?: throw AuthException("UNAUTHENTICATED")
    if (!value.startsWith("Bearer ", ignoreCase = true)) throw AuthException("UNAUTHENTICATED")
    return value.substringAfter("Bearer ").trim().takeIf { it.isNotEmpty() } ?: throw AuthException("UNAUTHENTICATED")
}
