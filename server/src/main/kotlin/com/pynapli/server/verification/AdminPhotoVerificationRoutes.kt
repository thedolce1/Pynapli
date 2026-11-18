package com.pynapli.server.verification

import com.pynapli.server.admin.requireAdminMfa
import com.pynapli.server.admin.requireSeniorAdmin
import com.pynapli.server.config.ServerConfig
import com.pynapli.server.moderation.requireAdminActor
import com.pynapli.server.moderation.requiredAdminReason
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.receiveNullable
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing

/**
 * Admin review of selfie photo verification. Every route changes or reveals trust status (the queue
 * shows selfies), so all of them need the admin token, a senior admin and step-up MFA. Decisions are
 * audited in the same transaction (plus the general AdminAuditPlugin row); viewing the queue needs
 * `X-Admin-Reason` and writes a `photo_verification.queue_view` audit row. Not installed without an
 * admin token.
 */
fun Application.configureAdminPhotoVerificationRoutes(
    config: ServerConfig,
    photoVerificationService: PhotoVerificationService,
) {
    val adminApiToken = config.adminApiToken ?: return

    suspend fun ApplicationCall.requireSeniorReviewer(): String {
        val actor = requireAdminActor(adminApiToken)
        requireSeniorAdmin(config, actor.actorId)
        requireAdminMfa(config, actor.actorId)
        return actor.actorId
    }

    routing {
        route("/v1/admin/verification/photo") {
            get {
                val actorId = call.requireSeniorReviewer()
                val reason = call.requiredAdminReason()
                call.respond(
                    photoVerificationService.listPending(
                        actorId = actorId,
                        reason = reason,
                        limitText = call.request.queryParameters["limit"],
                        cursor = call.request.queryParameters["cursor"],
                    ),
                )
            }

            post("/{verificationId}/approve") {
                val actorId = call.requireSeniorReviewer()
                val verificationId = call.parameters["verificationId"] ?: throw VerificationException("INVALID_VERIFICATION_ID")
                val request = call.receiveNullable<AdminPhotoVerificationDecisionRequestDto>()
                call.respond(photoVerificationService.approve(verificationId, actorId, request?.reason))
            }

            post("/{verificationId}/reject") {
                val actorId = call.requireSeniorReviewer()
                val verificationId = call.parameters["verificationId"] ?: throw VerificationException("INVALID_VERIFICATION_ID")
                val request = call.receiveNullable<AdminPhotoVerificationDecisionRequestDto>()
                call.respond(photoVerificationService.reject(verificationId, actorId, request?.reason))
            }

            post("/users/{userId}/revoke") {
                val actorId = call.requireSeniorReviewer()
                val userId = call.parameters["userId"] ?: throw VerificationException("INVALID_USER_ID")
                val request = call.receiveNullable<AdminPhotoVerificationDecisionRequestDto>()
                call.respond(photoVerificationService.revoke(userId, actorId, request?.reason))
            }
        }
    }
}
