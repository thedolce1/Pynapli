package com.pynapli.server.psync

import com.pynapli.server.auth.AuthService
import com.pynapli.server.auth.authenticatedUser
import com.pynapli.shared.network.dto.CreatePsyncSessionRequestDto
import com.pynapli.shared.network.dto.SubmitPsyncActionRequestDto
import com.pynapli.shared.network.dto.SuggestPsyncFiltersRequestDto
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.receiveNullable
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import java.util.UUID

/**
 * Psync session endpoints (PS-1 negotiation + PS-2 shared deck/swipes + PS-3 match
 * resolution/recap). All bearer-authed. Realtime + Activity remain later phases.
 */
fun Application.configurePsyncRoutes(
    authService: AuthService,
    service: PsyncSessionService,
) {
    routing {
        post("/v1/psync/sessions") {
            val user = call.authenticatedUser(authService)
            val body = call.receiveNullable<CreatePsyncSessionRequestDto>() ?: CreatePsyncSessionRequestDto()
            call.respond(service.create(user.id, body))
        }

        post("/v1/psync/sessions/{id}/accept") {
            val user = call.authenticatedUser(authService)
            call.respond(service.accept(call.requiredSessionId(), user.id))
        }

        post("/v1/psync/sessions/{id}/deny") {
            val user = call.authenticatedUser(authService)
            call.respond(service.deny(call.requiredSessionId(), user.id))
        }

        post("/v1/psync/sessions/{id}/suggest-filters") {
            val user = call.authenticatedUser(authService)
            val body =
                call.receiveNullable<SuggestPsyncFiltersRequestDto>()
                    ?: throw PsyncException("PSYNC_INVALID_STATE")
            call.respond(service.suggestFilters(call.requiredSessionId(), user.id, body))
        }

        post("/v1/psync/sessions/{id}/leave") {
            val user = call.authenticatedUser(authService)
            call.respond(service.leave(call.requiredSessionId(), user.id))
        }

        post("/v1/psync/sessions/{id}/start") {
            val user = call.authenticatedUser(authService)
            call.respond(service.start(call.requiredSessionId(), user.id))
        }

        get("/v1/psync/sessions/{id}/deck") {
            val user = call.authenticatedUser(authService)
            call.respond(service.getDeck(call.requiredSessionId(), user.id))
        }

        post("/v1/psync/sessions/{id}/actions") {
            val user = call.authenticatedUser(authService)
            val body =
                call.receiveNullable<SubmitPsyncActionRequestDto>()
                    ?: throw PsyncException("PSYNC_INVALID_CARD")
            call.respond(service.submitAction(call.requiredSessionId(), user.id, body))
        }

        post("/v1/psync/sessions/{id}/pause") {
            val user = call.authenticatedUser(authService)
            call.respond(service.pause(call.requiredSessionId(), user.id))
        }

        post("/v1/psync/sessions/{id}/resume") {
            val user = call.authenticatedUser(authService)
            call.respond(service.resume(call.requiredSessionId(), user.id))
        }

        post("/v1/psync/sessions/{id}/complete") {
            val user = call.authenticatedUser(authService)
            call.respond(service.complete(call.requiredSessionId(), user.id))
        }

        get("/v1/psync/sessions/{id}/recap") {
            val user = call.authenticatedUser(authService)
            call.respond(service.getRecap(call.requiredSessionId(), user.id))
        }

        post("/v1/psync/sessions/{id}/nudge") {
            val user = call.authenticatedUser(authService)
            call.respond(service.nudge(call.requiredSessionId(), user.id))
        }

        get("/v1/psync/sessions/{id}") {
            val user = call.authenticatedUser(authService)
            call.respond(service.get(call.requiredSessionId(), user.id))
        }

        get("/v1/me/psync/session") {
            val user = call.authenticatedUser(authService)
            call.respond(service.current(user.id))
        }
    }
}

private fun ApplicationCall.requiredSessionId(): UUID {
    val raw = parameters["id"]?.trim().orEmpty()
    return runCatching { UUID.fromString(raw) }
        .getOrElse { throw PsyncException("PSYNC_SESSION_NOT_FOUND") }
}
