package com.pynapli.app.profilehub

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

internal class ProfileHubPanelActions(
    private val state: MutableStateFlow<ProfileHubUiState>,
) {
    fun openEditor() {
        state.update { existing ->
            existing.copy(
                editVisible = true,
                privacyVisible = false,
                activityVisible = false,
                appSettingsVisible = false,
                notificationCenterVisible = false,
                safetyCenterVisible = false,
                ageVerificationDialogVisible = false,
                draft = existing.me?.profile?.toDraft() ?: existing.draft,
                feedbackMessage = null,
                errorMessage = null,
            )
        }
    }

    fun dismissEditor() {
        state.update { existing ->
            existing.copy(
                editVisible = false,
                draft = existing.me?.profile?.toDraft() ?: existing.draft,
                errorMessage = null,
            )
        }
    }

    fun openPrivacySettings() {
        state.update { existing ->
            existing.copy(
                privacyVisible = true,
                editVisible = false,
                activityVisible = false,
                appSettingsVisible = false,
                notificationCenterVisible = false,
                safetyCenterVisible = false,
                ageVerificationDialogVisible = false,
                privacyDraft = existing.me?.discoverySettings?.toPrivacyDraft() ?: existing.privacyDraft,
                feedbackMessage = null,
                errorMessage = null,
            )
        }
    }

    fun dismissPrivacySettings() {
        state.update { existing ->
            existing.copy(
                privacyVisible = false,
                privacyDraft = existing.me?.discoverySettings?.toPrivacyDraft() ?: existing.privacyDraft,
                errorMessage = null,
            )
        }
    }

    fun openPartner() {
        state.update { existing ->
            existing.copy(
                partnerVisible = true,
                editVisible = false,
                privacyVisible = false,
                activityVisible = false,
                appSettingsVisible = false,
                notificationCenterVisible = false,
                safetyCenterVisible = false,
                ageVerificationDialogVisible = false,
                privacyDraft = existing.me?.discoverySettings?.toPrivacyDraft() ?: existing.privacyDraft,
                feedbackMessage = null,
                errorMessage = null,
            )
        }
    }

    fun dismissPartner() {
        state.update { existing ->
            existing.copy(
                partnerVisible = false,
                privacyDraft = existing.me?.discoverySettings?.toPrivacyDraft() ?: existing.privacyDraft,
                errorMessage = null,
            )
        }
    }

    fun openActivityControls() {
        state.update { existing ->
            existing.copy(
                activityVisible = true,
                editVisible = false,
                privacyVisible = false,
                partnerVisible = false,
                appSettingsVisible = false,
                notificationCenterVisible = false,
                safetyCenterVisible = false,
                ageVerificationDialogVisible = false,
                activityDraft = existing.me?.discoverySettings?.toActivityDraft() ?: existing.activityDraft,
                feedbackMessage = null,
                errorMessage = null,
            )
        }
    }

    fun dismissActivityControls() {
        state.update { existing ->
            existing.copy(
                activityVisible = false,
                activityDraft = existing.me?.discoverySettings?.toActivityDraft() ?: existing.activityDraft,
                errorMessage = null,
            )
        }
    }

    fun openAppSettings() {
        state.update { existing ->
            existing.copy(
                appSettingsVisible = true,
                editVisible = false,
                privacyVisible = false,
                activityVisible = false,
                notificationCenterVisible = false,
                safetyCenterVisible = false,
                ageVerificationDialogVisible = false,
                feedbackMessage = null,
                errorMessage = null,
            )
        }
    }

    fun dismissAppSettings() {
        state.update { it.copy(appSettingsVisible = false, errorMessage = null) }
    }

    fun openNotificationCenter() {
        state.update { existing ->
            existing.copy(
                notificationCenterVisible = true,
                editVisible = false,
                privacyVisible = false,
                activityVisible = false,
                appSettingsVisible = false,
                safetyCenterVisible = false,
                ageVerificationDialogVisible = false,
                feedbackMessage = null,
                errorMessage = null,
            )
        }
    }

    fun dismissNotificationCenter() {
        state.update { it.copy(notificationCenterVisible = false, errorMessage = null) }
    }

    fun openAgeVerification() {
        state.update { existing ->
            existing.copy(
                ageVerificationDialogVisible = true,
                editVisible = false,
                privacyVisible = false,
                activityVisible = false,
                appSettingsVisible = false,
                notificationCenterVisible = false,
                safetyCenterVisible = false,
                feedbackMessage = null,
                errorMessage = null,
            )
        }
    }

    fun dismissAgeVerification() {
        state.update { it.copy(ageVerificationDialogVisible = false, errorMessage = null) }
    }

    fun openSafetyCenter() {
        state.update { existing ->
            existing.copy(
                safetyCenterVisible = true,
                editVisible = false,
                privacyVisible = false,
                activityVisible = false,
                appSettingsVisible = false,
                notificationCenterVisible = false,
                ageVerificationDialogVisible = false,
                feedbackMessage = null,
                errorMessage = null,
            )
        }
    }

    fun dismissSafetyCenter() {
        state.update {
            it.copy(
                safetyCenterVisible = false,
                isBlocksLoading = false,
                isHiddenProfilesLoading = false,
                isReportHistoryLoading = false,
                isSupportContactLoading = false,
                unblockingUserId = null,
                unhidingUserId = null,
                errorMessage = null,
            )
        }
    }

    fun closeAppSettingsWithFeedback(message: String) {
        state.update {
            it.copy(
                appSettingsVisible = false,
                errorMessage = null,
                feedbackMessage = message,
            )
        }
    }
}
