package com.pynapli.server.moderation

import java.util.UUID

class AdminReportService(
    private val repository: AdminReportRepository
) {
    suspend fun listReports(
        statusText: String?,
        limitText: String?,
        actorId: String,
        reason: String
    ): List<AdminReportRecord> {
        val status = statusText?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: "open"
        if (status !in LIST_STATUSES) throw ModerationException("INVALID_REPORT_REVIEW_STATUS")
        val limit = limitText?.takeIf { it.isNotBlank() }?.toIntOrNull()
            ?.takeIf { it in 1..MAX_REPORT_REVIEW_LIMIT }
            ?: if (limitText.isNullOrBlank()) DEFAULT_REPORT_REVIEW_LIMIT else throw ModerationException("INVALID_REVIEW_LIMIT")
        return repository.listReports(
            status = status,
            limit = limit,
            actorId = actorId.normalizedActorIdOrThrow(),
            reason = reason.normalizedReasonOrThrow()
        )
    }

    suspend fun markUnderReview(
        reportIdText: String,
        actorId: String,
        reason: String?
    ): AdminReportActionRecord =
        updateReportStatus(reportIdText, "under_review", actorId, reason)

    suspend fun resolveReport(
        reportIdText: String,
        actorId: String,
        reason: String?
    ): AdminReportActionRecord =
        updateReportStatus(reportIdText, "resolved", actorId, reason)

    suspend fun dismissReport(
        reportIdText: String,
        actorId: String,
        reason: String?
    ): AdminReportActionRecord =
        updateReportStatus(reportIdText, "dismissed", actorId, reason)

    private suspend fun updateReportStatus(
        reportIdText: String,
        status: String,
        actorId: String,
        reason: String?
    ): AdminReportActionRecord =
        repository.updateReportStatus(
            reportId = reportIdText.toUuidOrThrow(),
            status = status,
            actorId = actorId.normalizedActorIdOrThrow(),
            reason = reason.normalizedReasonOrThrow()
        ) ?: throw ModerationException("REPORT_REVIEW_NOT_FOUND")

    private fun String.toUuidOrThrow(): UUID =
        runCatching { UUID.fromString(this) }.getOrElse { throw ModerationException("INVALID_ID") }

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
        private const val DEFAULT_REPORT_REVIEW_LIMIT = 50
        private const val MAX_REPORT_REVIEW_LIMIT = 100
        private val LIST_STATUSES = setOf("open", "under_review", "resolved", "dismissed", "all")
    }
}
