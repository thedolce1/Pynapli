package com.pynapli.app.profilelink

import androidx.compose.runtime.Immutable
import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.network.PynapliApiClient
import com.pynapli.shared.network.dto.CreateProfileLinkInvitationRequestDto
import com.pynapli.shared.network.dto.ProfileLinkDto
import com.pynapli.shared.network.dto.ProfileLinksSummaryDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

@Immutable
data class ProfileLinksUiState(
    val current: ProfileLinkUi? = null,
    val incomingInvites: List<ProfileLinkUi> = emptyList(),
    val outgoingInvites: List<ProfileLinkUi> = emptyList(),
    val submittingTargetUserIds: Set<String> = emptySet(),
    val mutatingLinkIds: Set<String> = emptySet(),
    val isLoading: Boolean = false,
    val feedbackMessage: String? = null,
    val feedbackTargetUserId: String? = null,
    val errorMessage: String? = null,
    val errorTargetUserId: String? = null,
) {
    fun pendingInviteWith(userId: String): ProfileLinkUi? =
        incomingInvites.firstOrNull { it.otherUserId == userId }
            ?: outgoingInvites.firstOrNull { it.otherUserId == userId }
}

@Immutable
data class ProfileLinkUi(
    val linkId: String,
    val otherUserId: String,
    val otherDisplayName: String?,
    val otherPhotoUrl: String?,
    val status: String,
    val direction: String,
    val createdAt: String,
    val acceptedAt: String?,
    val unlinkedAt: String?,
) {
    val displayLabel: String
        get() = otherDisplayName?.takeIf { it.isNotBlank() } ?: "User ${otherUserId.take(8)}"
}

class ProfileLinksStateHolder(
    private val repository: ProfileLinksRepository,
) {
    private val _state = MutableStateFlow(ProfileLinksUiState())
    val state: StateFlow<ProfileLinksUiState> = _state.asStateFlow()

    suspend fun load() {
        if (state.value.isLoading) return
        _state.update { it.copy(isLoading = true, errorMessage = null, errorTargetUserId = null) }

        when (val result = repository.loadSummary()) {
            is AppResult.Success -> {
                _state.update { existing ->
                    existing.withSummary(result.value).copy(
                        isLoading = false,
                        feedbackMessage = null,
                        feedbackTargetUserId = null,
                    )
                }
            }

            is AppResult.Failure -> {
                _state.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = result.error.message,
                        errorTargetUserId = null,
                    )
                }
            }
        }
    }

    suspend fun createInvitation(targetUserId: String) {
        val cleanTargetUserId = targetUserId.trim()
        if (cleanTargetUserId.isEmpty() || cleanTargetUserId in state.value.submittingTargetUserIds) return

        _state.update { existing ->
            existing.copy(
                submittingTargetUserIds = existing.submittingTargetUserIds + cleanTargetUserId,
                feedbackMessage = null,
                feedbackTargetUserId = null,
                errorMessage = null,
                errorTargetUserId = null,
            )
        }

        when (val result = repository.createInvitation(cleanTargetUserId)) {
            is AppResult.Success -> {
                _state.update { existing ->
                    existing.withSummary(result.value).copy(
                        submittingTargetUserIds = existing.submittingTargetUserIds - cleanTargetUserId,
                        feedbackMessage = "Link invite sent",
                        feedbackTargetUserId = cleanTargetUserId,
                    )
                }
            }

            is AppResult.Failure -> {
                _state.update { existing ->
                    existing.copy(
                        submittingTargetUserIds = existing.submittingTargetUserIds - cleanTargetUserId,
                        errorMessage = result.error.message,
                        errorTargetUserId = cleanTargetUserId,
                    )
                }
            }
        }
    }

    suspend fun acceptInvitation(linkId: String) {
        mutateLink(
            linkId = linkId,
            successMessage = "Profiles linked",
            call = repository::acceptInvitation,
        )
    }

    suspend fun declineInvitation(linkId: String) {
        mutateLink(
            linkId = linkId,
            successMessage = "Invite declined",
            call = repository::declineInvitation,
        )
    }

    suspend fun unlinkCurrent() {
        val linkId = state.value.current?.linkId ?: return
        mutateLink(
            linkId = linkId,
            successMessage = "Profiles unlinked",
            call = { repository.unlinkCurrent() },
        )
    }

    fun clearMessages() {
        _state.update {
            it.copy(
                feedbackMessage = null,
                feedbackTargetUserId = null,
                errorMessage = null,
                errorTargetUserId = null,
            )
        }
    }

    fun reset() {
        _state.value = ProfileLinksUiState()
    }

    private suspend fun mutateLink(
        linkId: String,
        successMessage: String,
        call: suspend (String) -> AppResult<ProfileLinksSummaryDto>,
    ) {
        if (linkId in state.value.mutatingLinkIds) return
        _state.update { existing ->
            existing.copy(
                mutatingLinkIds = existing.mutatingLinkIds + linkId,
                feedbackMessage = null,
                feedbackTargetUserId = null,
                errorMessage = null,
                errorTargetUserId = null,
            )
        }

        when (val result = call(linkId)) {
            is AppResult.Success -> {
                _state.update { existing ->
                    existing.withSummary(result.value).copy(
                        mutatingLinkIds = existing.mutatingLinkIds - linkId,
                        feedbackMessage = successMessage,
                        feedbackTargetUserId = null,
                    )
                }
            }

            is AppResult.Failure -> {
                _state.update { existing ->
                    existing.copy(
                        mutatingLinkIds = existing.mutatingLinkIds - linkId,
                        errorMessage = result.error.message,
                        errorTargetUserId = null,
                    )
                }
            }
        }
    }

    private fun ProfileLinksUiState.withSummary(summary: ProfileLinksSummaryDto): ProfileLinksUiState =
        copy(
            current = summary.current?.toUi(),
            incomingInvites = summary.incomingInvites.map { it.toUi() },
            outgoingInvites = summary.outgoingInvites.map { it.toUi() },
            errorMessage = null,
            errorTargetUserId = null,
        )
}

