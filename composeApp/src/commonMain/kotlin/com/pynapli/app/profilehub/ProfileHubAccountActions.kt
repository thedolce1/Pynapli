package com.pynapli.app.profilehub

import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.network.dto.DeleteAccountRequestDto
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
}
