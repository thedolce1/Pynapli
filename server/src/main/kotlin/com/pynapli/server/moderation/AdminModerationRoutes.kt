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

fun Application.configureAdminModerationRoutes(
    config: ServerConfig,
    moderationService: MessageModerationService?,
) {
    val adminApiToken = config.adminApiToken ?: return
    if (moderationService == null) return

    routing {
        route("/v1/admin/messages/review-queue") {
            get {
                val actor = call.requireAdminActor(adminApiToken)
                val reason = call.requiredAdminReason()
                val items =
                    moderationService.listOpenMessageReviews(
                        limitText = call.request.queryParameters["limit"],
                        actorId = actor.actorId,
                        reason = reason,
                    )
                call.respond(AdminMessageReviewQueueDto(items = items.map { it.toDto() }))
            }

            post("/{messageId}/release") {
                val actor = call.requireAdminActor(adminApiToken)
                val messageId = call.parameters["messageId"] ?: throw ModerationException("INVALID_ID")
                val request =
                    call.receiveNullable<AdminMessageReviewActionRequestDto>()
                        ?: throw ModerationException("MODERATION_REASON_REQUIRED")
                call.respond(
                    moderationService
                        .releaseHeldMessage(
                            messageIdText = messageId,
                            actorId = actor.actorId,
                            reason = request.reason,
                        ).toDto(),
                )
            }

            post("/{messageId}/remove") {
                val actor = call.requireAdminActor(adminApiToken)
                val messageId = call.parameters["messageId"] ?: throw ModerationException("INVALID_ID")
                val request =
                    call.receiveNullable<AdminMessageReviewActionRequestDto>()
                        ?: throw ModerationException("MODERATION_REASON_REQUIRED")
                call.respond(
                    moderationService
                        .removeHeldMessage(
                            messageIdText = messageId,
                            actorId = actor.actorId,
                            reason = request.reason,
                        ).toDto(),
                )
            }
        }
    }
}

@Serializable
data class AdminMessageReviewQueueDto(
    val items: List<AdminMessageReviewItemDto>,
)

@Serializable
data class AdminMessageReviewItemDto(
    val queueId: String,
    val messageId: String,
    val conversationId: String,
    val senderUserId: String,
    val body: String,
    val safetyReason: String,
    val queueStatus: String,
    val createdAt: String,
    val updatedAt: String,
)

@Serializable
data class AdminMessageReviewActionRequestDto(
    val reason: String? = null,
)

@Serializable
data class AdminMessageReviewActionDto(
    val messageId: String,
    val conversationId: String,
    val senderUserId: String,
    val messageStatus: String,
    val queueStatus: String,
    val reviewedAt: String,
)

private fun MessageReviewRecord.toDto(): AdminMessageReviewItemDto =
    AdminMessageReviewItemDto(
        queueId = queueId.toString(),
        messageId = messageId.toString(),
        conversationId = conversationId.toString(),
        senderUserId = senderUserId.toString(),
        body = body,
        safetyReason = safetyReason,
        queueStatus = queueStatus,
        createdAt = createdAt.toString(),
        updatedAt = updatedAt.toString(),
    )

private fun MessageReviewActionRecord.toDto(): AdminMessageReviewActionDto =
    AdminMessageReviewActionDto(
        messageId = messageId.toString(),
        conversationId = conversationId.toString(),
        senderUserId = senderUserId.toString(),
        messageStatus = messageStatus,
        queueStatus = queueStatus,
        reviewedAt = reviewedAt.toString(),
    )