interface ProfileLinksRepository {
    suspend fun loadSummary(): AppResult<ProfileLinksSummaryDto>

    suspend fun createInvitation(targetUserId: String): AppResult<ProfileLinksSummaryDto>

    suspend fun acceptInvitation(linkId: String): AppResult<ProfileLinksSummaryDto>

    suspend fun declineInvitation(linkId: String): AppResult<ProfileLinksSummaryDto>

    suspend fun unlinkCurrent(): AppResult<ProfileLinksSummaryDto>
}

class ApiProfileLinksRepository(
    private val apiClient: PynapliApiClient,
) : ProfileLinksRepository {
    override suspend fun loadSummary(): AppResult<ProfileLinksSummaryDto> = apiClient.listProfileLinks()

    override suspend fun createInvitation(targetUserId: String): AppResult<ProfileLinksSummaryDto> = apiClient.createProfileLinkInvitation(CreateProfileLinkInvitationRequestDto(targetUserId = targetUserId))

    override suspend fun acceptInvitation(linkId: String): AppResult<ProfileLinksSummaryDto> = apiClient.acceptProfileLinkInvitation(linkId)

    override suspend fun declineInvitation(linkId: String): AppResult<ProfileLinksSummaryDto> = apiClient.declineProfileLinkInvitation(linkId)

    override suspend fun unlinkCurrent(): AppResult<ProfileLinksSummaryDto> = apiClient.unlinkCurrentProfileLink()
}

private fun ProfileLinkDto.toUi(): ProfileLinkUi =
    ProfileLinkUi(
        linkId = linkId,
        otherUserId = otherUserId,
        otherDisplayName = otherDisplayName,
        otherPhotoUrl = otherPhotoUrl,
        status = status,
        direction = direction,
        createdAt = createdAt,
        acceptedAt = acceptedAt,
        unlinkedAt = unlinkedAt,
    )
