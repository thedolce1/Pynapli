package com.pynapli.app.profilehub

import com.pynapli.shared.core.result.AppResult
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

internal class ProfileHubLoader(
    private val repository: ProfileHubRepository,
    private val state: MutableStateFlow<ProfileHubUiState>,
) {
    suspend fun load() =
        coroutineScope {
            if (state.value.isLoading) return@coroutineScope
            state.update { it.copy(isLoading = true, errorMessage = null) }

            val meDeferred = async { repository.loadMe() }
            val ageVerificationDeferred = async { repository.loadAgeVerificationStatus() }
            val latestDataExportDeferred = async { repository.latestDataExportRequest() }

            when (val result = meDeferred.await()) {
                is AppResult.Success -> {
                    val ageVerificationStatus =
                        when (val status = ageVerificationDeferred.await()) {
                            is AppResult.Success -> status.value
                            is AppResult.Failure -> null
                        }
                    val latestDataExport =
                        when (val export = latestDataExportDeferred.await()) {
                            is AppResult.Success -> export.value
                            is AppResult.Failure -> null
                        }
                    state.update { existing ->
                        existing.copy(
                            me = result.value,
                            ageVerificationStatus = ageVerificationStatus,
                            latestDataExport = latestDataExport,
                            draft = if (existing.editVisible) existing.draft else result.value.profile.toDraft(),
                            privacyDraft =
                                if (existing.privacyVisible) {
                                    existing.privacyDraft
                                } else {
                                    result.value.discoverySettings.toPrivacyDraft()
                                },
                            activityDraft =
                                if (existing.activityVisible) {
                                    existing.activityDraft
                                } else {
                                    result.value.discoverySettings.toActivityDraft()
                                },
                            isLoading = false,
                            errorMessage = null,
                        )
                    }
                }

                is AppResult.Failure -> {
                    ageVerificationDeferred.cancel()
                    latestDataExportDeferred.cancel()
                    state.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = result.error.message,
                        )
                    }
                }
            }
        }
}
