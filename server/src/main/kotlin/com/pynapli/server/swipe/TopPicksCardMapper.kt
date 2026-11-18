package com.pynapli.server.swipe

import com.pynapli.server.distance.toPublicDistanceLabel
import com.pynapli.server.distance.toPublicDistanceMeters
import com.pynapli.server.media.MediaAssetUrlResolver
import com.pynapli.server.presence.PresenceService
import com.pynapli.shared.network.dto.LinkedPartnerMiniDto
import com.pynapli.shared.network.dto.SwipeCardDto
import java.util.UUID

internal class TopPicksCardMapper(
    private val presenceService: PresenceService?,
    private val mediaAssetUrlResolver: MediaAssetUrlResolver?,
) {
    suspend fun onlineFor(ids: Set<UUID>): Set<UUID> {
        val service = presenceService ?: return emptySet()
        if (ids.isEmpty()) return emptySet()
        return runCatching { service.onlineUserIds(ids) }.getOrDefault(emptySet())
    }

    fun toCardDto(
        record: SwipeCardRecord,
        rankToken: String,
        onlineIds: Set<UUID>,
    ): SwipeCardDto {
        val resolvedPhotoUrls =
            mediaAssetUrlResolver?.let { resolver ->
                val sourceIds =
                    if (record.thumbnailMediaAssetIds.isNotEmpty()) {
                        record.thumbnailMediaAssetIds
                    } else {
                        listOfNotNull(record.thumbnailMediaAssetId)
                    }
                sourceIds.take(TOP_PICKS_MAX_VISIBLE_PHOTOS).mapNotNull { resolver.publicContentUrl(it) }
            } ?: emptyList()

        return SwipeCardDto(
            userId = record.userId.toString(),
            displayName = record.displayName,
            displayAge = record.displayAge,
            distanceLabel = if (record.showDistance) record.distanceMeters.toPublicDistanceLabel() else null,
            distanceMeters = if (record.showDistance) record.distanceMeters.toPublicDistanceMeters() else null,
            photoUrl = resolvedPhotoUrls.firstOrNull(),
            photoUrls = resolvedPhotoUrls,
            linkedPartner =
                record.linkedPartnerUserId?.let { partnerUserId ->
                    LinkedPartnerMiniDto(
                        userId = partnerUserId.toString(),
                        displayName = record.linkedPartnerDisplayName,
                        thumbnailUrl =
                            record.linkedPartnerThumbnailMediaAssetId
                                ?.let { mediaAssetUrlResolver?.publicContentUrl(it) },
                        online = partnerUserId in onlineIds,
                    )
                },
            rankToken = rankToken,
            online = record.userId in onlineIds,
            shortBio =
                record.bio
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?.take(TOP_PICKS_MAX_SHORT_BIO_CHARS),
            tags = record.tags.take(TOP_PICKS_MAX_VISIBLE_TAGS),
            verified = record.verified,
            coupleVerified = record.verified && record.linkedPartnerUserId != null && record.linkedPartnerVerified,
            visiting = record.visiting,
        )
    }
}
