package com.pynapli.app.media

import androidx.compose.runtime.Immutable

@Immutable
data class MediaAlbumsUiState(
    val profileItems: List<MediaAlbumItemUi> = emptyList(),
    val privateItems: List<MediaAlbumItemUi> = emptyList(),
    val privateGrants: List<MediaPrivateGrantUi> = emptyList(),
    val revokingGranteeIds: Set<String> = emptySet(),
    val isLoading: Boolean = false,
    val isUploadingProfile: Boolean = false,
    val isUploadingPrivate: Boolean = false,
    val errorMessage: String? = null
)

@Immutable
data class MediaPrivateGrantUi(
    val granteeUserId: String,
    val displayName: String?,
    val grantedAt: String
) {
    val displayLabel: String
        get() = displayName?.takeIf { it.isNotBlank() } ?: "User ${granteeUserId.take(8)}"
}

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
