package com.pynapli.app.profilehub

import androidx.compose.runtime.Immutable
import com.pynapli.shared.network.dto.AccountDataExportDto
import com.pynapli.shared.network.dto.AgeVerificationStatusDto
import com.pynapli.shared.network.dto.BlockSummaryDto
import com.pynapli.shared.network.dto.HiddenProfileSummaryDto
import com.pynapli.shared.network.dto.MeDto
import com.pynapli.shared.network.dto.ReportHistoryItemDto
import com.pynapli.shared.network.dto.SupportContactDto

@Immutable
data class ProfileHubUiState(
    val me: MeDto? = null,
    val draft: ProfileEditDraft = ProfileEditDraft(),
    val privacyDraft: PrivacySettingsDraft = PrivacySettingsDraft(),
    val activityDraft: ActivitySettingsDraft = ActivitySettingsDraft(),
    val editVisible: Boolean = false,
    val privacyVisible: Boolean = false,
    val activityVisible: Boolean = false,
    val appSettingsVisible: Boolean = false,
    val notificationCenterVisible: Boolean = false,
    val safetyCenterVisible: Boolean = false,
    val ageVerificationDialogVisible: Boolean = false,
    val ageVerificationStatus: AgeVerificationStatusDto? = null,
    val blockedUsers: List<BlockSummaryDto> = emptyList(),
    val hiddenProfiles: List<HiddenProfileSummaryDto> = emptyList(),
    val reportHistory: List<ReportHistoryItemDto> = emptyList(),
    val supportContact: SupportContactDto? = null,
    val latestDataExport: AccountDataExportDto? = null,
    val isAgeVerificationMutating: Boolean = false,
    val isDataExportMutating: Boolean = false,
    val isDeletingAccount: Boolean = false,
    val isBlocksLoading: Boolean = false,
    val isHiddenProfilesLoading: Boolean = false,
    val isReportHistoryLoading: Boolean = false,
    val isSupportContactLoading: Boolean = false,
    val unblockingUserId: String? = null,
    val unhidingUserId: String? = null,
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val errorMessage: String? = null,
    val feedbackMessage: String? = null,
)

@Immutable
data class ProfileEditDraft(
    val displayName: String = "",
    val birthdate: String = "",
    val bio: String = "",
    // Structured taxonomy key ("" = unset); the *Detail value only applies when the
    // key is self_describe (ProfileTaxonomies).
    val gender: String = "",
    val genderDetail: String = "",
    val orientation: String = "",
    val orientationDetail: String = "",
    val relationshipContext: String = "",
    val discoveryStyle: String = "both",
    val lifestyleTags: Set<String> = emptySet(),
    val logisticsTags: Set<String> = emptySet(),
    val intents: Set<String> = emptySet(),
)

@Immutable
data class PrivacySettingsDraft(
    val gridVisible: Boolean = true,
    val swipeVisible: Boolean = true,
    val showDistance: Boolean = true,
    // PROFILE_SETTINGS_SPEC L221-245: when false, the viewer is hidden from
    // every discovery surface while they have an active travel_locations row.
    val visibleWhileTraveling: Boolean = true,
    // PSYNC_SPEC: opt in to let a linked partner see your Psync solo matches in
    // the shared recap. Private (false) by default.
    val sharePsyncSoloMatches: Boolean = false,
)

@Immutable
data class ActivitySettingsDraft(
    val showInViewedLists: Boolean = true,
    val allowGridMessages: Boolean = true,
    val gridMessagePolicy: String = "everyone",
    val allowFlirts: Boolean = true,
    val allowIntroNotes: Boolean = true,
    val allowAlbumRequests: Boolean = true,
)
