package com.pynapli.server.moderation

import java.util.UUID

class MessageModerationService(
    private val repository: MessageModerationRepository,
) {
    suspend fun listOpenMessageReviews(
        limitText: String?,
        actorId: String,
        reason: String,
    ): List<MessageReviewRecord> {
        val limit =
            limitText
                ?.takeIf { it.isNotBlank() }
                ?.toIntOrNull()
                ?.takeIf { it in 1..MAX_REVIEW_LIMIT }
                ?: if (limitText.isNullOrBlank()) DEFAULT_REVIEW_LIMIT else throw ModerationException("INVALID_REVIEW_LIMIT")
        return repository.listOpenMessageReviews(
            limit = limit,
            actorId = actorId.normalizedActorIdOrThrow(),
            reason = reason.normalizedReasonOrThrow(),
        )
    }

    suspend fun releaseHeldMessage(
        messageIdText: String,
        actorId: String,
        reason: String?,
    ): MessageReviewActionRecord =
        repository.releaseHeldMessage(
            messageId = messageIdText.toUuidOrThrow(),
            actorId = actorId.normalizedActorIdOrThrow(),
            reason = reason.normalizedReasonOrThrow(),
        ) ?: throw ModerationException("MESSAGE_REVIEW_NOT_FOUND")

    suspend fun removeHeldMessage(
        messageIdText: String,
        actorId: String,
        reason: String?,
    ): MessageReviewActionRecord =
        repository.removeHeldMessage(
            messageId = messageIdText.toUuidOrThrow(),
            actorId = actorId.normalizedActorIdOrThrow(),
            reason = reason.normalizedReasonOrThrow(),
        ) ?: throw ModerationException("MESSAGE_REVIEW_NOT_FOUND")

    private fun String.toUuidOrThrow(): UUID = runCatching { UUID.fromString(this) }.getOrElse { throw ModerationException("INVALID_ID") }

    private fun String.normalizedActorIdOrThrow(): String {
        val normalized = trim()
        if (normalized.length !in 2..120) throw ModerationException("INVALID_ADMIN_ACTOR")
        return normalized
    }

    private fun String?.normalizedReasonOrThrow(): String {
        val normalized = this?.trim().orEmpty()
        if (normalized.isEmpty()) throw ModerationException("MODERATION_REASON_REQUIRED")
        if (normalized.length > 500) throw ModerationException("MODERATION_REASON_TOO_LONG")
        return normalized
    }

    companion object {
        private const val DEFAULT_REVIEW_LIMIT = 50
        private const val MAX_REVIEW_LIMIT = 100
    }
}

class ModerationException(
    val code: String,
) : RuntimeException(code)
