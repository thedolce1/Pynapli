package com.pynapli.server.moderation

import java.util.UUID

class AdminAppealService(
    private val repository: AdminAppealRepository,
) {
    suspend fun listAppeals(
        statusText: String?,
        limitText: String?,
        actorId: String,
        reason: String,
    ): List<AdminAppealRecord> {
        val status = statusText?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: "open"
        if (status !in LIST_STATUSES) throw ModerationException("INVALID_APPEAL_REVIEW_STATUS")
        val limit =
            limitText
                ?.takeIf { it.isNotBlank() }
                ?.toIntOrNull()
                ?.takeIf { it in 1..MAX_APPEAL_REVIEW_LIMIT }
                ?: if (limitText.isNullOrBlank()) DEFAULT_APPEAL_REVIEW_LIMIT else throw ModerationException("INVALID_REVIEW_LIMIT")
        return repository.listAppeals(
            status = status,
            limit = limit,
            actorId = actorId.normalizedActorIdOrThrow(),
            reason = reason.normalizedReasonOrThrow(),
        )
    }

    suspend fun markUnderReview(
        appealIdText: String,
        actorId: String,
        reason: String?,
    ): AdminAppealActionRecord =
        repository.markUnderReview(
            appealId = appealIdText.toUuidOrThrow(),
            actorId = actorId.normalizedActorIdOrThrow(),
            reason = reason.normalizedReasonOrThrow(),
        ) ?: throw ModerationException("APPEAL_REVIEW_NOT_FOUND")

    suspend fun decideAppeal(
        appealIdText: String,
        outcomeText: String?,
        actorId: String,
        reason: String?,
        adminNote: String?,
    ): AdminAppealActionRecord {
        val outcome =
            outcomeText
                ?.trim()
                ?.lowercase()
                ?.takeIf { it.isNotEmpty() }
                ?: throw ModerationException("INVALID_APPEAL_OUTCOME")
        if (outcome !in DECISION_STATUSES) throw ModerationException("INVALID_APPEAL_OUTCOME")
        val normalizedNote = adminNote?.trim()?.takeIf { it.isNotEmpty() }
        if (normalizedNote != null && normalizedNote.length > MAX_ADMIN_NOTE_LENGTH) {
            throw ModerationException("APPEAL_ADMIN_NOTE_TOO_LONG")
        }
        return repository.decideAppeal(
            appealId = appealIdText.toUuidOrThrow(),
            status = outcome,
            actorId = actorId.normalizedActorIdOrThrow(),
            reason = reason.normalizedReasonOrThrow(),
            adminNote = normalizedNote,
        ) ?: throw ModerationException("APPEAL_REVIEW_NOT_FOUND")
    }

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
        private const val DEFAULT_APPEAL_REVIEW_LIMIT = 50
        private const val MAX_APPEAL_REVIEW_LIMIT = 100
        private const val MAX_ADMIN_NOTE_LENGTH = 2_000
        private val LIST_STATUSES =
            setOf(
                "open",
                "under_review",
                "upheld",
                "reduced",
                "restored",
                "verification_required",
                "escalated",
                "withdrawn",
                "all",
            )
        private val DECISION_STATUSES =
            setOf(
                "upheld",
                "reduced",
                "restored",
                "verification_required",
                "escalated",
            )
    }
}
