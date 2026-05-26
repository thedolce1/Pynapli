package com.pynapli.server.safety

import com.pynapli.shared.network.dto.CreateReportRequestDto
import com.pynapli.shared.network.dto.ReportHistoryItemDto
import com.pynapli.shared.network.dto.ReportHistoryListDto
import com.pynapli.shared.network.dto.ReportDto
import com.pynapli.shared.network.dto.SupportContactDto
import com.pynapli.shared.monetization.FeatureAccessPolicy
import com.pynapli.shared.monetization.PynapliFeature
import org.slf4j.LoggerFactory
import java.util.UUID

class ReportService(
    private val repository: ReportRepository,
    private val riskSignalPublisher: ReportRiskSignalPublisher? = null,
    private val rateLimitService: RateLimitService? = null,
    private val supportEmail: String = "support@pynapli.com"
) {
    suspend fun submitReport(reporterUserId: UUID, request: CreateReportRequestDto): ReportDto {
        FeatureAccessPolicy.requireAlwaysFreeSafetyFeature(PynapliFeature.SubmitReport)
        val reportedUserId = request.reportedUserId.toUuidOrThrow()
        if (reportedUserId == reporterUserId) throw SafetyException("REPORT_SELF_FORBIDDEN")

        val targetType = request.targetType.trim().lowercase()
        if (targetType !in TARGET_TYPES) throw SafetyException("INVALID_REPORT_TARGET")

        val category = request.category.trim().lowercase()
        if (category !in CATEGORIES) throw SafetyException("INVALID_REPORT_CATEGORY")

        val targetId = request.targetId?.trim()?.takeIf { it.isNotEmpty() }
        val note = request.note?.trim()?.takeIf { it.isNotEmpty() }
        if (note != null && note.length > MAX_NOTE_LENGTH) throw SafetyException("REPORT_NOTE_TOO_LONG")
        if (rateLimitService?.check(reporterUserId, RateLimitedAction.REPORT)?.allowed == false) {
            throw SafetyException("RATE_LIMITED")
        }

        val record = repository.createReport(
            reporterUserId = reporterUserId,
            reportedUserId = reportedUserId,
            targetType = targetType,
            targetId = targetId,
            category = category,
            note = note
        )

        riskSignalPublisher?.let { publisher ->
            runCatching {
                publisher.recordReportCreated(
                    reportId = record.id,
                    reporterUserId = reporterUserId,
                    reportedUserId = reportedUserId,
                    targetType = targetType,
                    category = category
                )
            }.onFailure { error ->
                logger.warn("Failed to record report risk signal for report {}", record.id, error)
            }
        }

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

    suspend fun listReports(reporterUserId: UUID, limit: Int = DEFAULT_HISTORY_LIMIT): ReportHistoryListDto {
        FeatureAccessPolicy.requireAlwaysFreeSafetyFeature(PynapliFeature.ListReports)
        return ReportHistoryListDto(
            reports = repository.listReportsByReporter(
                reporterUserId = reporterUserId,
                limit = limit.coerceIn(1, MAX_HISTORY_LIMIT)
            ).map { record ->
                ReportHistoryItemDto(
                    reportId = record.id.toString(),
                    reportedDisplayName = record.reportedDisplayName,
                    targetType = record.targetType,
                    category = record.category,
                    status = record.status.toUserVisibleStatus(),
                    createdAt = record.createdAt.toString()
                )
            }
        )
    }

    fun supportContact(userId: UUID): SupportContactDto {
        FeatureAccessPolicy.requireAlwaysFreeSafetyFeature(PynapliFeature.SupportContact)
        val accountReference = userId.toString()
        return SupportContactDto(
            email = supportEmail,
            subject = "Pynapli support request",
            body = "Account reference: $accountReference\n\nHow can we help?"
        )
    }

    private fun String.toUuidOrThrow(): UUID =
        runCatching { UUID.fromString(this) }.getOrElse { throw SafetyException("INVALID_REPORT_TARGET") }

    private fun String.toUserVisibleStatus(): String = when (this) {
        "under_review" -> "under_review"
        "resolved", "dismissed" -> "reviewed"
        else -> "received"
    }

    companion object {
        private val logger = LoggerFactory.getLogger(ReportService::class.java)
        private const val DEFAULT_HISTORY_LIMIT = 25
        private const val MAX_HISTORY_LIMIT = 50
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
