package com.pynapli.server.favorites

import com.pynapli.server.auth.AuthService
import com.pynapli.server.auth.authenticatedUser
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import io.ktor.server.routing.routing

fun Application.configureFavoriteRoutes(
    authService: AuthService,
    favoriteService: FavoriteService,
) {
    routing {
        get("/v1/favorites") {
            val user = call.authenticatedUser(authService)
            call.respond(
                favoriteService.list(
                    viewerUserId = user.id,
                    cursorText = call.request.queryParameters["cursor"],
                    limitParam = call.request.queryParameters["limit"]?.toIntOrNull(),
                ),
            )
        }

        put("/v1/favorites/{userId}") {
            val user = call.authenticatedUser(authService)
            call.respond(favoriteService.add(user.id, call.parameters["userId"].orEmpty()))
        }

        delete("/v1/favorites/{userId}") {
            val user = call.authenticatedUser(authService)
            call.respond(favoriteService.remove(user.id, call.parameters["userId"].orEmpty()))
        }
    }
}
