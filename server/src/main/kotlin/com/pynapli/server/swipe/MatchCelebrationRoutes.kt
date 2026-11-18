package com.pynapli.server.swipe

import com.pynapli.server.auth.AuthService
import com.pynapli.server.auth.authenticatedUser
import com.pynapli.shared.network.dto.MarkMatchesCelebratedRequestDto
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing

fun Application.configureMatchCelebrationRoutes(
    authService: AuthService,
    matchCelebrationService: MatchCelebrationService,
) {
    routing {
        // Matches this person has not seen the "It's a match" moment for yet (canonical; the match
        // activity delta only says "refetch").
        get("/v1/matches/celebrations") {
            val user = call.authenticatedUser(authService)
            call.respond(matchCelebrationService.listUncelebrated(user.id))
        }

        // Idempotent: marking the same match twice, or from two devices, is a no-op.
        post("/v1/matches/celebrations/seen") {
            val user = call.authenticatedUser(authService)
            val request = call.receive<MarkMatchesCelebratedRequestDto>()
            call.respond(matchCelebrationService.markCelebrated(user.id, request))
        }
    }
}
