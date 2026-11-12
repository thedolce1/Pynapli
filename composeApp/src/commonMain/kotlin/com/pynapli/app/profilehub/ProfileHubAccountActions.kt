package com.pynapli.app.profilehub

import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.network.dto.DeleteAccountRequestDto
import com.pynapli.shared.network.dto.PasswordChangeDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

internal class ProfileHubAccountActions(
    private val repository: ProfileHubRepository,
    private val state: MutableStateFlow<ProfileHubUiState>,
) {
    /**
     * What a tap on the Data export row does. A ready export returns its download link for the screen
     * to open. One still being prepared refreshes its status instead, so repeated taps don't queue
     * duplicate exports. Anything else (none yet, failed, expired) requests a new export.
     */
    suspend fun onDataExportTapped(): String? {
        val current = state.value
        if (current.isDataExportMutating) return null
        val latest = current.latestDataExport
        when (latest?.status) {
            "ready" -> latest.downloadUrl?.let { url -> return url } ?: refreshDataExport()
            "requested", "processing" -> refreshDataExport()
            else -> requestDataExport()
        }
        return null
    }

    private suspend fun refreshDataExport() {
        state.update { it.copy(isDataExportMutating = true, errorMessage = null, feedbackMessage = null) }
        when (val result = repository.latestDataExportRequest()) {
            is AppResult.Success ->
                state.update {
                    it.copy(
                        latestDataExport = result.value,
                        isDataExportMutating = false,
                        feedbackMessage = result.value?.message,
                    )
                }

            is AppResult.Failure ->
                state.update { it.copy(isDataExportMutating = false, errorMessage = result.error.message) }
        }
    }

    suspend fun requestDataExport() {
        val current = state.value
        if (current.isDataExportMutating) return

        state.update {
            it.copy(
                isDataExportMutating = true,
                errorMessage = null,
                feedbackMessage = null,
            )
        }

        when (val result = repository.requestDataExport()) {
            is AppResult.Success -> {
                state.update {
                    it.copy(
                        latestDataExport = result.value,
                        isDataExportMutating = false,
                        errorMessage = null,
                        feedbackMessage = result.value.message,
                    )
                }
            }

            is AppResult.Failure -> {
                state.update {
                    it.copy(
                        isDataExportMutating = false,
                        errorMessage = result.error.message,
                    )
                }
            }
        }
    }

    suspend fun deleteAccount(confirmation: String): Boolean {
        val current = state.value
        if (current.isDeletingAccount) return false

        state.update {
            it.copy(
                isDeletingAccount = true,
                errorMessage = null,
                feedbackMessage = null,
            )
        }

        return when (
            val result =
                repository.deleteAccount(
                    DeleteAccountRequestDto(confirmation = confirmation),
                )
        ) {
            is AppResult.Success -> {
                state.update {
                    it.copy(
                        isDeletingAccount = false,
                        errorMessage = null,
                        feedbackMessage = result.value.message,
                    )
                }
                true
            }

            is AppResult.Failure -> {
                state.update {
                    it.copy(
                        isDeletingAccount = false,
                        errorMessage = result.error.message,
                    )
                }
                false
            }
        }
    }

    /** Returns true when the password changed; other devices are signed out by the server. */
    suspend fun changePassword(
        currentPassword: String,
        newPassword: String,
    ): Boolean {
        if (state.value.isChangingPassword) return false
        state.update { it.copy(isChangingPassword = true, errorMessage = null, feedbackMessage = null) }
        return when (val result = repository.changePassword(PasswordChangeDto(currentPassword = currentPassword, newPassword = newPassword))) {
            is AppResult.Success -> {
                state.update {
                    it.copy(
                        isChangingPassword = false,
                        feedbackMessage = "Password changed. Any other devices were signed out.",
                    )
                }
                true
            }

            is AppResult.Failure -> {
                state.update { it.copy(isChangingPassword = false, errorMessage = result.error.message) }
                false
            }
        }
    }

    suspend fun loadSignedInDevices() {
        state.update { it.copy(isLoadingDevices = true) }
        when (val result = repository.listSignedInDevices()) {
            is AppResult.Success -> state.update { it.copy(isLoadingDevices = false, signedInDevices = result.value.devices) }
            is AppResult.Failure -> state.update { it.copy(isLoadingDevices = false, errorMessage = result.error.message) }
        }
    }

    suspend fun signOutDevice(sessionId: String) {
        if (sessionId in state.value.signingOutDeviceIds) return
        state.update { it.copy(signingOutDeviceIds = it.signingOutDeviceIds + sessionId, errorMessage = null, feedbackMessage = null) }
        val result = repository.signOutDevice(sessionId)
        // Already signed out elsewhere counts as done: the row goes either way.
        val gone = result is AppResult.Success || (result as? AppResult.Failure)?.error?.code == "SESSION_NOT_FOUND"
        state.update { current ->
            current.copy(
                signingOutDeviceIds = current.signingOutDeviceIds - sessionId,
                signedInDevices = if (gone) current.signedInDevices?.filterNot { it.sessionId == sessionId } else current.signedInDevices,
                feedbackMessage = if (gone) "Signed out of that device." else current.feedbackMessage,
                errorMessage = if (gone) null else (result as AppResult.Failure).error.message,
            )
        }
    }

    suspend fun signOutOtherDevices() {
        val others =
            state.value.signedInDevices
                .orEmpty()
                .filterNot { it.current }
                .map { it.sessionId }
                .toSet()
        state.update { it.copy(signingOutDeviceIds = it.signingOutDeviceIds + others, errorMessage = null, feedbackMessage = null) }
        when (val result = repository.signOutOtherDevices()) {
            is AppResult.Success ->
                state.update { current ->
                    current.copy(
                        signingOutDeviceIds = current.signingOutDeviceIds - others,
                        signedInDevices = current.signedInDevices?.filter { it.current },
                        feedbackMessage =
                            when (val count = result.value.signedOut) {
                                0 -> "You're only signed in here."
                                1 -> "Signed out of 1 other device."
                                else -> "Signed out of $count other devices."
                            },
                    )
                }

            is AppResult.Failure ->
                state.update { it.copy(signingOutDeviceIds = it.signingOutDeviceIds - others, errorMessage = result.error.message) }
        }
    }
}
