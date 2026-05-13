package com.pynapli.server.verification

import com.pynapli.server.auth.AuthException
import com.pynapli.server.auth.AuthService
import com.pynapli.server.config.ServerConfig
import com.pynapli.server.moderation.requireAdminActor
import com.pynapli.server.moderation.requiredAdminReason
import com.pynapli.shared.network.dto.AgeVerificationCallbackRequestDto
import com.pynapli.shared.network.dto.AdminAgeVerificationReviewRequestDto
import com.pynapli.shared.network.dto.StartAgeVerificationRequestDto
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

fun Application.configureAgeVerificationRoutes(
    authService: AuthService?,
    ageVerificationService: AgeVerificationService?
) {
    if (authService == null || ageVerificationService == null) return

    routing {
        get("/v1/verification/requirements") {
            val user = authService.authenticate(call.requiredBearerToken())
            call.respond(ageVerificationService.requirements(user.id))
        }

        get("/v1/verification/status") {
            val user = authService.authenticate(call.requiredBearerToken())
            call.respond(ageVerificationService.status(user.id))
        }

        post("/v1/verification/start") {
            val user = authService.authenticate(call.requiredBearerToken())
            val request = call.receive<StartAgeVerificationRequestDto>()
            call.respond(ageVerificationService.start(user.id, request))
        }
    }
}

fun Application.configureAgeVerificationAdminRoutes(
    config: ServerConfig,
    ageVerificationService: AgeVerificationService?
) {
    val adminApiToken = config.adminApiToken ?: return
    if (ageVerificationService == null) return

    routing {
        post("/v1/verification/callback") {
            val actor = call.requireAdminActor(adminApiToken)
            val reason = call.requiredAdminReason()
            val request = call.receive<AgeVerificationCallbackRequestDto>()
            call.respond(ageVerificationService.callback(request, actor.actorId, reason))
        }

        route("/v1/admin/verification/age") {
            post("/{verificationId}/review") {
                val actor = call.requireAdminActor(adminApiToken)
                val verificationId = call.parameters["verificationId"] ?: throw VerificationException("INVALID_VERIFICATION_ID")
                val request = call.receive<AdminAgeVerificationReviewRequestDto>()
                call.respond(ageVerificationService.review(verificationId, request, actor.actorId))
            }
        }
    }
}

private fun ApplicationCall.requiredBearerToken(): String {
    val value = request.headers[HttpHeaders.Authorization] ?: throw AuthException("UNAUTHENTICATED")
    if (!value.startsWith("Bearer ", ignoreCase = true)) throw AuthException("UNAUTHENTICATED")
    return value.substringAfter("Bearer ").trim().takeIf { it.isNotEmpty() } ?: throw AuthException("UNAUTHENTICATED")
}
