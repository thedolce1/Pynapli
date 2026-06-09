package com.pynapli.server.moderation

import java.time.Instant
import java.util.UUID

interface AdminAppealRepository {
    suspend fun listAppeals(
        status: String,
        limit: Int,
        actorId: String,
        reason: String,
    ): List<AdminAppealRecord>

    suspend fun markUnderReview(
        appealId: UUID,
        actorId: String,
        reason: String,
    ): AdminAppealActionRecord?

    suspend fun decideAppeal(
        appealId: UUID,
        status: String,
        actorId: String,
        reason: String,
        adminNote: String?,
    ): AdminAppealActionRecord?
}

data class AdminAppealRecord(
    val appealId: UUID,
    val userId: UUID,
    val actionType: String,
    val targetType: String,
    val targetId: String?,
    val relatedReportId: UUID?,
    val status: String,
    val userReason: String,
    val adminNote: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val reviewedAt: Instant?,
    val reviewedByAdminId: String?,
)

data class AdminAppealActionRecord(
    val appealId: UUID,
    val userId: UUID,
    val status: String,
    val actedAt: Instant,
)
