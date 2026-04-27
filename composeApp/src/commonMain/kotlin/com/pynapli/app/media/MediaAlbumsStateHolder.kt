package com.pynapli.app.media

import androidx.compose.runtime.Immutable
import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.network.MediaByteUploader
import com.pynapli.shared.network.PynapliApiClient
import com.pynapli.shared.network.dto.CompleteMediaUploadRequestDto
import com.pynapli.shared.network.dto.CreateMediaUploadRequestDto
import com.pynapli.shared.network.dto.MediaAlbumItemDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

@Immutable
data class MediaAlbumsUiState(
    val profileItems: List<MediaAlbumItemUi> = emptyList(),
    val privateItems: List<MediaAlbumItemUi> = emptyList(),
    val isLoading: Boolean = false,
    val isUploadingProfile: Boolean = false,
    val isUploadingPrivate: Boolean = false,
    val errorMessage: String? = null
)

@Immutable
data class MediaAlbumItemUi(
    val mediaAssetId: String,
    val albumId: String,
    val albumKind: MediaAlbumKindUi,
    val mediaType: String,
    val contentType: String,
    val processingStatus: String,
    val moderationStatus: String,
    val sortOrder: Int,
    val createdAt: String,
    val mediaUrl: String? = null,
    val uploadUrl: String? = null
) {
    val statusLabel: String
        get() = when (processingStatus) {
            "pending_upload" -> "Pending upload"
            "uploaded" -> "Uploaded"
            "processing" -> "Processing"
            "ready" -> "Ready"
            "failed" -> "Failed"
            else -> processingStatus.replace('_', ' ').replaceFirstChar { it.uppercase() }
        }

    val isOwnerVisibleImage: Boolean
        get() = mediaUrl != null &&
            mediaType == "image" &&
            processingStatus in ownerVisibleProcessingStatuses &&
            moderationStatus in ownerVisibleModerationStatuses

    val isPublicDiscoverableImage: Boolean
        get() = mediaUrl != null &&
            mediaType == "image" &&
            processingStatus == "ready" &&
            moderationStatus == "approved"
}

enum class MediaAlbumKindUi(
    val apiValue: String,
    val title: String
) {
    Profile("profile", "Profile photos"),
    Private("private", "Private album")
}

class PickedMediaForUpload(
    val contentType: String,
    val byteSize: Long,
    val pixelWidth: Int?,
    val pixelHeight: Int?,
    val bytes: ByteArray
)

