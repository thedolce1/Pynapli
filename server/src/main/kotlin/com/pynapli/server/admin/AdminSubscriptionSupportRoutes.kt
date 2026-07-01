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
 * Admin-only subscription support view (ADMIN_MODERATION_CONSOLE_SPEC "Subscription support view").
 * Registered only when an admin token is configured; every successful view requires a reason and
 * writes an audit row because subscription/billing state is sensitive account context. Distinct from
 * the entitlement CRUD under `/v1/admin/subscriptions/...` — this composes a read-only support view.
 */
fun Application.configureAdminSubscriptionSupportRoutes(
    config: ServerConfig,
    service: AdminSubscriptionSupportService?,
) {
    val adminApiToken = config.adminApiToken ?: return
    if (service == null) return

    routing {
        route("/v1/admin/subscriptions/support") {
            get("/{userId}") {
                val actor = call.requireAdminActor(adminApiToken)
                requireSeniorAdmin(config, actor.actorId)
                val reason = call.requiredAdminReason()
                val userId =
                    call.parameters["userId"]
                        ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                        ?: throw ModerationException("INVALID_USER_ID")
                call.respond(service.view(actorId = actor.actorId, userId = userId, reason = reason))
            }
        }
    }
}
