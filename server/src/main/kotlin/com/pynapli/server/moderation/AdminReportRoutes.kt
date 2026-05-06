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

fun Application.configureAdminReportRoutes(
    config: ServerConfig,
    adminReportService: AdminReportService?
) {
    val adminApiToken = config.adminApiToken ?: return
    if (adminReportService == null) return

    routing {
        route("/v1/admin/reports") {
            get {
                val actor = call.requireAdminActor(adminApiToken)
                val reason = call.requiredAdminReason()
                val reports = adminReportService.listReports(
                    statusText = call.request.queryParameters["status"],
                    limitText = call.request.queryParameters["limit"],
                    actorId = actor.actorId,
                    reason = reason
                )
                call.respond(AdminReportListDto(items = reports.map { it.toDto() }))
            }

            post("/{reportId}/mark-under-review") {
                val actor = call.requireAdminActor(adminApiToken)
                val reportId = call.parameters["reportId"] ?: throw ModerationException("INVALID_ID")
                val request = call.receiveNullable<AdminReportActionRequestDto>()
                    ?: throw ModerationException("MODERATION_REASON_REQUIRED")
                call.respond(
                    adminReportService.markUnderReview(
                        reportIdText = reportId,
                        actorId = actor.actorId,
                        reason = request.reason
                    ).toDto()
                )
            }

            post("/{reportId}/resolve") {
                val actor = call.requireAdminActor(adminApiToken)
                val reportId = call.parameters["reportId"] ?: throw ModerationException("INVALID_ID")
                val request = call.receiveNullable<AdminReportActionRequestDto>()
                    ?: throw ModerationException("MODERATION_REASON_REQUIRED")
                call.respond(
                    adminReportService.resolveReport(
                        reportIdText = reportId,
                        actorId = actor.actorId,
                        reason = request.reason
                    ).toDto()
                )
            }

            post("/{reportId}/dismiss") {
                val actor = call.requireAdminActor(adminApiToken)
                val reportId = call.parameters["reportId"] ?: throw ModerationException("INVALID_ID")
                val request = call.receiveNullable<AdminReportActionRequestDto>()
                    ?: throw ModerationException("MODERATION_REASON_REQUIRED")
                call.respond(
                    adminReportService.dismissReport(
                        reportIdText = reportId,
                        actorId = actor.actorId,
                        reason = request.reason
                    ).toDto()
                )
            }
        }
    }
}

@Serializable
data class AdminReportListDto(
    val items: List<AdminReportItemDto>
)

@Serializable
data class AdminReportItemDto(
    val reportId: String,
    val reporterUserId: String,
    val reportedUserId: String,
    val targetType: String,
    val targetId: String? = null,
    val category: String,
    val note: String? = null,
    val status: String,
    val createdAt: String,
    val updatedAt: String,
    val reviewedAt: String? = null
)

@Serializable
data class AdminReportActionRequestDto(
    val reason: String? = null
)

@Serializable
data class AdminReportActionDto(
    val reportId: String,
    val status: String,
    val actedAt: String
)

private fun AdminReportRecord.toDto(): AdminReportItemDto =
    AdminReportItemDto(
        reportId = reportId.toString(),
        reporterUserId = reporterUserId.toString(),
        reportedUserId = reportedUserId.toString(),
        targetType = targetType,
        targetId = targetId,
        category = category,
        note = note,
        status = status,
        createdAt = createdAt.toString(),
        updatedAt = updatedAt.toString(),
        reviewedAt = reviewedAt?.toString()
    )

private fun AdminReportActionRecord.toDto(): AdminReportActionDto =
    AdminReportActionDto(
        reportId = reportId.toString(),
        status = status,
        actedAt = actedAt.toString()
    )
