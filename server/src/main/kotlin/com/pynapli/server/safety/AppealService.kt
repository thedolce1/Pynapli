package com.pynapli.server.safety

import com.pynapli.shared.network.dto.APPEAL_REASON_MAX_LENGTH
import com.pynapli.shared.network.dto.AppealDto
import com.pynapli.shared.network.dto.AppealListDto
import com.pynapli.shared.network.dto.CreateAppealRequestDto
import java.util.UUID

class AppealService(
    private val repository: AppealRepository,
) {
    suspend fun submitAppeal(
        userId: UUID,
        request: CreateAppealRequestDto,
    ): AppealDto {
        val actionType = request.actionType.normalizedIn(ACTION_TYPES, "INVALID_APPEAL_ACTION")
        val targetType = request.targetType.normalizedIn(TARGET_TYPES, "INVALID_APPEAL_TARGET")
        validateActionTarget(actionType, targetType)

        val targetId = request.targetId?.trim()?.takeIf { it.isNotEmpty() }
        if (targetType in TARGET_TYPES_REQUIRING_ID && targetId == null) throw SafetyException("APPEAL_TARGET_REQUIRED")
        if (targetType == "account" && targetId != null) throw SafetyException("INVALID_APPEAL_TARGET")
        if (targetType in UUID_TARGET_TYPES && targetId != null) targetId.toUuidOrThrow("INVALID_APPEAL_TARGET")

        val relatedReportId =
            request.relatedReportId
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.toUuidOrThrow("INVALID_APPEAL_REPORT")

        val reason = request.reason.trim()
        if (reason.isEmpty()) throw SafetyException("APPEAL_REASON_REQUIRED")
        if (reason.length > APPEAL_REASON_MAX_LENGTH) throw SafetyException("APPEAL_REASON_TOO_LONG")

        return when (
            val result =
                repository.createAppeal(
                    userId = userId,
                    actionType = actionType,
                    targetType = targetType,
                    targetId = targetId,
                    relatedReportId = relatedReportId,
                    reason = reason,
                )
        ) {
            is CreateAppealResult.Created -> result.appeal.toDto()
            CreateAppealResult.DuplicateOpenAppeal -> throw SafetyException("APPEAL_ALREADY_OPEN")
            CreateAppealResult.RelatedReportNotFound -> throw SafetyException("APPEAL_REPORT_NOT_FOUND")
            CreateAppealResult.TargetNotAppealable -> throw SafetyException("APPEAL_TARGET_NOT_APPEALABLE")
        }
    }

    suspend fun listAppeals(
        userId: UUID,
        limit: Int,
    ): AppealListDto =
        AppealListDto(
            appeals =
                repository
                    .listAppeals(userId, limit.coerceIn(1, MAX_APPEAL_HISTORY_LIMIT))
                    .map { it.toDto() },
        )

    suspend fun withdrawAppeal(
        userId: UUID,
        appealIdText: String,
    ): AppealDto =
        repository
            .withdrawAppeal(
                userId = userId,
                appealId = appealIdText.toUuidOrThrow("INVALID_APPEAL_ID"),
            )?.toDto()
            ?: throw SafetyException("APPEAL_NOT_FOUND")

    private fun validateActionTarget(
        actionType: String,
        targetType: String,
    ) {
        val allowed =
            when (actionType) {
                "account_suspension", "account_ban", "risk_restriction" -> targetType == "account"
                "media_removal" -> targetType == "media"
                "message_removal" -> targetType == "message"
                "other" -> targetType in TARGET_TYPES
                else -> false
            }
        if (!allowed) throw SafetyException("INVALID_APPEAL_TARGET")
    }

    private fun String.normalizedIn(
        allowed: Set<String>,
        code: String,
    ): String {
        val normalized = trim().lowercase()
        if (normalized !in allowed) throw SafetyException(code)
        return normalized
    }

    private fun String.toUuidOrThrow(code: String): UUID = runCatching { UUID.fromString(this) }.getOrElse { throw SafetyException(code) }

    private fun AppealRecord.toDto(): AppealDto =
        AppealDto(
            appealId = id.toString(),
            actionType = actionType,
            targetType = targetType,
            targetId = targetId,
            relatedReportId = relatedReportId?.toString(),
            status = status,
            userReason = userReason,
            adminNote = adminNote,
            createdAt = createdAt.toString(),
            updatedAt = updatedAt.toString(),
            reviewedAt = reviewedAt?.toString(),
        )

    companion object {
        private const val MAX_APPEAL_HISTORY_LIMIT = 50
        private val ACTION_TYPES =
            setOf(
                "account_suspension",
                "account_ban",
                "risk_restriction",
                "media_removal",
                "message_removal",
                "other",
            )
        private val TARGET_TYPES = setOf("account", "media", "message", "profile", "other")
        private val TARGET_TYPES_REQUIRING_ID = setOf("media", "message", "profile")
        private val UUID_TARGET_TYPES = setOf("media", "message", "profile")
    }
}
