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
 * Admin-only consolidated user lookup (ADMIN_MODERATION_CONSOLE_SPEC "User Lookup"). Same server-only
 * `X-Admin-Token` + `X-Admin-Id` gate as the other admin routes, and — because a lookup reveals a
 * real user's account state — it additionally requires `X-Admin-Reason` and writes an audit row.
 * Registered only when an admin token is configured.
 */
fun Application.configureAdminUserLookupRoutes(
    config: ServerConfig,
    service: AdminUserLookupService?,
) {
    val adminApiToken = config.adminApiToken ?: return
    if (service == null) return

    routing {
        route("/v1/admin/users") {
            get("/{userId}") {
                val actor = call.requireAdminActor(adminApiToken)
                requireSeniorAdmin(config, actor.actorId)
                val reason = call.requiredAdminReason()
                val userId =
                    call.parameters["userId"]
                        ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                        ?: throw ModerationException("INVALID_USER_ID")
                call.respond(service.lookup(actorId = actor.actorId, userId = userId, reason = reason))
            }
        }
    }
}
