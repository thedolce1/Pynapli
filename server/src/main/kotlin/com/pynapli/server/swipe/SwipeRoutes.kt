package com.pynapli.server.swipe

import com.pynapli.server.auth.AuthException
import com.pynapli.server.auth.AuthService
import com.pynapli.shared.network.dto.SwipeActionRequestDto
import com.pynapli.shared.network.dto.SwipeDeckRequestDto
import io.ktor.http.HttpHeaders
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.request.receiveNullable
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing

fun Application.configureSwipeRoutes(
    authService: AuthService?,
    swipeService: SwipeService?
) {
    if (authService == null || swipeService == null) return

    routing {
        post("/v1/swipe/deck/query") {
            val user = authService.authenticate(call.requiredBearerToken())
            val request = call.receiveNullable<SwipeDeckRequestDto>() ?: SwipeDeckRequestDto()
            call.respond(swipeService.queryDeck(user.id, request))
        }

        post("/v1/swipe/actions") {
            val user = authService.authenticate(call.requiredBearerToken())
            val request = call.receive<SwipeActionRequestDto>()
            call.respond(swipeService.submitAction(user.id, request))
        }

        post("/v1/swipe/rewind") {
            val user = authService.authenticate(call.requiredBearerToken())
            call.respond(swipeService.rewind(user.id))
        }

        get("/v1/matches") {
            val user = authService.authenticate(call.requiredBearerToken())
            call.respond(swipeService.listMatches(user.id))
        }
    }
}

private fun ApplicationCall.requiredBearerToken(): String {
    val value = request.headers[HttpHeaders.Authorization] ?: throw AuthException("UNAUTHENTICATED")
    if (!value.startsWith("Bearer ", ignoreCase = true)) throw AuthException("UNAUTHENTICATED")
    return value.substringAfter("Bearer ").trim().takeIf { it.isNotEmpty() } ?: throw AuthException("UNAUTHENTICATED")
}
