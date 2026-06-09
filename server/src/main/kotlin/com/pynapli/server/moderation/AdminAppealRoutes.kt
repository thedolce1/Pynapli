package com.pynapli.server.moderation

import com.pynapli.server.config.ServerConfig
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.request.receiveNullable
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable

fun Application.configureAdminAppealRoutes(
    config: ServerConfig,
    adminAppealService: AdminAppealService?,
) {
    val adminApiToken = config.adminApiToken ?: return
    if (adminAppealService == null) return

    routing {
        route("/v1/admin/appeals") {
            get {
                val actor = call.requireAdminActor(adminApiToken)
                val reason = call.requiredAdminReason()
                val appeals =
                    adminAppealService.listAppeals(
                        statusText = call.request.queryParameters["status"],
                        limitText = call.request.queryParameters["limit"],
                        actorId = actor.actorId,
                        reason = reason,
                    )
                call.respond(AdminAppealListDto(items = appeals.map { it.toDto() }))
            }

            post("/{appealId}/mark-under-review") {
                val actor = call.requireAdminActor(adminApiToken)
                val appealId = call.parameters["appealId"] ?: throw ModerationException("INVALID_ID")
                val request =
                    call.receiveNullable<AdminAppealActionRequestDto>()
                        ?: throw ModerationException("MODERATION_REASON_REQUIRED")
                call.respond(
                    adminAppealService
                        .markUnderReview(
                            appealIdText = appealId,
                            actorId = actor.actorId,
                            reason = request.reason,
                        ).toDto(),
                )
            }

            post("/{appealId}/decide") {
                val actor = call.requireAdminActor(adminApiToken)
                val appealId = call.parameters["appealId"] ?: throw ModerationException("INVALID_ID")
                val request =
                    call.receiveNullable<AdminAppealDecisionRequestDto>()
                        ?: throw ModerationException("MODERATION_REASON_REQUIRED")
                call.respond(
                    adminAppealService
                        .decideAppeal(
                            appealIdText = appealId,
                            outcomeText = request.outcome,
                            actorId = actor.actorId,
                            reason = request.reason,
                            adminNote = request.adminNote,
                        ).toDto(),
                )
            }
        }
    }
}

@Serializable
data class AdminAppealListDto(
    val items: List<AdminAppealItemDto>,
)

@Serializable
data class AdminAppealItemDto(
    val appealId: String,
    val userId: String,
    val actionType: String,
    val targetType: String,
    val targetId: String? = null,
    val relatedReportId: String? = null,
    val status: String,
    val userReason: String,
    val adminNote: String? = null,
    val createdAt: String,
    val updatedAt: String,
    val reviewedAt: String? = null,
    val reviewedByAdminId: String? = null,
)

@Serializable
data class AdminAppealActionRequestDto(
    val reason: String? = null,
)

@Serializable
data class AdminAppealDecisionRequestDto(
    val outcome: String? = null,
    val reason: String? = null,
    val adminNote: String? = null,
)

@Serializable
data class AdminAppealActionDto(
    val appealId: String,
    val userId: String,
    val status: String,
    val actedAt: String,
)

private fun AdminAppealRecord.toDto(): AdminAppealItemDto =
    AdminAppealItemDto(
        appealId = appealId.toString(),
        userId = userId.toString(),
        actionType = actionType,
        targetType = targetType,
        targetId = targetId,
        relatedReportId = relatedReportId?.toString(),
        status = status,
        userReason = userReason,
        adminNote = adminNote,
        createdAt = createdAt.toString(),
        updatedAt = updatedAt.toString(),
        reviewedAt = reviewedAt?.toString(),
        reviewedByAdminId = reviewedByAdminId,
    )

private fun AdminAppealActionRecord.toDto(): AdminAppealActionDto =
    AdminAppealActionDto(
        appealId = appealId.toString(),
        userId = userId.toString(),
        status = status,
        actedAt = actedAt.toString(),
    )
