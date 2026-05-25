package com.pynapli.app.media

import com.pynapli.shared.core.result.AppResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class MediaAlbumsStateHolder(
    private val repository: MediaAlbumsRepository
) {
    private val _state = MutableStateFlow(MediaAlbumsUiState())
    val state: StateFlow<MediaAlbumsUiState> = _state.asStateFlow()

    val primaryOwnerProfilePhotoUrl: String?
        get() = state.value.primaryOwnerProfilePhotoUrl()

    suspend fun load() {
        if (state.value.isLoading) return

        _state.update { existing ->
            existing.copy(isLoading = true, errorMessage = null)
        }

        val profileResult = repository.loadAlbum(MediaAlbumKindUi.Profile)
        val privateResult = repository.loadAlbum(MediaAlbumKindUi.Private)
        val grantsResult = repository.loadPrivateGrants()

        _state.update { existing ->
            when {
                profileResult is AppResult.Success &&
                    privateResult is AppResult.Success &&
                    grantsResult is AppResult.Success ->
                    existing.copy(
                        profileItems = profileResult.value,
                        privateItems = privateResult.value,
                        privateGrants = grantsResult.value,
                        isLoading = false,
                        errorMessage = null
                    )

                profileResult is AppResult.Failure ->
                    existing.copy(
                        isLoading = false,
                        errorMessage = profileResult.error.message
                    )

                privateResult is AppResult.Failure ->
                    existing.copy(
                        isLoading = false,
                        errorMessage = privateResult.error.message
                    )

                grantsResult is AppResult.Failure ->
                    existing.copy(
                        profileItems = (profileResult as? AppResult.Success)?.value ?: existing.profileItems,
                        privateItems = (privateResult as? AppResult.Success)?.value ?: existing.privateItems,
                        isLoading = false,
                        errorMessage = grantsResult.error.message
                    )

                else -> existing.copy(isLoading = false)
            }
        }
    }

    suspend fun revokeGrant(granteeUserId: String) {
        val current = state.value
        if (granteeUserId in current.revokingGranteeIds) return

        _state.update { existing ->
            existing.copy(
                revokingGranteeIds = existing.revokingGranteeIds + granteeUserId,
                errorMessage = null
            )
        }

        when (val result = repository.revokePrivateGrant(granteeUserId)) {
            is AppResult.Success -> {
                _state.update { existing ->
                    existing.copy(
                        privateGrants = existing.privateGrants.filterNot { it.granteeUserId == granteeUserId },
                        revokingGranteeIds = existing.revokingGranteeIds - granteeUserId
                    )
                }
            }

            is AppResult.Failure -> {
                _state.update { existing ->
                    existing.copy(
                        revokingGranteeIds = existing.revokingGranteeIds - granteeUserId,
                        errorMessage = result.error.message
                    )
                }
            }
        }
    }

    suspend fun uploadPickedMedia(
        kind: MediaAlbumKindUi,
        media: PickedMediaForUpload
    ) {
        val current = state.value
        if (kind == MediaAlbumKindUi.Profile && current.isUploadingProfile) return
        if (kind == MediaAlbumKindUi.Private && current.isUploadingPrivate) return

        setUploading(kind, uploading = true)
        when (val result = repository.uploadMedia(kind, media)) {
            is AppResult.Success -> {
                val item = result.value
                _state.update { existing ->
                    val profileItems = if (kind == MediaAlbumKindUi.Profile) {
                        existing.profileItems.withPrependedUnique(item)
                    } else {
                        existing.profileItems
                    }
                    val privateItems = if (kind == MediaAlbumKindUi.Private) {
                        existing.privateItems.withPrependedUnique(item)
                    } else {
                        existing.privateItems
                    }
                    existing.copy(
                        profileItems = profileItems,
                        privateItems = privateItems,
                        isUploadingProfile = if (kind == MediaAlbumKindUi.Profile) false else existing.isUploadingProfile,
                        isUploadingPrivate = if (kind == MediaAlbumKindUi.Private) false else existing.isUploadingPrivate,
                        errorMessage = null
                    )
                }
            }

            is AppResult.Failure -> {
                _state.update { existing ->
                    existing.copy(
                        isUploadingProfile = if (kind == MediaAlbumKindUi.Profile) false else existing.isUploadingProfile,
                        isUploadingPrivate = if (kind == MediaAlbumKindUi.Private) false else existing.isUploadingPrivate,
                        errorMessage = result.error.message
                    )
                }
            }
        }
    }

    fun showError(message: String) {
        _state.update { it.copy(errorMessage = message) }
    }

    fun clearError() {
        _state.update { it.copy(errorMessage = null) }
    }

    private fun setUploading(kind: MediaAlbumKindUi, uploading: Boolean) {
        _state.update { existing ->
            existing.copy(
                isUploadingProfile = if (kind == MediaAlbumKindUi.Profile) uploading else existing.isUploadingProfile,
                isUploadingPrivate = if (kind == MediaAlbumKindUi.Private) uploading else existing.isUploadingPrivate,
                errorMessage = null
            )
        }
    }

    private fun List<MediaAlbumItemUi>.withPrependedUnique(item: MediaAlbumItemUi): List<MediaAlbumItemUi> =
        listOf(item) + filterNot { existing -> existing.mediaAssetId == item.mediaAssetId }
}
