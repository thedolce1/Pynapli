package com.pynapli.server.admin

import com.pynapli.server.config.ServerConfig
import com.pynapli.server.moderation.ModerationException
import com.pynapli.server.moderation.requireAdminActor
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.request.receiveNullable
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing

/**
 * Pending profile photo review, so the manual-review scanner path is usable: list the held photos
 * oldest first, approve or reject one. Operator-level (`requireAdminActor`, no senior role or MFA);
 * decisions write `media_review.approve/reject` audit rows on top of the AdminAuditPlugin trail.
 * Not installed without an admin token.
 */
fun Application.configureAdminMediaReviewRoutes(
    config: ServerConfig,
    service: AdminMediaReviewService,
) {
    val adminApiToken = config.adminApiToken ?: return

    routing {
        route("/v1/admin/media") {
            get("/pending") {
                call.requireAdminActor(adminApiToken)
                call.respond(
                    service.listPending(
                        limitText = call.request.queryParameters["limit"],
                        cursor = call.request.queryParameters["cursor"],
                    ),
                )
            }

            post("/{assetId}/approve") {
                val actor = call.requireAdminActor(adminApiToken)
                val assetId = call.parameters["assetId"] ?: throw ModerationException("INVALID_ID")
                val request = call.receiveNullable<AdminMediaReviewRequestDto>()
                call.respond(service.approve(assetId, actor.actorId, request?.reason))
            }

            post("/{assetId}/reject") {
                val actor = call.requireAdminActor(adminApiToken)
                val assetId = call.parameters["assetId"] ?: throw ModerationException("INVALID_ID")
                val request = call.receiveNullable<AdminMediaReviewRequestDto>()
                call.respond(service.reject(assetId, actor.actorId, request?.reason))
            }
        }
    }
}
