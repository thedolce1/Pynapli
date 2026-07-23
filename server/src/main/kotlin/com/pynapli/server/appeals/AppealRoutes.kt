package com.pynapli.server.appeals

import com.pynapli.server.auth.AuthException
import com.pynapli.server.auth.AuthService
import com.pynapli.shared.network.dto.SubmitAppealRequestDto
import io.ktor.http.HttpHeaders
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing

fun Application.configureAppealRoutes(
    authService: AuthService?,
    appealService: AppealService?,
) {
    if (authService == null || appealService == null) return

    routing {
        route("/v1/appeals") {
            post {
                val user = authService.authenticate(call.requiredBearerToken())
                val request = call.receive<SubmitAppealRequestDto>()
                call.respond(appealService.submitAppeal(user.id, request))
            }

            get {
                val user = authService.authenticate(call.requiredBearerToken())
                call.respond(appealService.listMyAppeals(user.id))
            }
        }
    }
}

private fun ApplicationCall.requiredBearerToken(): String {
    val value = request.headers[HttpHeaders.Authorization] ?: throw AuthException("UNAUTHENTICATED")
    if (!value.startsWith("Bearer ", ignoreCase = true)) throw AuthException("UNAUTHENTICATED")
    return value.substringAfter("Bearer ").trim().takeIf { it.isNotEmpty() } ?: throw AuthException("UNAUTHENTICATED")
}
