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

fun Application.configureAdminRiskRoutes(
    config: ServerConfig,
    adminRiskService: AdminRiskService?,
) {
    val adminApiToken = config.adminApiToken ?: return
    if (adminRiskService == null) return

    routing {
        route("/v1/admin/risk") {
            get("/states") {
                val actor = call.requireAdminActor(adminApiToken)
                val reason = call.requiredAdminReason()
                val states =
                    adminRiskService.listRiskStates(
                        tierFilterText = call.request.queryParameters["tier"],
                        limitText = call.request.queryParameters["limit"],
                        actorId = actor.actorId,
                        reason = reason,
                    )
                call.respond(AdminRiskStateListDto(items = states.map { it.toDto() }))
            }

            get("/users/{userId}/events") {
                val actor = call.requireAdminActor(adminApiToken)
                val reason = call.requiredAdminReason()
                val userId = call.parameters["userId"] ?: throw ModerationException("INVALID_ID")
                val events =
                    adminRiskService.listRiskEvents(
                        userIdText = userId,
                        limitText = call.request.queryParameters["limit"],
                        actorId = actor.actorId,
                        reason = reason,
                    )
                call.respond(AdminRiskEventListDto(items = events.map { it.toDto() }))
            }

            post("/users/{userId}/clear") {
                val actor = call.requireAdminActor(adminApiToken)
                val userId = call.parameters["userId"] ?: throw ModerationException("INVALID_ID")
                val request =
                    call.receiveNullable<AdminRiskActionRequestDto>()
                        ?: throw ModerationException("MODERATION_REASON_REQUIRED")
                call.respond(
                    adminRiskService
                        .clearRisk(
                            userIdText = userId,
                            actorId = actor.actorId,
                            reason = request.reason,
                        ).toDto(),
                )
            }

            post("/users/{userId}/set-tier") {
                val actor = call.requireAdminActor(adminApiToken)
                val userId = call.parameters["userId"] ?: throw ModerationException("INVALID_ID")
                val request =
                    call.receiveNullable<AdminRiskSetTierRequestDto>()
                        ?: throw ModerationException("INVALID_RISK_TIER")
                call.respond(
                    adminRiskService
                        .setRiskTier(
                            userIdText = userId,
                            tierText = request.tier,
                            actorId = actor.actorId,
                            reason = request.reason,
                        ).toDto(),
                )
            }

            post("/users/{userId}/reviewed") {
                val actor = call.requireAdminActor(adminApiToken)
                val userId = call.parameters["userId"] ?: throw ModerationException("INVALID_ID")
                val request =
                    call.receiveNullable<AdminRiskActionRequestDto>()
                        ?: throw ModerationException("MODERATION_REASON_REQUIRED")
                call.respond(
                    adminRiskService
                        .markRiskReviewed(
                            userIdText = userId,
                            actorId = actor.actorId,
                            reason = request.reason,
                        ).toDto(),
                )
            }

            post("/users/{userId}/lock") {
                val actor = call.requireAdminActor(adminApiToken)
                val userId = call.parameters["userId"] ?: throw ModerationException("INVALID_ID")
                val request =
                    call.receiveNullable<AdminRiskActionRequestDto>()
                        ?: throw ModerationException("MODERATION_REASON_REQUIRED")
                call.respond(
                    adminRiskService
                        .lockAccount(
                            userIdText = userId,
                            actorId = actor.actorId,
                            reason = request.reason,
                        ).toDto(),
                )
            }

            post("/users/{userId}/ban") {
                val actor = call.requireAdminActor(adminApiToken)
                val userId = call.parameters["userId"] ?: throw ModerationException("INVALID_ID")
                val request =
                    call.receiveNullable<AdminRiskActionRequestDto>()
                        ?: throw ModerationException("MODERATION_REASON_REQUIRED")
                call.respond(
                    adminRiskService
                        .banAccount(
                            userIdText = userId,
                            actorId = actor.actorId,
                            reason = request.reason,
                        ).toDto(),
                )
            }
        }
    }
}

@Serializable
data class AdminRiskStateListDto(
    val items: List<AdminRiskStateDto>,
)

@Serializable
data class AdminRiskStateDto(
    val userId: String,
    val displayName: String? = null,
    val score: Int,
    val tier: String,
    val lastEventAt: String? = null,
    val lastReviewedAt: String? = null,
    val updatedAt: String,
)

@Serializable
data class AdminRiskEventListDto(
    val items: List<AdminRiskEventDto>,
)

@Serializable
data class AdminRiskEventDto(
    val eventId: String,
    val userId: String,
    val actorUserId: String? = null,
    val source: String,
    val reason: String,
    val delta: Int,
    val scoreAfter: Int,
    val tierAfter: String,
    val metadataJson: String,
    val createdAt: String,
)

@Serializable
data class AdminRiskActionRequestDto(
    val reason: String? = null,
)

@Serializable
data class AdminRiskSetTierRequestDto(
    val tier: String? = null,
    val reason: String? = null,
)

@Serializable
data class AdminRiskActionDto(
    val userId: String,
    val score: Int,
    val tier: String,
    val accountStatus: String? = null,
    val lastReviewedAt: String? = null,
    val updatedAt: String,
)

private fun AdminRiskStateRecord.toDto(): AdminRiskStateDto =
    AdminRiskStateDto(
        userId = userId.toString(),
        displayName = displayName,
        score = score,
        tier = tier,
        lastEventAt = lastEventAt?.toString(),
        lastReviewedAt = lastReviewedAt?.toString(),
        updatedAt = updatedAt.toString(),
    )

private fun AdminRiskEventRecord.toDto(): AdminRiskEventDto =
    AdminRiskEventDto(
        eventId = eventId.toString(),
        userId = userId.toString(),
        actorUserId = actorUserId?.toString(),
        source = source,
        reason = reason,
        delta = delta,
        scoreAfter = scoreAfter,
        tierAfter = tierAfter,
        metadataJson = metadataJson,
        createdAt = createdAt.toString(),
    )

private fun AdminRiskActionRecord.toDto(): AdminRiskActionDto =
    AdminRiskActionDto(
        userId = userId.toString(),
        score = score,
        tier = tier,
        accountStatus = accountStatus,
        lastReviewedAt = lastReviewedAt?.toString(),
        updatedAt = updatedAt.toString(),
    )
