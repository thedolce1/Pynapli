package com.pynapli.app.profilehub

import androidx.compose.runtime.Immutable
import com.pynapli.shared.network.dto.AccountDataExportDto
import com.pynapli.shared.network.dto.AgeVerificationStatusDto
import com.pynapli.shared.network.dto.BlockSummaryDto
import com.pynapli.shared.network.dto.HiddenProfileSummaryDto
import com.pynapli.shared.network.dto.MeDto
import com.pynapli.shared.network.dto.ReportHistoryItemDto
import com.pynapli.shared.network.dto.SubscriptionEntitlementDto
import com.pynapli.shared.network.dto.SupportContactDto

@Immutable
data class ProfileHubUiState(
    val me: MeDto? = null,
    val entitlement: SubscriptionEntitlementDto? = null,
    val draft: ProfileEditDraft = ProfileEditDraft(),
    val privacyDraft: PrivacySettingsDraft = PrivacySettingsDraft(),
    val activityDraft: ActivitySettingsDraft = ActivitySettingsDraft(),
    val editVisible: Boolean = false,
    val privacyVisible: Boolean = false,
    val partnerVisible: Boolean = false,
    val activityVisible: Boolean = false,
    val appSettingsVisible: Boolean = false,
    val notificationCenterVisible: Boolean = false,
    val safetyCenterVisible: Boolean = false,
    val ageVerificationDialogVisible: Boolean = false,
    val ageVerificationStatus: AgeVerificationStatusDto? = null,
    val blockedUsers: List<BlockSummaryDto> = emptyList(),
    val hiddenProfiles: List<HiddenProfileSummaryDto> = emptyList(),
    val reportHistory: List<ReportHistoryItemDto> = emptyList(),
    val appeals: List<com.pynapli.shared.network.dto.AppealDto> = emptyList(),
    val isSubmittingAppeal: Boolean = false,
    val appealMessage: String? = null,
    val appealMessageIsError: Boolean = false,
    val supportContact: SupportContactDto? = null,
    val latestDataExport: AccountDataExportDto? = null,
    val isAgeVerificationMutating: Boolean = false,
    val isDataExportMutating: Boolean = false,
    val isDeletingAccount: Boolean = false,
    val isChangingPassword: Boolean = false,
    // "Where you're signed in": null until first loaded.
    val signedInDevices: List<com.pynapli.shared.network.dto.SignedInDeviceDto>? = null,
    val isLoadingDevices: Boolean = false,
    val signingOutDeviceIds: Set<String> = emptySet(),
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
    // User-declared neighborhood / area label ("" = unset). Shown in the profile peek
    // meta row; self-declared, never derived from GPS.
    val neighborhood: String = "",
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
    // PROFILE_SETTINGS_SPEC partner visibility: public | matches_only | verified_only | hidden.
    val partnerVisibility: String = "public",
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
