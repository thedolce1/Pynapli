package com.pynapli.app.profile

import com.pynapli.app.grid.GridCardTone
import com.pynapli.app.grid.GridLinkedPartnerUi
import com.pynapli.app.grid.GridProfileCardUi
import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.network.dto.ReportTargetType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class ProfileDetailStateHolder(
    private val repository: PublicProfileRepository
) {
    private val _state = MutableStateFlow(ProfileDetailUiState())
    val state: StateFlow<ProfileDetailUiState> = _state.asStateFlow()

    suspend fun open(card: GridProfileCardUi) {
        _state.update {
            ProfileDetailUiState(
                selectedCard = card,
                isLoading = true
            )
        }

        when (val result = repository.loadProfile(card.id)) {
            is AppResult.Success -> {
                _state.update { current ->
                    current.copy(
                        profile = result.value,
                        isLoading = false,
                        errorMessage = null
                    )
                }
            }

            is AppResult.Failure -> {
                _state.update { current ->
                    current.copy(
                        isLoading = false,
                        errorMessage = result.error.message
                    )
                }
            }
        }
    }

    suspend fun openLinkedPartner(partner: GridLinkedPartnerUi) {
        open(
            GridProfileCardUi(
                id = partner.userId,
                displayName = partner.displayName ?: "Profile ${partner.userId.take(8)}",
                thumbnailUrl = partner.thumbnailUrl,
                online = false,
                distanceLabel = null,
                tone = GridCardTone.entries[partner.userId.stableIndex(GridCardTone.entries.size)]
            )
        )
    }

    suspend fun sendSpark() {
        val targetUserId = _state.value.profile?.userId ?: _state.value.selectedCard?.id ?: return
        _state.update { current ->
            current.copy(
                isSendingFlirt = true,
                albumFeedback = null,
                flirtFeedback = null,
                errorMessage = null
            )
        }

        when (val result = repository.sendFlirt(targetUserId)) {
            is AppResult.Success -> {
                _state.update { current ->
                    current.copy(
                        isSendingFlirt = false,
                        flirtFeedback = "Spark sent"
                    )
                }
            }

            is AppResult.Failure -> {
                _state.update { current ->
                    current.copy(
                        isSendingFlirt = false,
                        errorMessage = result.error.message
                    )
                }
            }
        }
    }

    suspend fun requestAlbumAccess() {
        val targetUserId = _state.value.profile?.userId ?: _state.value.selectedCard?.id ?: return
        _state.update { current ->
            current.copy(
                isRequestingAlbum = true,
                flirtFeedback = null,
                albumFeedback = null,
                errorMessage = null
            )
        }

        when (val result = repository.requestAlbumAccess(targetUserId)) {
            is AppResult.Success -> {
                _state.update { current ->
                    current.copy(
                        isRequestingAlbum = false,
                        profile = current.profile?.copy(privateAlbumAccessStatus = "pending"),
                        albumFeedback = "Album request sent"
                    )
                }
            }

            is AppResult.Failure -> {
                _state.update { current ->
                    current.copy(
                        isRequestingAlbum = false,
                        errorMessage = result.error.message
                    )
                }
            }
        }
    }

    suspend fun loadPrivateAlbum() {
        val ownerUserId = _state.value.profile?.userId ?: _state.value.selectedCard?.id ?: return
        _state.update { current ->
            current.copy(
                isLoadingPrivateAlbum = true,
                albumFeedback = null,
                errorMessage = null
            )
        }

        when (val result = repository.loadPrivateAlbum(ownerUserId)) {
            is AppResult.Success -> {
                _state.update { current ->
                    current.copy(
                        isLoadingPrivateAlbum = false,
                        privateAlbumItems = result.value,
                        profile = current.profile?.copy(privateAlbumAccessStatus = "granted"),
                        albumFeedback = if (result.value.isEmpty()) "Private album is empty" else null
                    )
                }
            }

            is AppResult.Failure -> {
                _state.update { current ->
                    current.copy(
                        isLoadingPrivateAlbum = false,
                        errorMessage = result.error.message
                    )
                }
            }
        }
    }

    suspend fun blockUser(reason: String? = null) {
        val targetUserId = _state.value.profile?.userId ?: _state.value.selectedCard?.id ?: return
        if (_state.value.isBlocking) return

        _state.update { current ->
            current.copy(
                isBlocking = true,
                flirtFeedback = null,
                albumFeedback = null,
                reportFeedback = null,
                errorMessage = null
            )
        }

        when (val result = repository.blockUser(targetUserId, reason)) {
            is AppResult.Success -> {
                _state.update { current ->
                    current.copy(
                        isBlocking = false,
                        blockedTargetUserId = targetUserId
                    )
                }
            }

            is AppResult.Failure -> {
                _state.update { current ->
                    current.copy(
                        isBlocking = false,
                        errorMessage = result.error.message
                    )
                }
            }
        }
    }

    suspend fun hideProfile() {
        val targetUserId = _state.value.profile?.userId ?: _state.value.selectedCard?.id ?: return
        if (_state.value.isHiding) return

        _state.update { current ->
            current.copy(
                isHiding = true,
                flirtFeedback = null,
                albumFeedback = null,
                reportFeedback = null,
                errorMessage = null
            )
        }

        when (val result = repository.hideProfile(targetUserId)) {
            is AppResult.Success -> {
                _state.update { current ->
                    current.copy(
                        isHiding = false,
                        hiddenTargetUserId = targetUserId
                    )
                }
            }

            is AppResult.Failure -> {
                _state.update { current ->
                    current.copy(
                        isHiding = false,
                        errorMessage = result.error.message
                    )
                }
            }
        }
    }

    suspend fun submitReport(category: String, note: String? = null) {
        val targetUserId = _state.value.profile?.userId ?: _state.value.selectedCard?.id ?: return
        if (_state.value.isReporting) return

        _state.update { current ->
            current.copy(
                isReporting = true,
                flirtFeedback = null,
                albumFeedback = null,
                reportFeedback = null,
                errorMessage = null
            )
        }

        when (val result = repository.submitReport(targetUserId, ReportTargetType.PROFILE, category, note)) {
            is AppResult.Success -> {
                _state.update { current ->
                    current.copy(
                        isReporting = false,
                        reportFeedback = "Report submitted"
                    )
                }
            }

            is AppResult.Failure -> {
                _state.update { current ->
                    current.copy(
                        isReporting = false,
                        errorMessage = result.error.message
                    )
                }
            }
        }
    }

    fun consumeBlockedTargetUserId(): String? {
        val current = _state.value.blockedTargetUserId ?: return null
        _state.update { it.copy(blockedTargetUserId = null) }
        return current
    }

    fun consumeHiddenTargetUserId(): String? {
        val current = _state.value.hiddenTargetUserId ?: return null
        _state.update { it.copy(hiddenTargetUserId = null) }
        return current
    }

    fun dismiss() {
        _state.value = ProfileDetailUiState()
    }

}
