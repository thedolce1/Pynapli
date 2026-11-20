package com.pynapli.server.metinperson

import com.pynapli.server.admin.requireAdminMfa
import com.pynapli.server.admin.requireSeniorAdmin
import com.pynapli.server.config.ServerConfig
import com.pynapli.server.moderation.ModerationException
import com.pynapli.server.moderation.requireAdminActor
import com.pynapli.server.moderation.requiredAdminReason
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.request.receiveNullable
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import java.util.UUID

/**
 * Admin moderation for "Met in person": the minimal read view and three actions, new routes under the
 * existing admin gate. Every route reveals or changes who vouched for whom, so all of them take the
 * same gate as the admin user lookup: admin token + actor, senior role, step-up MFA, and a required
 * `X-Admin-Reason`; each writes its own `met_in_person.*` audit row (plus the request-level row for the
 * POSTs). Not installed without an admin token.
 */
fun Application.configureAdminMetInPersonRoutes(
    config: ServerConfig,
    service: AdminMetInPersonService,
) {
    val adminApiToken = config.adminApiToken ?: return

    suspend fun ApplicationCall.requireSeniorWithReason(): Pair<String, String> {
        val actor = requireAdminActor(adminApiToken)
        requireSeniorAdmin(config, actor.actorId)
        requireAdminMfa(config, actor.actorId)
        val reason = requiredAdminReason()
        if (reason.length > MAX_REASON_LENGTH) throw ModerationException("MODERATION_REASON_TOO_LONG")
        return actor.actorId to reason
    }

    fun ApplicationCall.uuidParameter(name: String): UUID =
        parameters[name]
            ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: throw ModerationException("INVALID_ID")

    routing {
        route("/v1/admin/met-in-person") {
            get("/users/{userId}") {
                val (actorId, reason) = call.requireSeniorWithReason()
                call.respond(service.view(actorId, call.uuidParameter("userId"), reason))
            }

            post("/confirmations/{confirmationId}/void") {
                val (actorId, reason) = call.requireSeniorWithReason()
                call.respond(service.voidConfirmation(actorId, call.uuidParameter("confirmationId"), reason))
            }

            // The voucher id travels in the body, never the path (request-level audit rows store paths).
            post("/vouchers/void") {
                val (actorId, reason) = call.requireSeniorWithReason()
                val request = call.receive<AdminMetInPersonVoidVoucherRequestDto>()
                val voucherUserId =
                    runCatching { UUID.fromString(request.voucherUserId.trim()) }.getOrNull()
                        ?: throw ModerationException("INVALID_USER_ID")
                call.respond(service.voidAllFromVoucher(actorId, voucherUserId, reason))
            }

            post("/users/{userId}/freeze") {
                val (actorId, reason) = call.requireSeniorWithReason()
                val request = call.receiveNullable<AdminMetInPersonFreezeRequestDto>()
                call.respond(service.setFrozen(actorId, call.uuidParameter("userId"), request?.frozen ?: true, reason))
            }
        }
    }
}

private const val MAX_REASON_LENGTH = 500
