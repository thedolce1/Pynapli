package com.pynapli.server.safety

import java.time.Instant
import java.util.UUID

interface ReportRepository {
    suspend fun createReport(
        reporterUserId: UUID,
        reportedUserId: UUID,
        targetType: String,
        targetId: String?,
        category: String,
        note: String?,
        reportIdFactory: () -> UUID = UUID::randomUUID
    ): ReportRecord

    suspend fun listReportsByReporter(
        reporterUserId: UUID,
        limit: Int
    ): List<ReportHistoryRecord>
}

data class ReportRecord(
    val id: UUID,
    val reporterUserId: UUID,
    val reportedUserId: UUID,
    val targetType: String,
    val targetId: String?,
    val category: String,
    val note: String?,
    val status: String,
    val createdAt: Instant
)

data class ReportHistoryRecord(
    val id: UUID,
    val reportedDisplayName: String?,
    val targetType: String,
    val category: String,
    val status: String,
    val createdAt: Instant
)
