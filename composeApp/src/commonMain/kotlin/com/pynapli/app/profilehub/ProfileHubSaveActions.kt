package com.pynapli.app.profilehub

import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.network.dto.StartAgeVerificationRequestDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

internal class ProfileHubSaveActions(
    private val repository: ProfileHubRepository,
    private val state: MutableStateFlow<ProfileHubUiState>,
) {
    suspend fun saveDraft() {
        val current = state.value
        if (current.isSaving) return

        val validationError = current.draft.validationError()
        if (validationError != null) {
            state.update { it.copy(errorMessage = validationError, feedbackMessage = null) }
            return
        }

        state.update {
            it.copy(
                isSaving = true,
                errorMessage = null,
                feedbackMessage = null,
            )
        }

        when (val result = repository.updateProfile(current.draft.toRequest())) {
            is AppResult.Success -> {
                state.update {
                    it.copy(
                        me = result.value,
                        draft = result.value.profile.toDraft(),
                        privacyDraft = result.value.discoverySettings.toPrivacyDraft(),
                        activityDraft = result.value.discoverySettings.toActivityDraft(),
                        editVisible = false,
                        isSaving = false,
                        errorMessage = null,
                        feedbackMessage = "Profile updated",
                    )
                }
            }

            is AppResult.Failure -> {
                state.update {
                    it.copy(
                        isSaving = false,
                        errorMessage = result.error.message,
                    )
                }
            }
        }
    }

    suspend fun savePrivacySettings() {
        val current = state.value
        if (current.isSaving) return

        state.update {
            it.copy(
                isSaving = true,
                errorMessage = null,
                feedbackMessage = null,
            )
        }

        when (val result = repository.updateDiscoverySettings(current.privacyDraft.toRequest())) {
            is AppResult.Success -> {
                state.update {
                    it.copy(
                        me = result.value,
                        privacyDraft = result.value.discoverySettings.toPrivacyDraft(),
                        activityDraft = result.value.discoverySettings.toActivityDraft(),
                        privacyVisible = false,
                        isSaving = false,
                        errorMessage = null,
                        feedbackMessage = "Privacy updated",
                    )
                }
            }

            is AppResult.Failure -> {
                state.update {
                    it.copy(
                        isSaving = false,
                        errorMessage = result.error.message,
                    )
                }
            }
        }
    }

    /**
     * Saves the partner-visibility selection from the Partner surface. Partner visibility is a
     * discovery_settings field, so it round-trips through the same privacy draft/request as
     * [savePrivacySettings].
     */
    suspend fun savePartner() {
        val current = state.value
        if (current.isSaving) return

        state.update { it.copy(isSaving = true, errorMessage = null, feedbackMessage = null) }

        when (val result = repository.updateDiscoverySettings(current.privacyDraft.toRequest())) {
            is AppResult.Success -> {
                state.update {
                    it.copy(
                        me = result.value,
                        privacyDraft = result.value.discoverySettings.toPrivacyDraft(),
                        activityDraft = result.value.discoverySettings.toActivityDraft(),
                        partnerVisible = false,
                        isSaving = false,
                        errorMessage = null,
                        feedbackMessage = "Partner settings updated",
                    )
                }
            }

            is AppResult.Failure -> {
                state.update { it.copy(isSaving = false, errorMessage = result.error.message) }
            }
        }
    }

    suspend fun saveActivityControls() {
        val current = state.value
        if (current.isSaving) return

        if (current.activityDraft.gridMessagePolicy !in gridMessagePolicies) {
            state.update { it.copy(errorMessage = "Choose who can message from the grid.", feedbackMessage = null) }
            return
        }

        state.update {
            it.copy(
                isSaving = true,
                errorMessage = null,
                feedbackMessage = null,
            )
        }

        when (val result = repository.updateDiscoverySettings(current.activityDraft.toRequest())) {
            is AppResult.Success -> {
                state.update {
                    it.copy(
                        me = result.value,
                        privacyDraft = result.value.discoverySettings.toPrivacyDraft(),
                        activityDraft = result.value.discoverySettings.toActivityDraft(),
                        activityVisible = false,
                        isSaving = false,
                        errorMessage = null,
                        feedbackMessage = "Activity controls updated",
                    )
                }
            }

            is AppResult.Failure -> {
                state.update {
                    it.copy(
                        isSaving = false,
                        errorMessage = result.error.message,
                    )
                }
            }
        }
    }

    suspend fun startAgeVerification() {
        val current = state.value
        if (current.isAgeVerificationMutating) return

        state.update {
            it.copy(
                isAgeVerificationMutating = true,
                errorMessage = null,
                feedbackMessage = null,
            )
        }

        when (
            val result =
                repository.startAgeVerification(
                    StartAgeVerificationRequestDto(confirmedAdult = true),
                )
        ) {
            is AppResult.Success -> {
                state.update {
                    it.copy(
                        ageVerificationStatus = result.value,
                        ageVerificationDialogVisible = false,
                        isAgeVerificationMutating = false,
                        errorMessage = null,
                        feedbackMessage = result.value.message,
                    )
                }
            }

            is AppResult.Failure -> {
                state.update {
                    it.copy(
                        isAgeVerificationMutating = false,
                        errorMessage = result.error.message,
                    )
                }
            }
        }
    }
}
