package com.pynapli.server.grid

import com.pynapli.server.auth.AuthService
import com.pynapli.server.auth.authenticatedUser
import com.pynapli.shared.network.dto.CreateSavedGridFilterRequestDto
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing

fun Application.configureSavedGridFilterRoutes(
    authService: AuthService?,
    savedGridFilterService: SavedGridFilterService?,
) {
    if (authService == null || savedGridFilterService == null) return

    routing {
        get("/v1/me/grid-filters") {
            val user = call.authenticatedUser(authService)
            call.respond(savedGridFilterService.list(user.id))
        }

        post("/v1/me/grid-filters") {
            val user = call.authenticatedUser(authService)
            val body = call.receive<CreateSavedGridFilterRequestDto>()
            val created = savedGridFilterService.create(user.id, body)
            call.respond(HttpStatusCode.Created, created)
        }

        delete("/v1/me/grid-filters/{id}") {
            val user = call.authenticatedUser(authService)
            val id =
                call.parameters["id"]?.takeIf { it.isNotBlank() }
                    ?: throw SavedGridFilterException("INVALID_SAVED_FILTER_ID")
            val removed = savedGridFilterService.delete(user.id, id)
            if (removed) call.respond(HttpStatusCode.NoContent) else call.respond(HttpStatusCode.NotFound)
        }
    }
}
