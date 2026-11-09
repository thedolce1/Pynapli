package com.pynapli.shared.network.dto

import kotlinx.serialization.Serializable

object ReportTargetType {
    const val PROFILE = "profile"
    const val MESSAGE = "message"
    const val MEDIA = "media"
    const val ALBUM = "album"
    const val SESSION = "session"
}

object ReportCategory {
    const val UNDERAGE_USER = "underage_user"
    const val HARASSMENT_OR_THREATS = "harassment_or_threats"
    const val EXPLICIT_PUBLIC_CONTENT = "explicit_public_content"
    const val SPAM_OR_FAKE_PROFILE = "spam_or_fake_profile"
    const val IMPERSONATION = "impersonation"
    const val SOLICITATION = "solicitation"
    const val NON_CONSENSUAL_CONTENT = "non_consensual_content"
    const val PRIVATE_INFO_DOXXING = "private_info_doxxing"
    const val OTHER = "other"

    val ALL: List<String> =
        listOf(
            UNDERAGE_USER,
            HARASSMENT_OR_THREATS,
            EXPLICIT_PUBLIC_CONTENT,
            SPAM_OR_FAKE_PROFILE,
            IMPERSONATION,
            SOLICITATION,
            NON_CONSENSUAL_CONTENT,
            PRIVATE_INFO_DOXXING,
            OTHER,
        )
}

@Serializable
data class BlockUserRequestDto(
    val reason: String? = null,
)

@Serializable
data class BlockSummaryDto(
    val blockedUserId: String,
    val blockedDisplayName: String? = null,
    val reason: String? = null,
    val createdAt: String,
)

@Serializable
data class BlocksListDto(
    val blocks: List<BlockSummaryDto>,
)

@Serializable
data class HiddenProfileSummaryDto(
    val hiddenUserId: String,
    val hiddenDisplayName: String? = null,
    val createdAt: String,
)

@Serializable
data class HiddenProfilesListDto(
    val hiddenProfiles: List<HiddenProfileSummaryDto>,
)

@Serializable
data class CreateReportRequestDto(
    val reportedUserId: String,
    val targetType: String,
    val targetId: String? = null,
    val category: String,
    val note: String? = null,
)

@Serializable
data class ReportDto(
    val reportId: String,
    val reportedUserId: String,
    val targetType: String,
    val targetId: String? = null,
    val category: String,
    val status: String,
    val createdAt: String,
)

@Serializable
data class ReportHistoryItemDto(
    val reportId: String,
    val reportedDisplayName: String? = null,
    val targetType: String,
    val category: String,
    val status: String,
    val createdAt: String,
)

@Serializable
data class ReportHistoryListDto(
    val reports: List<ReportHistoryItemDto>,
)

@Serializable
data class SupportContactDto(
    val email: String,
    val subject: String,
    val body: String,
)

const val APPEAL_REASON_MAX_LENGTH = 2_000

@Serializable
data class CreateAppealRequestDto(
    val actionType: String,
    val targetType: String,
    val targetId: String? = null,
    val relatedReportId: String? = null,
    val reason: String,
)

@Serializable
data class AppealDto(
    val appealId: String,
    val actionType: String,
    val targetType: String,
    val targetId: String? = null,
    val relatedReportId: String? = null,
    val status: String,
    val userReason: String,
    val adminNote: String? = null,
    val createdAt: String,
    val updatedAt: String,
    val reviewedAt: String? = null,
)

@Serializable
data class AppealListDto(
    val appeals: List<AppealDto>,
)
