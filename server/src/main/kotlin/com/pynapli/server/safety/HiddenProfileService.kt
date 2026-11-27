package com.pynapli.server.safety

import com.pynapli.server.contactblocks.ContactBlockVisibility
import com.pynapli.server.media.MediaAssetUrlResolver
import com.pynapli.server.media.MediaImageResolver
import com.pynapli.server.media.forAssetsOrOriginals
import com.pynapli.shared.monetization.FeatureAccessPolicy
import com.pynapli.shared.monetization.PynapliFeature
import com.pynapli.shared.network.dto.HiddenProfileSummaryDto
import com.pynapli.shared.network.dto.HiddenProfilesListDto
import java.util.UUID

/**
 * Hide is silent (the hidden person is never told) and one-way: it drops them from this viewer's Grid,
 * Swipe, Favorites and the other discovery surfaces, and the Grid backfills the slot. The unhide list
 * lives in the Safety Center.
 */
class HiddenProfileService(
    private val repository: HiddenProfileRepository,
    // The unhide list avatar (plan G2-4): grid thumbnails with stable cache keys. Null: names only.
    private val mediaAssetUrlResolver: MediaAssetUrlResolver? = null,
    private val mediaImages: MediaImageResolver? = null,
    // Someone hiding from the viewer ("Hide from people you know") keeps their photo out of it too.
    private val contactBlockVisibility: ContactBlockVisibility? = null,
) {
    suspend fun hideProfile(
        hiderUserId: UUID,
        targetUserIdText: String,
    ): HiddenProfileSummaryDto {
        FeatureAccessPolicy.requireAlwaysFreeSafetyFeature(PynapliFeature.HideProfile)
        val targetUserId = targetUserIdText.toHiddenProfileUuidOrThrow()
        if (targetUserId == hiderUserId) throw SafetyException("HIDE_SELF_FORBIDDEN")

        val record = repository.hideProfile(hiderUserId, targetUserId)
        return HiddenProfileSummaryDto(
            hiddenUserId = record.hiddenUserId.toString(),
            hiddenDisplayName = null,
            createdAt = record.createdAt.toString(),
        )
    }

    suspend fun unhideProfile(
        hiderUserId: UUID,
        targetUserIdText: String,
    ): Boolean {
        FeatureAccessPolicy.requireAlwaysFreeSafetyFeature(PynapliFeature.UnhideProfile)
        val targetUserId = targetUserIdText.toHiddenProfileUuidOrThrow()
        if (targetUserId == hiderUserId) throw SafetyException("HIDE_SELF_FORBIDDEN")
        return repository.unhideProfile(hiderUserId, targetUserId)
    }

    suspend fun listHiddenProfiles(hiderUserId: UUID): HiddenProfilesListDto {
        FeatureAccessPolicy.requireAlwaysFreeSafetyFeature(PynapliFeature.ListHiddenProfiles)
        val records = repository.listHiddenProfiles(hiderUserId)
        val hidingFromViewer =
            if (records.any { it.photoMediaAssetId != null }) contactBlockVisibility?.hiddenFrom(hiderUserId).orEmpty() else emptySet()
        val photoIds = records.filterNot { it.hiddenUserId in hidingFromViewer }.mapNotNull { it.photoMediaAssetId }
        val images = if (photoIds.isEmpty()) null else mediaImages.forAssetsOrOriginals(mediaAssetUrlResolver, photoIds)
        return HiddenProfilesListDto(
            hiddenProfiles =
                records.map { record ->
                    val tile = record.photoMediaAssetId?.takeUnless { record.hiddenUserId in hidingFromViewer }?.let { images?.tile(it) }
                    HiddenProfileSummaryDto(
                        hiddenUserId = record.hiddenUserId.toString(),
                        hiddenDisplayName = record.hiddenDisplayName,
                        createdAt = record.createdAt.toString(),
                        thumbnailUrl = tile?.url,
                        thumbnailCacheKey = tile?.cacheKey,
                    )
                },
        )
    }
}

private fun String.toHiddenProfileUuidOrThrow(): UUID =
    runCatching { UUID.fromString(this) }.getOrElse {
        throw SafetyException("INVALID_HIDDEN_PROFILE_TARGET")
    }
