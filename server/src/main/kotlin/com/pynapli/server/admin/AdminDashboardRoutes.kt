package com.pynapli.server.admin

import com.pynapli.server.config.ServerConfig
import com.pynapli.server.moderation.requireAdminActor
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing

/**
 * Admin-only unified dashboard (ADMIN_MODERATION_CONSOLE_SPEC "unified moderation console").
 * Registered only when an admin token is configured. Counts-only backlog snapshot across the operator
 * queues — like the metrics routes, it requires the admin token via `requireAdminActor` but carries no
 * per-user data, so it needs no `X-Admin-Reason` / audit row.
 */
fun Application.configureAdminDashboardRoutes(
    config: ServerConfig,
    service: AdminDashboardService?,
) {
    val adminApiToken = config.adminApiToken ?: return
    if (service == null) return

    routing {
        get("/v1/admin/dashboard") {
            call.requireAdminActor(adminApiToken)
            call.respond(service.dashboard())
        }
    }
}
