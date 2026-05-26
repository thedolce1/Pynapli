package com.pynapli.app.profilehub

import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.network.dto.StartAgeVerificationRequestDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class ProfileHubStateHolder(
    private val repository: ProfileHubRepository
) {
    private val _state = MutableStateFlow(ProfileHubUiState())
    val state: StateFlow<ProfileHubUiState> = _state.asStateFlow()

    suspend fun load() {
        if (state.value.isLoading) return
        _state.update { it.copy(isLoading = true, errorMessage = null) }

        when (val result = repository.loadMe()) {
            is AppResult.Success -> {
                val ageVerificationStatus = when (val status = repository.loadAgeVerificationStatus()) {
                    is AppResult.Success -> status.value
                    is AppResult.Failure -> null
                }
                _state.update { existing ->
                    existing.copy(
                        me = result.value,
                        ageVerificationStatus = ageVerificationStatus,
                        draft = if (existing.editVisible) existing.draft else result.value.profile.toDraft(),
                        privacyDraft = if (existing.privacyVisible) {
                            existing.privacyDraft
                        } else {
                            result.value.discoverySettings.toPrivacyDraft()
                        },
                        activityDraft = if (existing.activityVisible) {
                            existing.activityDraft
                        } else {
                            result.value.discoverySettings.toActivityDraft()
                        },
                        isLoading = false,
                        errorMessage = null
                    )
                }
            }

            is AppResult.Failure -> {
                _state.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = result.error.message
                    )
                }
            }
        }
    }

    fun openEditor() {
        _state.update { existing ->
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
                errorMessage = null
            )
        }
    }

    fun dismissEditor() {
        _state.update { existing ->
            existing.copy(
                editVisible = false,
                draft = existing.me?.profile?.toDraft() ?: existing.draft,
                errorMessage = null
            )
        }
    }

    fun openPrivacySettings() {
        _state.update { existing ->
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
                errorMessage = null
            )
        }
    }

    fun dismissPrivacySettings() {
        _state.update { existing ->
            existing.copy(
                privacyVisible = false,
                privacyDraft = existing.me?.discoverySettings?.toPrivacyDraft() ?: existing.privacyDraft,
                errorMessage = null
            )
        }
    }

    fun openActivityControls() {
        _state.update { existing ->
            existing.copy(
                activityVisible = true,
                editVisible = false,
                privacyVisible = false,
                appSettingsVisible = false,
                notificationCenterVisible = false,
                safetyCenterVisible = false,
                ageVerificationDialogVisible = false,
                activityDraft = existing.me?.discoverySettings?.toActivityDraft() ?: existing.activityDraft,
                feedbackMessage = null,
                errorMessage = null
            )
        }
    }

    fun dismissActivityControls() {
        _state.update { existing ->
            existing.copy(
                activityVisible = false,
                activityDraft = existing.me?.discoverySettings?.toActivityDraft() ?: existing.activityDraft,
                errorMessage = null
            )
        }
    }

    fun openAppSettings() {
        _state.update { existing ->
            existing.copy(
                appSettingsVisible = true,
                editVisible = false,
                privacyVisible = false,
                activityVisible = false,
                notificationCenterVisible = false,
                safetyCenterVisible = false,
                ageVerificationDialogVisible = false,
                feedbackMessage = null,
                errorMessage = null
            )
        }
    }

    fun dismissAppSettings() {
        _state.update { it.copy(appSettingsVisible = false, errorMessage = null) }
    }

    fun openNotificationCenter() {
        _state.update { existing ->
            existing.copy(
                notificationCenterVisible = true,
                editVisible = false,
                privacyVisible = false,
                activityVisible = false,
                appSettingsVisible = false,
                safetyCenterVisible = false,
                ageVerificationDialogVisible = false,
                feedbackMessage = null,
                errorMessage = null
            )
        }
    }

    fun dismissNotificationCenter() {
        _state.update { it.copy(notificationCenterVisible = false, errorMessage = null) }
    }

    fun openAgeVerification() {
        _state.update { existing ->
            existing.copy(
                ageVerificationDialogVisible = true,
                editVisible = false,
                privacyVisible = false,
                activityVisible = false,
                appSettingsVisible = false,
                notificationCenterVisible = false,
                safetyCenterVisible = false,
                feedbackMessage = null,
                errorMessage = null
            )
        }
    }

    fun dismissAgeVerification() {
        _state.update { it.copy(ageVerificationDialogVisible = false, errorMessage = null) }
    }

    fun openSafetyCenter() {
        _state.update { existing ->
            existing.copy(
                safetyCenterVisible = true,
                editVisible = false,
                privacyVisible = false,
                activityVisible = false,
                appSettingsVisible = false,
                notificationCenterVisible = false,
                ageVerificationDialogVisible = false,
                feedbackMessage = null,
                errorMessage = null
            )
        }
    }

    fun dismissSafetyCenter() {
        _state.update {
            it.copy(
                safetyCenterVisible = false,
                isBlocksLoading = false,
                isHiddenProfilesLoading = false,
                unblockingUserId = null,
                unhidingUserId = null,
                errorMessage = null
            )
        }
    }

    fun closeAppSettingsWithFeedback(message: String) {
        _state.update {
            it.copy(
                appSettingsVisible = false,
                errorMessage = null,
                feedbackMessage = message
            )
        }
    }

    fun updateDisplayName(value: String) = updateDraft { it.copy(displayName = value) }

    fun updateBirthdate(value: String) = updateDraft { it.copy(birthdate = value) }

    fun updateGender(value: String) = updateDraft { it.copy(gender = value) }

    fun updateOrientation(value: String) = updateDraft { it.copy(orientation = value) }

    fun updateRelationshipContext(value: String) = updateDraft { it.copy(relationshipContext = value) }

    fun updateDiscoveryStyle(value: String) = updateDraft { it.copy(discoveryStyle = value) }

    fun updateGridVisible(value: Boolean) = updatePrivacyDraft { it.copy(gridVisible = value) }

    fun updateSwipeVisible(value: Boolean) = updatePrivacyDraft { it.copy(swipeVisible = value) }

    fun updateShowDistance(value: Boolean) = updatePrivacyDraft { it.copy(showDistance = value) }

    fun updateVisibleWhileTraveling(value: Boolean) = updatePrivacyDraft { it.copy(visibleWhileTraveling = value) }

    fun updateShowInViewedLists(value: Boolean) = updateActivityDraft { it.copy(showInViewedLists = value) }

    fun updateAllowGridMessages(value: Boolean) = updateActivityDraft {
        it.copy(
            allowGridMessages = value,
            gridMessagePolicy = when {
                value && it.gridMessagePolicy == "no_one" -> "everyone"
                !value -> "no_one"
                else -> it.gridMessagePolicy
            }
        )
    }

    fun updateGridMessagePolicy(value: String) = updateActivityDraft {
        val normalized = value.lowercase()
        it.copy(
            gridMessagePolicy = normalized,
            allowGridMessages = normalized != "no_one"
        )
    }

    fun updateAllowFlirts(value: Boolean) = updateActivityDraft { it.copy(allowFlirts = value) }

    fun updateAllowIntroNotes(value: Boolean) = updateActivityDraft { it.copy(allowIntroNotes = value) }

    fun updateAllowAlbumRequests(value: Boolean) = updateActivityDraft { it.copy(allowAlbumRequests = value) }

    fun clearMessage() {
        _state.update { it.copy(errorMessage = null, feedbackMessage = null) }
    }

    suspend fun loadBlocks() {
        if (state.value.isBlocksLoading) return

        _state.update {
            it.copy(
                isBlocksLoading = true,
                errorMessage = null,
                feedbackMessage = null
            )
        }

        when (val result = repository.listBlocks()) {
            is AppResult.Success -> {
                _state.update {
                    it.copy(
                        blockedUsers = result.value.blocks,
                        isBlocksLoading = false,
                        errorMessage = null
                    )
                }
            }

            is AppResult.Failure -> {
                _state.update {
                    it.copy(
                        isBlocksLoading = false,
                        errorMessage = result.error.message
                    )
                }
            }
        }
    }

    suspend fun loadHiddenProfiles() {
        if (state.value.isHiddenProfilesLoading) return

        _state.update {
            it.copy(
                isHiddenProfilesLoading = true,
                errorMessage = null,
                feedbackMessage = null
            )
        }

        when (val result = repository.listHiddenProfiles()) {
            is AppResult.Success -> {
                _state.update {
                    it.copy(
                        hiddenProfiles = result.value.hiddenProfiles,
                        isHiddenProfilesLoading = false,
                        errorMessage = null
                    )
                }
            }

            is AppResult.Failure -> {
                _state.update {
                    it.copy(
                        isHiddenProfilesLoading = false,
                        errorMessage = result.error.message
                    )
                }
            }
        }
    }

    suspend fun loadSafetyLists() {
        loadBlocks()
        loadHiddenProfiles()
    }

    suspend fun unblockUser(userId: String) {
        val current = state.value
        if (current.unblockingUserId != null) return

        _state.update {
            it.copy(
                unblockingUserId = userId,
                errorMessage = null,
                feedbackMessage = null
            )
        }

        when (val result = repository.unblockUser(userId)) {
            is AppResult.Success -> {
                _state.update {
                    it.copy(
                        blockedUsers = it.blockedUsers.filterNot { block -> block.blockedUserId == userId },
                        unblockingUserId = null,
                        errorMessage = null,
                        feedbackMessage = "User unblocked"
                    )
                }
            }

            is AppResult.Failure -> {
                _state.update {
                    it.copy(
                        unblockingUserId = null,
                        errorMessage = result.error.message
                    )
                }
            }
        }
    }

    suspend fun unhideProfile(userId: String) {
        val current = state.value
        if (current.unhidingUserId != null) return

        _state.update {
            it.copy(
                unhidingUserId = userId,
                errorMessage = null,
                feedbackMessage = null
            )
        }

        when (val result = repository.unhideProfile(userId)) {
            is AppResult.Success -> {
                _state.update {
                    it.copy(
                        hiddenProfiles = it.hiddenProfiles.filterNot { profile -> profile.hiddenUserId == userId },
                        unhidingUserId = null,
                        errorMessage = null,
                        feedbackMessage = "Profile unhidden"
                    )
                }
            }

            is AppResult.Failure -> {
                _state.update {
                    it.copy(
                        unhidingUserId = null,
                        errorMessage = result.error.message
                    )
                }
            }
        }
    }

    fun reset() {
        _state.value = ProfileHubUiState()
    }

    suspend fun saveDraft() {
        val current = state.value
        if (current.isSaving) return

        val validationError = current.draft.validationError()
        if (validationError != null) {
            _state.update { it.copy(errorMessage = validationError, feedbackMessage = null) }
            return
        }

        _state.update {
            it.copy(
                isSaving = true,
                errorMessage = null,
                feedbackMessage = null
            )
        }

        when (val result = repository.updateProfile(current.draft.toRequest())) {
            is AppResult.Success -> {
                _state.update {
                    it.copy(
                        me = result.value,
                        draft = result.value.profile.toDraft(),
                        privacyDraft = result.value.discoverySettings.toPrivacyDraft(),
                        activityDraft = result.value.discoverySettings.toActivityDraft(),
                        editVisible = false,
                        isSaving = false,
                        errorMessage = null,
                        feedbackMessage = "Profile updated"
                    )
                }
            }

            is AppResult.Failure -> {
                _state.update {
                    it.copy(
                        isSaving = false,
                        errorMessage = result.error.message
                    )
                }
            }
        }
    }

    suspend fun savePrivacySettings() {
        val current = state.value
        if (current.isSaving) return

        _state.update {
            it.copy(
                isSaving = true,
                errorMessage = null,
                feedbackMessage = null
            )
        }

        when (val result = repository.updateDiscoverySettings(current.privacyDraft.toRequest())) {
            is AppResult.Success -> {
                _state.update {
                    it.copy(
                        me = result.value,
                        privacyDraft = result.value.discoverySettings.toPrivacyDraft(),
                        activityDraft = result.value.discoverySettings.toActivityDraft(),
                        privacyVisible = false,
                        isSaving = false,
                        errorMessage = null,
                        feedbackMessage = "Privacy updated"
                    )
                }
            }

            is AppResult.Failure -> {
                _state.update {
                    it.copy(
                        isSaving = false,
                        errorMessage = result.error.message
                    )
                }
            }
        }
    }

    suspend fun saveActivityControls() {
        val current = state.value
        if (current.isSaving) return

        if (current.activityDraft.gridMessagePolicy !in gridMessagePolicies) {
            _state.update { it.copy(errorMessage = "Choose who can message from the grid.", feedbackMessage = null) }
            return
        }

        _state.update {
            it.copy(
                isSaving = true,
                errorMessage = null,
                feedbackMessage = null
            )
        }

        when (val result = repository.updateDiscoverySettings(current.activityDraft.toRequest())) {
            is AppResult.Success -> {
                _state.update {
                    it.copy(
                        me = result.value,
                        privacyDraft = result.value.discoverySettings.toPrivacyDraft(),
                        activityDraft = result.value.discoverySettings.toActivityDraft(),
                        activityVisible = false,
                        isSaving = false,
                        errorMessage = null,
                        feedbackMessage = "Activity controls updated"
                    )
                }
            }

            is AppResult.Failure -> {
                _state.update {
                    it.copy(
                        isSaving = false,
                        errorMessage = result.error.message
                    )
                }
            }
        }
    }

    suspend fun startAgeVerification() {
        val current = state.value
        if (current.isAgeVerificationMutating) return

        _state.update {
            it.copy(
                isAgeVerificationMutating = true,
                errorMessage = null,
                feedbackMessage = null
            )
        }

        when (
            val result = repository.startAgeVerification(
                StartAgeVerificationRequestDto(confirmedAdult = true)
            )
        ) {
            is AppResult.Success -> {
                _state.update {
                    it.copy(
                        ageVerificationStatus = result.value,
                        ageVerificationDialogVisible = false,
                        isAgeVerificationMutating = false,
                        errorMessage = null,
                        feedbackMessage = result.value.message
                    )
                }
            }

            is AppResult.Failure -> {
                _state.update {
                    it.copy(
                        isAgeVerificationMutating = false,
                        errorMessage = result.error.message
                    )
                }
            }
        }
    }

    private fun updateDraft(transform: (ProfileEditDraft) -> ProfileEditDraft) {
        _state.update {
            it.copy(
                draft = transform(it.draft),
                errorMessage = null,
                feedbackMessage = null
            )
        }
    }

    private fun updatePrivacyDraft(transform: (PrivacySettingsDraft) -> PrivacySettingsDraft) {
        _state.update {
            it.copy(
                privacyDraft = transform(it.privacyDraft),
                errorMessage = null,
                feedbackMessage = null
            )
        }
    }

    private fun updateActivityDraft(transform: (ActivitySettingsDraft) -> ActivitySettingsDraft) {
        _state.update {
            it.copy(
                activityDraft = transform(it.activityDraft),
                errorMessage = null,
                feedbackMessage = null
            )
        }
    }
}
