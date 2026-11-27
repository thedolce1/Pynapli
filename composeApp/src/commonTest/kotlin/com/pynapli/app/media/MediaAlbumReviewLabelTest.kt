package com.pynapli.app.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Section 4: the owner sees their own pending photo, labelled "Being reviewed". */
class MediaAlbumReviewLabelTest {
    @Test
    fun aPendingPhotoIsLabelledBeingReviewedForItsOwner() {
        val pending = item(id = "a", moderationStatus = "pending", processingStatus = "uploaded")

        assertTrue(pending.isBeingReviewed)
        assertTrue(pending.isOwnerVisibleImage)
        assertFalse(pending.isPublicDiscoverableImage)
        assertEquals("Being reviewed · only you can see it", pending.statusBadgeLabel)
    }

    @Test
    fun approvedFailedAndRemovedPhotosAreNotBeingReviewed() {
        assertFalse(item(id = "a", moderationStatus = "approved", processingStatus = "ready").isBeingReviewed)
        assertEquals(null, item(id = "a", moderationStatus = "approved", processingStatus = "ready").statusBadgeLabel)
        assertFalse(item(id = "b", moderationStatus = "pending", processingStatus = "failed").isBeingReviewed)
        assertEquals("Upload failed", item(id = "b", moderationStatus = "pending", processingStatus = "failed").statusBadgeLabel)
        assertFalse(item(id = "c", moderationStatus = "removed", processingStatus = "uploaded").isBeingReviewed)
    }

    @Test
    fun ownPreviewListsApprovedAndPendingPhotosInProfileOrder() {
        val state =
            MediaAlbumsUiState(
                profileItems =
                    listOf(
                        item(id = "second", moderationStatus = "approved", processingStatus = "ready", sortOrder = 1),
                        item(id = "first", moderationStatus = "pending", processingStatus = "uploaded", sortOrder = 0),
                        item(id = "gone", moderationStatus = "removed", processingStatus = "uploaded", sortOrder = 2),
                    ),
            )

        assertEquals(
            listOf(
                OwnPreviewPhoto(url = "https://media.test/first", beingReviewed = true),
                OwnPreviewPhoto(url = "https://media.test/second", beingReviewed = false),
            ),
            state.ownPreviewPhotos(),
        )
    }

    private fun item(
        id: String,
        moderationStatus: String,
        processingStatus: String,
        sortOrder: Int = 0,
    ) = MediaAlbumItemUi(
        mediaAssetId = id,
        albumId = "album-1",
        albumKind = MediaAlbumKindUi.Profile,
        mediaType = "image",
        contentType = "image/jpeg",
        processingStatus = processingStatus,
        moderationStatus = moderationStatus,
        sortOrder = sortOrder,
        createdAt = "2026-11-25T10:00:00Z",
        mediaUrl = "https://media.test/$id",
    )
}
