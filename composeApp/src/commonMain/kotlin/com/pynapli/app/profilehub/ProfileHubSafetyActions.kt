package com.pynapli.app.profilehub

import com.pynapli.shared.core.result.AppResult
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

internal class ProfileHubSafetyActions(
    private val repository: ProfileHubRepository,
    private val state: MutableStateFlow<ProfileHubUiState>,
) {
    suspend fun loadBlocks() {
        if (state.value.isBlocksLoading) return

        state.update {
            it.copy(
                isBlocksLoading = true,
                errorMessage = null,
                feedbackMessage = null,
            )
        }

        when (val result = repository.listBlocks()) {
            is AppResult.Success -> {
                state.update {
                    it.copy(
                        blockedUsers = result.value.blocks,
                        isBlocksLoading = false,
                        errorMessage = null,
                    )
                }
            }

            is AppResult.Failure -> {
                state.update {
                    it.copy(
                        isBlocksLoading = false,
                        errorMessage = result.error.message,
                    )
                }
            }
        }
    }

    suspend fun loadHiddenProfiles() {
        if (state.value.isHiddenProfilesLoading) return

        state.update {
            it.copy(
                isHiddenProfilesLoading = true,
                errorMessage = null,
                feedbackMessage = null,
            )
        }

        when (val result = repository.listHiddenProfiles()) {
            is AppResult.Success -> {
                state.update {
                    it.copy(
                        hiddenProfiles = result.value.hiddenProfiles,
                        isHiddenProfilesLoading = false,
                        errorMessage = null,
                    )
                }
            }

            is AppResult.Failure -> {
                state.update {
                    it.copy(
                        isHiddenProfilesLoading = false,
                        errorMessage = result.error.message,
                    )
                }
            }
        }
    }

    suspend fun loadReportHistory() {
        if (state.value.isReportHistoryLoading) return

        state.update {
            it.copy(
                isReportHistoryLoading = true,
                errorMessage = null,
                feedbackMessage = null,
            )
        }

        when (val result = repository.listReportHistory()) {
            is AppResult.Success -> {
                state.update {
                    it.copy(
                        reportHistory = result.value.reports,
                        isReportHistoryLoading = false,
                        errorMessage = null,
                    )
                }
            }

            is AppResult.Failure -> {
                state.update {
                    it.copy(
                        isReportHistoryLoading = false,
                        errorMessage = result.error.message,
                    )
                }
            }
        }
    }

    suspend fun loadSupportContact() {
        if (state.value.isSupportContactLoading) return

        state.update {
            it.copy(
                isSupportContactLoading = true,
                errorMessage = null,
                feedbackMessage = null,
            )
        }

        when (val result = repository.getSupportContact()) {
            is AppResult.Success -> {
                state.update {
                    it.copy(
                        supportContact = result.value,
                        isSupportContactLoading = false,
                        errorMessage = null,
                    )
                }
            }

            is AppResult.Failure -> {
                state.update {
                    it.copy(
                        isSupportContactLoading = false,
                        errorMessage = result.error.message,
                    )
                }
            }
        }
    }

    suspend fun loadSafetyLists() =
        coroutineScope {
            val blocks = async { loadBlocks() }
            val hidden = async { loadHiddenProfiles() }
            val reports = async { loadReportHistory() }
            val support = async { loadSupportContact() }
            blocks.await()
            hidden.await()
            reports.await()
            support.await()
        }

    suspend fun unblockUser(userId: String) {
        val current = state.value
        if (current.unblockingUserId != null) return

        state.update {
            it.copy(
                unblockingUserId = userId,
                errorMessage = null,
                feedbackMessage = null,
            )
        }

        when (val result = repository.unblockUser(userId)) {
            is AppResult.Success -> {
                state.update {
                    it.copy(
                        blockedUsers = it.blockedUsers.filterNot { block -> block.blockedUserId == userId },
                        unblockingUserId = null,
                        errorMessage = null,
                        feedbackMessage = "User unblocked",
                    )
                }
            }

            is AppResult.Failure -> {
                state.update {
                    it.copy(
                        unblockingUserId = null,
                        errorMessage = result.error.message,
                    )
                }
            }
        }
    }

    suspend fun unhideProfile(userId: String) {
        val current = state.value
        if (current.unhidingUserId != null) return

        state.update {
            it.copy(
                unhidingUserId = userId,
                errorMessage = null,
                feedbackMessage = null,
            )
        }

        when (val result = repository.unhideProfile(userId)) {
            is AppResult.Success -> {
                state.update {
                    it.copy(
                        hiddenProfiles = it.hiddenProfiles.filterNot { profile -> profile.hiddenUserId == userId },
                        unhidingUserId = null,
                        errorMessage = null,
                        feedbackMessage = "Profile unhidden",
                    )
                }
            }

            is AppResult.Failure -> {
                state.update {
                    it.copy(
                        unhidingUserId = null,
                        errorMessage = result.error.message,
                    )
                }
            }
        }
    }
}
