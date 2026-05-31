package com.pynapli.server.moderation

import java.time.Instant
import java.util.UUID

interface MessageModerationRepository {
    suspend fun listOpenMessageReviews(
        limit: Int,
        actorId: String,
        reason: String,
    ): List<MessageReviewRecord>

    suspend fun releaseHeldMessage(
        messageId: UUID,
        actorId: String,
        reason: String,
    ): MessageReviewActionRecord?

    suspend fun removeHeldMessage(
        messageId: UUID,
        actorId: String,
        reason: String,
    ): MessageReviewActionRecord?
}

data class MessageReviewRecord(
    val queueId: UUID,
    val messageId: UUID,
    val conversationId: UUID,
    val senderUserId: UUID,
    val body: String,
    val safetyReason: String,
    val queueStatus: String,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class MessageReviewActionRecord(
    val messageId: UUID,
    val conversationId: UUID,
    val senderUserId: UUID,
    val messageStatus: String,
    val queueStatus: String,
    val reviewedAt: Instant,
)
