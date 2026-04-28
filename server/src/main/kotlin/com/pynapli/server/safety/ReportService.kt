package com.pynapli.server.safety

import com.pynapli.shared.network.dto.CreateReportRequestDto
import com.pynapli.shared.network.dto.ReportDto
import java.util.UUID

class ReportService(
    private val repository: ReportRepository
) {
    suspend fun submitReport(reporterUserId: UUID, request: CreateReportRequestDto): ReportDto {
        val reportedUserId = request.reportedUserId.toUuidOrThrow()
        if (reportedUserId == reporterUserId) throw SafetyException("REPORT_SELF_FORBIDDEN")

        val targetType = request.targetType.trim().lowercase()
        if (targetType !in TARGET_TYPES) throw SafetyException("INVALID_REPORT_TARGET")

        val category = request.category.trim().lowercase()
        if (category !in CATEGORIES) throw SafetyException("INVALID_REPORT_CATEGORY")

        val targetId = request.targetId?.trim()?.takeIf { it.isNotEmpty() }
        val note = request.note?.trim()?.takeIf { it.isNotEmpty() }
        if (note != null && note.length > MAX_NOTE_LENGTH) throw SafetyException("REPORT_NOTE_TOO_LONG")

        val record = repository.createReport(
            reporterUserId = reporterUserId,
            reportedUserId = reportedUserId,
            targetType = targetType,
            targetId = targetId,
            category = category,
            note = note
        )

        return ReportDto(
            reportId = record.id.toString(),
            reportedUserId = record.reportedUserId.toString(),
            targetType = record.targetType,
            targetId = record.targetId,
            category = record.category,
            status = record.status,
            createdAt = record.createdAt.toString()
        )
    }

    private fun String.toUuidOrThrow(): UUID =
        runCatching { UUID.fromString(this) }.getOrElse { throw SafetyException("INVALID_REPORT_TARGET") }

    companion object {
        private const val MAX_NOTE_LENGTH = 2_000
        private val TARGET_TYPES = setOf("profile", "message", "media", "album", "session")
        private val CATEGORIES = setOf(
            "underage_user",
            "harassment_or_threats",
            "explicit_public_content",
            "spam_or_fake_profile",
            "impersonation",
            "solicitation",
            "non_consensual_content",
            "private_info_doxxing",
            "other"
        )
    }
}
