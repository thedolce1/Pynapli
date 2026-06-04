package com.pynapli.server.psync

import com.pynapli.server.auth.AuthException
import com.pynapli.server.auth.AuthService
import com.pynapli.shared.network.dto.CreatePsyncSessionRequestDto
import com.pynapli.shared.network.dto.SubmitPsyncActionRequestDto
import com.pynapli.shared.network.dto.SuggestPsyncFiltersRequestDto
import io.ktor.http.HttpHeaders
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
    authService: AuthService?,
    service: PsyncSessionService?,
) {
    if (authService == null || service == null) return

    routing {
        post("/v1/psync/sessions") {
            val user = authService.authenticate(call.requiredBearerToken())
            val body = call.receiveNullable<CreatePsyncSessionRequestDto>() ?: CreatePsyncSessionRequestDto()
            call.respond(service.create(user.id, body))
        }

        post("/v1/psync/sessions/{id}/accept") {
            val user = authService.authenticate(call.requiredBearerToken())
            call.respond(service.accept(call.requiredSessionId(), user.id))
        }

        post("/v1/psync/sessions/{id}/deny") {
            val user = authService.authenticate(call.requiredBearerToken())
            call.respond(service.deny(call.requiredSessionId(), user.id))
        }

        post("/v1/psync/sessions/{id}/suggest-filters") {
            val user = authService.authenticate(call.requiredBearerToken())
            val body =
                call.receiveNullable<SuggestPsyncFiltersRequestDto>()
                    ?: throw PsyncException("PSYNC_INVALID_STATE")
            call.respond(service.suggestFilters(call.requiredSessionId(), user.id, body))
        }

        post("/v1/psync/sessions/{id}/leave") {
            val user = authService.authenticate(call.requiredBearerToken())
            call.respond(service.leave(call.requiredSessionId(), user.id))
        }

        post("/v1/psync/sessions/{id}/start") {
            val user = authService.authenticate(call.requiredBearerToken())
            call.respond(service.start(call.requiredSessionId(), user.id))
        }

        get("/v1/psync/sessions/{id}/deck") {
            val user = authService.authenticate(call.requiredBearerToken())
            call.respond(service.getDeck(call.requiredSessionId(), user.id))
        }

        post("/v1/psync/sessions/{id}/actions") {
            val user = authService.authenticate(call.requiredBearerToken())
            val body =
                call.receiveNullable<SubmitPsyncActionRequestDto>()
                    ?: throw PsyncException("PSYNC_INVALID_CARD")
            call.respond(service.submitAction(call.requiredSessionId(), user.id, body))
        }

        post("/v1/psync/sessions/{id}/pause") {
            val user = authService.authenticate(call.requiredBearerToken())
            call.respond(service.pause(call.requiredSessionId(), user.id))
        }

        post("/v1/psync/sessions/{id}/resume") {
            val user = authService.authenticate(call.requiredBearerToken())
            call.respond(service.resume(call.requiredSessionId(), user.id))
        }

        post("/v1/psync/sessions/{id}/complete") {
            val user = authService.authenticate(call.requiredBearerToken())
            call.respond(service.complete(call.requiredSessionId(), user.id))
        }

        get("/v1/psync/sessions/{id}/recap") {
            val user = authService.authenticate(call.requiredBearerToken())
            call.respond(service.getRecap(call.requiredSessionId(), user.id))
        }

        get("/v1/psync/sessions/{id}") {
            val user = authService.authenticate(call.requiredBearerToken())
            call.respond(service.get(call.requiredSessionId(), user.id))
        }

        get("/v1/me/psync/session") {
            val user = authService.authenticate(call.requiredBearerToken())
            call.respond(service.current(user.id))
        }
    }
}

private fun ApplicationCall.requiredSessionId(): UUID {
    val raw = parameters["id"]?.trim().orEmpty()
    return runCatching { UUID.fromString(raw) }
        .getOrElse { throw PsyncException("PSYNC_SESSION_NOT_FOUND") }
}

private fun ApplicationCall.requiredBearerToken(): String {
    val value = request.headers[HttpHeaders.Authorization] ?: throw AuthException("UNAUTHENTICATED")
    if (!value.startsWith("Bearer ", ignoreCase = true)) throw AuthException("UNAUTHENTICATED")
    return value.substringAfter("Bearer ").trim().takeIf { it.isNotEmpty() } ?: throw AuthException("UNAUTHENTICATED")
}
