package com.pynapli.server.admin

import com.pynapli.server.config.ServerConfig
import com.pynapli.server.moderation.ModerationException
import com.pynapli.server.moderation.requireAdminActor
import com.pynapli.server.moderation.requiredAdminReason
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import java.util.UUID

/**
 * Admin-only partner-link review (ADMIN_MODERATION_CONSOLE_SPEC "Partner Link Review"). Registered
 * only when an admin token is configured; every successful review requires a reason and writes an
 * audit row because linked-partner history is sensitive account context.
 */
fun Application.configureAdminPartnerLinkReviewRoutes(
    config: ServerConfig,
    service: AdminPartnerLinkReviewService?,
) {
    val adminApiToken = config.adminApiToken ?: return
    if (service == null) return

    routing {
        route("/v1/admin/partner-links") {
            get("/{userId}") {
                val actor = call.requireAdminActor(adminApiToken)
                requireSeniorAdmin(config, actor.actorId)
                call.requireAdminMfa(config, actor.actorId)
                val reason = call.requiredAdminReason()
                val userId =
                    call.parameters["userId"]
                        ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                        ?: throw ModerationException("INVALID_USER_ID")
                call.respond(service.review(actorId = actor.actorId, userId = userId, reason = reason))
            }
        }
    }
}
