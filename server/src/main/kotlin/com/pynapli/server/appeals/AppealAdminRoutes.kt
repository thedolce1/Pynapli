package com.pynapli.server.appeals

import com.pynapli.server.config.ServerConfig
import com.pynapli.server.moderation.ModerationException
import com.pynapli.server.moderation.requireAdminActor
import com.pynapli.server.moderation.requiredAdminReason
import com.pynapli.shared.network.dto.AdminResolveAppealRequestDto
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.request.receiveNullable
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing

fun Application.configureAppealAdminRoutes(
    config: ServerConfig,
    appealService: AppealService?,
) {
    val adminApiToken = config.adminApiToken ?: return
    if (appealService == null) return

    routing {
        route("/v1/admin/appeals") {
            get {
                // Admin token + reason header are required for queue access, mirroring the other
                // admin queues (the reason feeds the audit trail).
                call.requireAdminActor(adminApiToken)
                call.requiredAdminReason()
                call.respond(
                    appealService.listPending(
                        cursorToken = call.request.queryParameters["cursor"],
                        limitText = call.request.queryParameters["limit"],
                    ),
                )
            }

            post("/{id}/resolve") {
                val actor = call.requireAdminActor(adminApiToken)
                val appealId = call.parameters["id"] ?: throw ModerationException("INVALID_ID")
                val request =
                    call.receiveNullable<AdminResolveAppealRequestDto>()
                        ?: throw ModerationException("MODERATION_REASON_REQUIRED")
                call.respond(
                    appealService.resolve(
                        appealIdText = appealId,
                        request = request,
                        adminId = actor.actorId,
                    ),
                )
            }
        }
    }
}
