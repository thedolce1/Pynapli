package com.pynapli.app.profile

import androidx.compose.runtime.Immutable
import com.pynapli.app.grid.GridProfileCardUi
import com.pynapli.app.media.MediaAlbumItemUi
import com.pynapli.app.media.MediaAlbumKindUi
import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.network.PynapliApiClient
import com.pynapli.shared.network.dto.MediaAlbumItemDto
import com.pynapli.shared.network.dto.PublicProfileDto
import com.pynapli.shared.network.dto.RequestAlbumAccessRequestDto
import com.pynapli.shared.network.dto.RequestAlbumAccessResponseDto
import com.pynapli.shared.network.dto.SendFlirtRequestDto
import com.pynapli.shared.network.dto.SendFlirtResponseDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

@Immutable
data class ProfileDetailUiState(
    val selectedCard: GridProfileCardUi? = null,
    val profile: PublicProfileDto? = null,
    val isLoading: Boolean = false,
    val isSendingFlirt: Boolean = false,
    val isRequestingAlbum: Boolean = false,
    val isLoadingPrivateAlbum: Boolean = false,
    val privateAlbumItems: List<MediaAlbumItemUi> = emptyList(),
    val flirtFeedback: String? = null,
    val albumFeedback: String? = null,
    val errorMessage: String? = null
) {
    val visible: Boolean
        get() = selectedCard != null
}

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

    fun dismiss() {
        _state.value = ProfileDetailUiState()
    }
}

interface PublicProfileRepository {
    suspend fun loadProfile(userId: String): AppResult<PublicProfileDto>

    suspend fun sendFlirt(userId: String): AppResult<SendFlirtResponseDto>

    suspend fun requestAlbumAccess(userId: String): AppResult<RequestAlbumAccessResponseDto>

    suspend fun loadPrivateAlbum(userId: String): AppResult<List<MediaAlbumItemUi>>
}

class ApiPublicProfileRepository(
    private val apiClient: PynapliApiClient
) : PublicProfileRepository {
    override suspend fun loadProfile(userId: String): AppResult<PublicProfileDto> =
        apiClient.getPublicProfile(userId)

    override suspend fun sendFlirt(userId: String): AppResult<SendFlirtResponseDto> =
        apiClient.sendFlirt(SendFlirtRequestDto(targetUserId = userId))

    override suspend fun requestAlbumAccess(userId: String): AppResult<RequestAlbumAccessResponseDto> =
        apiClient.requestAlbumAccess(RequestAlbumAccessRequestDto(targetUserId = userId))

    override suspend fun loadPrivateAlbum(userId: String): AppResult<List<MediaAlbumItemUi>> =
        when (val result = apiClient.listUserPrivateAlbum(userId)) {
            is AppResult.Success -> AppResult.Success(
                result.value.items.map { item ->
                    item.toPrivateAlbumUi(
                        mediaUrl = apiClient.mediaAssetContentUrl(item.mediaAsset.mediaAssetId)
                    )
                }
            )

            is AppResult.Failure -> result
        }
}

class SamplePublicProfileRepository : PublicProfileRepository {
    override suspend fun loadProfile(userId: String): AppResult<PublicProfileDto> =
        AppResult.Success(
            PublicProfileDto(
                userId = userId,
                displayName = "Maya",
                displayAge = 35,
                gender = "woman",
                orientation = "open",
                relationshipContext = "linked",
                discoveryStyle = "both",
                distanceLabel = "1.2 km",
                allowGridMessages = true,
                allowFlirts = true,
                allowAlbumRequests = true
            )
        )

    override suspend fun sendFlirt(userId: String): AppResult<SendFlirtResponseDto> =
        AppResult.Success(SendFlirtResponseDto(sent = true))

    override suspend fun requestAlbumAccess(userId: String): AppResult<RequestAlbumAccessResponseDto> =
        AppResult.Success(RequestAlbumAccessResponseDto(requested = true))

    override suspend fun loadPrivateAlbum(userId: String): AppResult<List<MediaAlbumItemUi>> =
        AppResult.Success(emptyList())
}

private fun MediaAlbumItemDto.toPrivateAlbumUi(mediaUrl: String? = null): MediaAlbumItemUi =
    MediaAlbumItemUi(
        mediaAssetId = mediaAsset.mediaAssetId,
        albumId = albumId,
        albumKind = MediaAlbumKindUi.Private,
        mediaType = mediaAsset.mediaType,
        contentType = mediaAsset.contentType,
        processingStatus = mediaAsset.processingStatus,
        moderationStatus = mediaAsset.moderationStatus,
        sortOrder = sortOrder,
        createdAt = addedAt,
        mediaUrl = mediaUrl
    )
