package com.pynapli.server.safety

import java.time.Instant
import java.util.UUID

interface AppealRepository {
    suspend fun createAppeal(
        userId: UUID,
        actionType: String,
        targetType: String,
        targetId: String?,
        relatedReportId: UUID?,
        reason: String,
        appealIdFactory: () -> UUID = UUID::randomUUID,
    ): CreateAppealResult

    suspend fun listAppeals(
        userId: UUID,
        limit: Int,
    ): List<AppealRecord>

    suspend fun withdrawAppeal(
        userId: UUID,
        appealId: UUID,
    ): AppealRecord?
}

sealed interface CreateAppealResult {
    data class Created(
        val appeal: AppealRecord,
    ) : CreateAppealResult

    data object DuplicateOpenAppeal : CreateAppealResult

    data object TargetNotAppealable : CreateAppealResult

    data object RelatedReportNotFound : CreateAppealResult
}

data class AppealRecord(
    val id: UUID,
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
)