class MediaAlbumsStateHolder(
    private val repository: MediaAlbumsRepository
) {
    private val _state = MutableStateFlow(MediaAlbumsUiState())
    val state: StateFlow<MediaAlbumsUiState> = _state.asStateFlow()

    val primaryOwnerProfilePhotoUrl: String?
        get() = state.value.profileItems.primaryOwnerVisibleImageUrl()

    suspend fun load() {
        if (state.value.isLoading) return

        _state.update { existing ->
            existing.copy(isLoading = true, errorMessage = null)
        }

        val profileResult = repository.loadAlbum(MediaAlbumKindUi.Profile)
        val privateResult = repository.loadAlbum(MediaAlbumKindUi.Private)

        _state.update { existing ->
            when {
                profileResult is AppResult.Success && privateResult is AppResult.Success ->
                    existing.copy(
                        profileItems = profileResult.value,
                        privateItems = privateResult.value,
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

                else -> existing.copy(isLoading = false)
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

interface MediaAlbumsRepository {
    suspend fun loadAlbum(kind: MediaAlbumKindUi): AppResult<List<MediaAlbumItemUi>>

    suspend fun uploadMedia(
        kind: MediaAlbumKindUi,
        media: PickedMediaForUpload
    ): AppResult<MediaAlbumItemUi>
}

class ApiMediaAlbumsRepository(
    private val apiClient: PynapliApiClient,
    private val mediaByteUploader: MediaByteUploader
) : MediaAlbumsRepository {
    override suspend fun loadAlbum(kind: MediaAlbumKindUi): AppResult<List<MediaAlbumItemUi>> =
        when (val result = apiClient.listMediaAlbum(kind.apiValue)) {
            is AppResult.Success -> AppResult.Success(
                result.value.items.map { item ->
                    item.toUi(
                        kind = kind,
                        mediaUrl = apiClient.mediaAssetContentUrl(item.mediaAsset.mediaAssetId)
                    )
                }
            )

            is AppResult.Failure -> result
        }

    override suspend fun uploadMedia(
        kind: MediaAlbumKindUi,
        media: PickedMediaForUpload
    ): AppResult<MediaAlbumItemUi> {
        val slot = when (
            val result = apiClient.createMediaUpload(
                CreateMediaUploadRequestDto(
                    albumKind = kind.apiValue,
                    mediaType = "image",
                    contentType = media.contentType,
                    byteSize = media.byteSize,
                    pixelWidth = media.pixelWidth,
                    pixelHeight = media.pixelHeight
                )
            )
        ) {
            is AppResult.Success -> result.value
            is AppResult.Failure -> return result
        }

        when (val result = mediaByteUploader.upload(slot, media.bytes)) {
            is AppResult.Success -> Unit
            is AppResult.Failure -> return result
        }

        return apiClient.completeMediaUpload(
            mediaAssetId = slot.mediaAsset.mediaAssetId,
            request = CompleteMediaUploadRequestDto(albumId = slot.album.albumId)
        ).let { result ->
            when (result) {
                is AppResult.Success -> AppResult.Success(
                    result.value.toUi(
                        kind = kind,
                        mediaUrl = apiClient.mediaAssetContentUrl(result.value.mediaAsset.mediaAssetId)
                    )
                )
                is AppResult.Failure -> result
            }
        }
    }
}

class SampleMediaAlbumsRepository : MediaAlbumsRepository {
    private var nextAssetIndex = 1
    private val itemsByKind = mutableMapOf<MediaAlbumKindUi, List<MediaAlbumItemUi>>()

    override suspend fun loadAlbum(kind: MediaAlbumKindUi): AppResult<List<MediaAlbumItemUi>> =
        AppResult.Success(itemsByKind[kind].orEmpty())

    override suspend fun uploadMedia(
        kind: MediaAlbumKindUi,
        media: PickedMediaForUpload
    ): AppResult<MediaAlbumItemUi> {
        val index = nextAssetIndex++
        val asset = com.pynapli.shared.network.dto.MediaAssetDto(
            mediaAssetId = "sample-media-$index",
            ownerUserId = "viewer",
            mediaType = "image",
            contentType = media.contentType,
            byteSize = media.byteSize,
            pixelWidth = media.pixelWidth,
            pixelHeight = media.pixelHeight,
            moderationStatus = "pending",
            processingStatus = "uploaded",
            createdAt = "2026-04-24T18:00:00Z",
            updatedAt = "2026-04-24T18:00:00Z"
        )
        val item = MediaAlbumItemDto(
            albumId = "sample-${kind.apiValue}-album",
            mediaAsset = asset,
            sortOrder = 0,
            addedAt = "2026-04-24T18:00:00Z"
        )
        return AppResult.Success(
            item.toUi(
                kind = kind,
                mediaUrl = "https://media.example/sample-media-$index"
            )
        )
            .also { result ->
                itemsByKind[kind] = listOf(result.value) + itemsByKind[kind].orEmpty()
            }
    }
}

private fun MediaAlbumItemDto.toUi(
    kind: MediaAlbumKindUi,
    mediaUrl: String? = null
): MediaAlbumItemUi =
    MediaAlbumItemUi(
        mediaAssetId = mediaAsset.mediaAssetId,
        albumId = albumId,
        albumKind = kind,
        mediaType = mediaAsset.mediaType,
        contentType = mediaAsset.contentType,
        processingStatus = mediaAsset.processingStatus,
        moderationStatus = mediaAsset.moderationStatus,
        sortOrder = sortOrder,
        createdAt = addedAt,
        mediaUrl = mediaUrl
    )

fun MediaAlbumsUiState.primaryOwnerProfilePhotoUrl(): String? =
    profileItems.primaryOwnerVisibleImageUrl()

private fun List<MediaAlbumItemUi>.primaryOwnerVisibleImageUrl(): String? =
    asSequence()
        .filter { item -> item.albumKind == MediaAlbumKindUi.Profile && item.isOwnerVisibleImage }
        .sortedWith(compareBy<MediaAlbumItemUi> { it.sortOrder }.thenByDescending { it.createdAt })
        .firstOrNull()
        ?.mediaUrl

private val ownerVisibleProcessingStatuses = setOf("uploaded", "processing", "ready")
private val ownerVisibleModerationStatuses = setOf("pending", "approved")
