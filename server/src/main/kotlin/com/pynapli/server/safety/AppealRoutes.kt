package com.pynapli.server.safety

import com.pynapli.server.auth.AuthException
import com.pynapli.server.auth.AuthService
import com.pynapli.shared.network.dto.CreateAppealRequestDto
import io.ktor.http.HttpHeaders
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing

fun Application.configureAppealRoutes(
    authService: AuthService?,
    appealService: AppealService?,
) {
    if (authService == null || appealService == null) return

    routing {
        post("/v1/appeals") {
            val user = authService.authenticate(call.requiredBearerToken())
            val request = call.receive<CreateAppealRequestDto>()
            call.respond(appealService.submitAppeal(user.id, request))
        }

        get("/v1/appeals") {
            val user = authService.authenticate(call.requiredBearerToken())
            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 25
            call.respond(appealService.listAppeals(user.id, limit))
        }

        post("/v1/appeals/{appealId}/withdraw") {
            val user = authService.authenticate(call.requiredBearerToken())
            val appealId = call.parameters["appealId"] ?: throw SafetyException("INVALID_APPEAL_ID")
            call.respond(appealService.withdrawAppeal(user.id, appealId))
        }
    }
}

private fun ApplicationCall.requiredBearerToken(): String {
    val value = request.headers[HttpHeaders.Authorization] ?: throw AuthException("UNAUTHENTICATED")
    if (!value.startsWith("Bearer ", ignoreCase = true)) throw AuthException("UNAUTHENTICATED")
    return value.substringAfter("Bearer ").trim().takeIf { it.isNotEmpty() } ?: throw AuthException("UNAUTHENTICATED")
}
