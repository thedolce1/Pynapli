package com.pynapli.server.tonight

import com.pynapli.server.auth.AuthService
import com.pynapli.server.auth.authenticatedUser
import com.pynapli.shared.network.dto.ProposeTonightSessionRequestDto
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.receiveNullable
import io.ktor.server.response.respond
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import java.util.UUID

fun Application.configureTonightSessionRoutes(
    authService: AuthService?,
    service: TonightSessionService?,
) {
    if (authService == null || service == null) return

    routing {
        post("/v1/tonight/sessions") {
            val user = call.authenticatedUser(authService)
            val body =
                call.receiveNullable<ProposeTonightSessionRequestDto>() ?: ProposeTonightSessionRequestDto()
            call.respond(service.propose(user.id, body))
        }

        get("/v1/me/tonight/session") {
            val user = call.authenticatedUser(authService)
            call.respond(service.current(user.id))
        }

        post("/v1/tonight/sessions/{id}/approve") {
            val user = call.authenticatedUser(authService)
            val sessionId = call.requiredSessionId()
            call.respond(service.approve(sessionId, user.id))
        }

        post("/v1/tonight/sessions/{id}/decline") {
            val user = call.authenticatedUser(authService)
            val sessionId = call.requiredSessionId()
            call.respond(service.decline(sessionId, user.id))
        }

        delete("/v1/tonight/sessions/{id}") {
            val user = call.authenticatedUser(authService)
            val sessionId = call.requiredSessionId()
            call.respond(service.end(sessionId, user.id))
        }
    }
}

private fun ApplicationCall.requiredSessionId(): UUID {
    val raw = parameters["id"]?.trim().orEmpty()
    return runCatching { UUID.fromString(raw) }
        .getOrElse { throw TonightSessionException("TONIGHT_SESSION_NOT_FOUND") }
}
