package com.pynapli.server.verification

import com.pynapli.server.auth.AuthService
import com.pynapli.server.auth.authenticatedUser
import com.pynapli.server.config.ServerConfig
import com.pynapli.server.moderation.requireAdminActor
import com.pynapli.server.moderation.requiredAdminReason
import com.pynapli.shared.network.dto.AdminAgeVerificationReviewRequestDto
import com.pynapli.shared.network.dto.AgeVerificationCallbackRequestDto
import com.pynapli.shared.network.dto.StartAgeVerificationRequestDto
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing

fun Application.configureAgeVerificationRoutes(
    authService: AuthService,
    ageVerificationService: AgeVerificationService,
) {
    routing {
        get("/v1/verification/requirements") {
            val user = call.authenticatedUser(authService)
            call.respond(
                ageVerificationService.requirements(
                    userId = user.id,
                    market = call.request.queryParameters["market"],
                ),
            )
        }

        get("/v1/verification/status") {
            val user = call.authenticatedUser(authService)
            call.respond(ageVerificationService.status(user.id))
        }

        post("/v1/verification/start") {
            val user = call.authenticatedUser(authService)
            val request = call.receive<StartAgeVerificationRequestDto>()
            call.respond(ageVerificationService.start(user.id, request))
        }
    }
}

fun Application.configureAgeVerificationAdminRoutes(
    config: ServerConfig,
    ageVerificationService: AgeVerificationService,
) {
    val adminApiToken = config.adminApiToken ?: return

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
