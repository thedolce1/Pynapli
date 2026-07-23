package com.pynapli.shared.network.dto

import kotlinx.serialization.Serializable

/**
 * User-submitted moderation appeal. `subjectType` is one of `account`, `content`, `message`, `media`.
 * `subjectRef` is required for non-account subjects (e.g. the moderated asset/message id) and omitted
 * for an account-level suspension/ban appeal.
 */
@Serializable
data class SubmitAppealRequestDto(
    val subjectType: String,
    val subjectRef: String? = null,
    val reason: String,
)

@Serializable
data class AppealDto(
    val id: String,
    val subjectType: String,
    val subjectRef: String? = null,
    val status: String,
    val createdAt: String,
    val resolvedAt: String? = null,
    val resolutionNote: String? = null,
)

@Serializable
data class AppealListResponseDto(
    val items: List<AppealDto>,
    val nextCursor: String? = null,
)

@Serializable
data class AdminResolveAppealRequestDto(
    val approve: Boolean,
    val reason: String? = null,
)
