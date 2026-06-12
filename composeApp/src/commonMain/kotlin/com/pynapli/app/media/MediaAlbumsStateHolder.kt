package com.pynapli.app.media

import com.pynapli.shared.core.result.AppResult
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class MediaAlbumsStateHolder(
    private val repository: MediaAlbumsRepository,
) {
    private val _state = MutableStateFlow(MediaAlbumsUiState())
    val state: StateFlow<MediaAlbumsUiState> = _state.asStateFlow()

    // Retained picked bytes for failed uploads, keyed by album kind; kept out of
    // MediaAlbumsUiState so the UI state stays cheap to copy and compare.
    private val retryableUploadMedia = mutableMapOf<MediaAlbumKindUi, PickedMediaForUpload>()

    val primaryOwnerProfilePhotoUrl: String?
        get() = state.value.primaryOwnerProfilePhotoUrl()

    suspend fun load() =
        coroutineScope {
            if (state.value.isLoading) return@coroutineScope

            _state.update { existing ->
                existing.copy(isLoading = true, errorMessage = null)
            }

            val profileDeferred = async { repository.loadAlbum(MediaAlbumKindUi.Profile) }
            val privateDeferred = async { repository.loadAlbum(MediaAlbumKindUi.Private) }
            val grantsDeferred = async { repository.loadPrivateGrants() }

            val profileResult = profileDeferred.await()
            val privateResult = privateDeferred.await()
            val grantsResult = grantsDeferred.await()

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
                            errorMessage = null,
                        )

                    profileResult is AppResult.Failure ->
                        existing.copy(
                            isLoading = false,
                            errorMessage = profileResult.error.message,
                        )

                    privateResult is AppResult.Failure ->
                        existing.copy(
                            isLoading = false,
                            errorMessage = privateResult.error.message,
                        )

                    grantsResult is AppResult.Failure ->
                        existing.copy(
                            profileItems = (profileResult as? AppResult.Success)?.value ?: existing.profileItems,
                            privateItems = (privateResult as? AppResult.Success)?.value ?: existing.privateItems,
                            isLoading = false,
                            errorMessage = grantsResult.error.message,
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
                errorMessage = null,
            )
        }

        when (val result = repository.revokePrivateGrant(granteeUserId)) {
            is AppResult.Success -> {
                _state.update { existing ->
                    existing.copy(
                        privateGrants = existing.privateGrants.filterNot { it.granteeUserId == granteeUserId },
                        revokingGranteeIds = existing.revokingGranteeIds - granteeUserId,
                    )
                }
            }

            is AppResult.Failure -> {
                _state.update { existing ->
                    existing.copy(
                        revokingGranteeIds = existing.revokingGranteeIds - granteeUserId,
                        errorMessage = result.error.message,
                    )
                }
            }
        }
    }

    suspend fun uploadPickedMedia(
        kind: MediaAlbumKindUi,
        media: PickedMediaForUpload,
    ) {
        val current = state.value
        if (kind == MediaAlbumKindUi.Profile && current.isUploadingProfile) return
        if (kind == MediaAlbumKindUi.Private && current.isUploadingPrivate) return

        setUploading(kind, uploading = true)
        when (val result = repository.uploadMedia(kind, media)) {
            is AppResult.Success -> {
                val item = result.value
                retryableUploadMedia.remove(kind)
                _state.update { existing ->
                    val profileItems =
                        if (kind == MediaAlbumKindUi.Profile) {
                            existing.profileItems.withPrependedUnique(item)
                        } else {
                            existing.profileItems
                        }
                    val privateItems =
                        if (kind == MediaAlbumKindUi.Private) {
                            existing.privateItems.withPrependedUnique(item)
                        } else {
                            existing.privateItems
                        }
                    existing.copy(
                        profileItems = profileItems,
                        privateItems = privateItems,
                        retryableUploadKinds = existing.retryableUploadKinds - kind,
                        isUploadingProfile = if (kind == MediaAlbumKindUi.Profile) false else existing.isUploadingProfile,
                        isUploadingPrivate = if (kind == MediaAlbumKindUi.Private) false else existing.isUploadingPrivate,
                        errorMessage = null,
                    )
                }
            }

            is AppResult.Failure -> {
                retryableUploadMedia[kind] = media
                _state.update { existing ->
                    existing.copy(
                        retryableUploadKinds = existing.retryableUploadKinds + kind,
                        isUploadingProfile = if (kind == MediaAlbumKindUi.Profile) false else existing.isUploadingProfile,
                        isUploadingPrivate = if (kind == MediaAlbumKindUi.Private) false else existing.isUploadingPrivate,
                        errorMessage = result.error.message,
                    )
                }
            }
        }
    }

    suspend fun retryUpload(kind: MediaAlbumKindUi) {
        val media = retryableUploadMedia[kind] ?: return
        uploadPickedMedia(kind, media)
    }

    suspend fun deleteMedia(
        kind: MediaAlbumKindUi,
        assetId: String,
    ) {
        if (assetId in state.value.busyAssetIds) return

        _state.update { existing ->
            existing.copy(busyAssetIds = existing.busyAssetIds + assetId, errorMessage = null)
        }

        when (val result = repository.deleteMedia(assetId)) {
            is AppResult.Success ->
                _state.update { existing ->
                    existing
                        .withItems(kind, existing.itemsFor(kind).filterNot { it.mediaAssetId == assetId })
                        .copy(busyAssetIds = existing.busyAssetIds - assetId)
                }

            is AppResult.Failure ->
                _state.update { existing ->
                    existing.copy(
                        busyAssetIds = existing.busyAssetIds - assetId,
                        errorMessage = result.error.message,
                    )
                }
        }
    }

    suspend fun moveMedia(
        kind: MediaAlbumKindUi,
        assetId: String,
        direction: MediaMoveDirection,
    ) {
        val items = state.value.itemsFor(kind)
        // The server validates the reorder request against the exact active set, so
        // hold off while any item in this album is still mid delete/reorder.
        if (items.any { it.mediaAssetId in state.value.busyAssetIds }) return

        val fromIndex = items.indexOfFirst { it.mediaAssetId == assetId }
        if (fromIndex < 0) return
        val toIndex = if (direction == MediaMoveDirection.Up) fromIndex - 1 else fromIndex + 1
        if (toIndex !in items.indices) return

        val reordered = items.toMutableList().apply { add(toIndex, removeAt(fromIndex)) }

        _state.update { existing ->
            existing.copy(busyAssetIds = existing.busyAssetIds + assetId, errorMessage = null)
        }

        when (val result = repository.reorderAlbum(kind, reordered.map { it.mediaAssetId })) {
            is AppResult.Success ->
                _state.update { existing ->
                    existing
                        .withItems(kind, result.value)
                        .copy(busyAssetIds = existing.busyAssetIds - assetId)
                }

            is AppResult.Failure ->
                _state.update { existing ->
                    existing.copy(
                        busyAssetIds = existing.busyAssetIds - assetId,
                        errorMessage = result.error.message,
                    )
                }
        }
    }

    fun showError(message: String) {
        _state.update { it.copy(errorMessage = message) }
    }

    fun clearError() {
        _state.update { it.copy(errorMessage = null) }
    }

    private fun setUploading(
        kind: MediaAlbumKindUi,
        uploading: Boolean,
    ) {
        _state.update { existing ->
            existing.copy(
                isUploadingProfile = if (kind == MediaAlbumKindUi.Profile) uploading else existing.isUploadingProfile,
                isUploadingPrivate = if (kind == MediaAlbumKindUi.Private) uploading else existing.isUploadingPrivate,
                errorMessage = null,
            )
        }
    }

    private fun List<MediaAlbumItemUi>.withPrependedUnique(item: MediaAlbumItemUi): List<MediaAlbumItemUi> = listOf(item) + filterNot { existing -> existing.mediaAssetId == item.mediaAssetId }
}
