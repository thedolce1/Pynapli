package com.pynapli.app.media

import androidx.compose.runtime.Immutable

/**
 * Client mirror of the server album caps (`MediaService.PROFILE_ALBUM_PHOTO_CAP` /
 * `PRIVATE_ALBUM_PHOTO_CAP`). The server owns enforcement — exceeding a cap returns
 * MEDIA_ALBUM_PHOTO_CAP_EXCEEDED (400) — these constants only drive the disabled
 * Add tile and the "6 of 6" affordance.
 */
object MediaAlbumCaps {
    const val PROFILE_PHOTO_CAP = 6
    const val PRIVATE_PHOTO_CAP = 12

    fun capFor(kind: MediaAlbumKindUi): Int =
        when (kind) {
            MediaAlbumKindUi.Profile -> PROFILE_PHOTO_CAP
            MediaAlbumKindUi.Private -> PRIVATE_PHOTO_CAP
        }
}

@Immutable
data class MediaAlbumsUiState(
    val profileItems: List<MediaAlbumItemUi> = emptyList(),
    val privateItems: List<MediaAlbumItemUi> = emptyList(),
    val privateGrants: List<MediaPrivateGrantUi> = emptyList(),
    val revokingGranteeIds: Set<String> = emptySet(),
    // Per-item busy tracking for delete, mirroring the revokingGranteeIds pattern.
    val busyAssetIds: Set<String> = emptySet(),
    // Albums whose new order is being persisted after a drag/set-as-main; gates starting a
    // fresh drag without flashing a per-tile spinner the way busyAssetIds would.
    val reorderingKinds: Set<MediaAlbumKindUi> = emptySet(),
    // Album kinds whose last upload failed with the picked media retained for retryUpload().
    val retryableUploadKinds: Set<MediaAlbumKindUi> = emptySet(),
    // A freshly picked profile photo awaiting the square crop step (profile album only,
    // per the founder default); the crop overlay renders while this is non-null.
    val pendingCropMedia: PickedMediaForUpload? = null,
    val isLoading: Boolean = false,
    val isUploadingProfile: Boolean = false,
    val isUploadingPrivate: Boolean = false,
    val errorMessage: String? = null,
) {
    fun itemsFor(kind: MediaAlbumKindUi): List<MediaAlbumItemUi> =
        when (kind) {
            MediaAlbumKindUi.Profile -> profileItems
            MediaAlbumKindUi.Private -> privateItems
        }

    /** Owner listings only contain active items, so the count vs cap is just the list size. */
    fun canAddMore(kind: MediaAlbumKindUi): Boolean = itemsFor(kind).size < MediaAlbumCaps.capFor(kind)

    /** e.g. "4 of 6"; rendered on the Add tile when the album is at cap. */
    fun capCountLabel(kind: MediaAlbumKindUi): String = "${itemsFor(kind).size} of ${MediaAlbumCaps.capFor(kind)}"

    fun isUploading(kind: MediaAlbumKindUi): Boolean =
        when (kind) {
            MediaAlbumKindUi.Profile -> isUploadingProfile
            MediaAlbumKindUi.Private -> isUploadingPrivate
        }

    /** True while the album can accept a drag: not loading, uploading, or mid reorder-commit. */
    fun canReorder(kind: MediaAlbumKindUi): Boolean = !isLoading && !isUploading(kind) && kind !in reorderingKinds && itemsFor(kind).size > 1

    internal fun withItems(
        kind: MediaAlbumKindUi,
        items: List<MediaAlbumItemUi>,
    ): MediaAlbumsUiState =
        when (kind) {
            MediaAlbumKindUi.Profile -> copy(profileItems = items)
            MediaAlbumKindUi.Private -> copy(privateItems = items)
        }
}

@Immutable
data class MediaPrivateGrantUi(
    val granteeUserId: String,
    val displayName: String?,
    val grantedAt: String,
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
    val uploadUrl: String? = null,
) {
    /** Upload never finished (or processing failed); the bytes never became a visible photo. */
    val isUploadFailed: Boolean
        get() = processingStatus in failedProcessingStatuses

    /**
     * Owner-facing moderation/processing badge derived from the server's nested asset states.
     * Null means approved-and-ready: no badge. The server owns visibility; this is presentation only.
     */
    val statusBadgeLabel: String?
        get() =
            when {
                isUploadFailed -> "Upload failed"
                moderationStatus == "removed" -> "Not approved"
                moderationStatus == "pending" || moderationStatus == "held" -> "In review — only you can see this"
                else -> null
            }

    val isOwnerVisibleImage: Boolean
        get() =
            mediaUrl != null &&
                mediaType == "image" &&
                processingStatus in ownerVisibleProcessingStatuses &&
                moderationStatus in ownerVisibleModerationStatuses

    val isPublicDiscoverableImage: Boolean
        get() =
            mediaUrl != null &&
                mediaType == "image" &&
                processingStatus == "ready" &&
                moderationStatus == "approved"
}

enum class MediaAlbumKindUi(
    val apiValue: String,
    val title: String,
) {
    Profile("profile", "Profile photos"),
    Private("private", "Private album"),
}

class PickedMediaForUpload(
    val contentType: String,
    val byteSize: Long,
    val pixelWidth: Int?,
    val pixelHeight: Int?,
    val bytes: ByteArray,
)

fun MediaAlbumsUiState.primaryOwnerProfilePhotoUrl(): String? = profileItems.primaryOwnerVisibleImageUrl()

private fun List<MediaAlbumItemUi>.primaryOwnerVisibleImageUrl(): String? =
    asSequence()
        .filter { item -> item.albumKind == MediaAlbumKindUi.Profile && item.isOwnerVisibleImage }
        .sortedWith(compareBy<MediaAlbumItemUi> { it.sortOrder }.thenByDescending { it.createdAt })
        .firstOrNull()
        ?.mediaUrl

private val failedProcessingStatuses = setOf("pending_upload", "failed")
private val ownerVisibleProcessingStatuses = setOf("uploaded", "processing", "ready")
private val ownerVisibleModerationStatuses = setOf("pending", "approved")
