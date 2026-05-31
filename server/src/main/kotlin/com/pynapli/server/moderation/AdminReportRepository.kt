package com.pynapli.server.moderation

import java.time.Instant
import java.util.UUID

interface AdminReportRepository {
    suspend fun listReports(
        status: String,
        limit: Int,
        actorId: String,
        reason: String,
    ): List<AdminReportRecord>

    suspend fun updateReportStatus(
        reportId: UUID,
        status: String,
        actorId: String,
        reason: String,
    ): AdminReportActionRecord?
}

data class AdminReportRecord(
    val reportId: UUID,
    val reporterUserId: UUID,
    val reportedUserId: UUID,
    val targetType: String,
    val targetId: String?,
    val category: String,
    val note: String?,
    val status: String,
    val createdAt: Instant,
    val updatedAt: Instant,
    val reviewedAt: Instant?,
)

data class AdminReportActionRecord(
    val reportId: UUID,
    val status: String,
    val actedAt: Instant,
)
