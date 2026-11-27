package com.pynapli.server.swipe

import com.pynapli.server.distance.toPublicDistanceLabel
import com.pynapli.server.distance.toPublicDistanceMeters
import com.pynapli.server.media.MediaAssetUrlResolver
import com.pynapli.server.media.MediaImageResolver
import com.pynapli.server.media.MediaImageSet
import com.pynapli.server.media.forAssetsOrOriginals
import com.pynapli.shared.network.dto.LinkedPartnerMiniDto
import com.pynapli.shared.network.dto.MatchDto
import com.pynapli.shared.network.dto.SwipeActionResponseDto
import com.pynapli.shared.network.dto.SwipeCardDto
import java.util.UUID

internal class SwipeDtoMapper(
    private val mediaAssetUrlResolver: MediaAssetUrlResolver?,
    // G2-2: partner minis use the Grid thumbnail when ready; every photo carries a stable cache key.
    private val mediaImages: MediaImageResolver? = null,
) {
    /** One readiness lookup for a page of cards (their photos and partner minis). */
    suspend fun imagesFor(cards: List<SwipeCardRecord>): MediaImageSet =
        mediaImages.forAssetsOrOriginals(
            mediaAssetUrlResolver,
            cards.flatMap { card -> card.photoSourceIds().take(1) + card.linkedPartnerThumbnailMediaAssetId },
        )

    fun cardToDto(
        card: SwipeCardRecord,
        rankToken: String,
        online: Boolean,
        onlineUserIds: Set<UUID>,
        images: MediaImageSet = MediaImageSet.originalsOnly(mediaAssetUrlResolver),
    ): SwipeCardDto {
        // A Swipe card is full-bleed: always the originals (a 480 px thumbnail would be soft there).
        val photos =
            if (mediaAssetUrlResolver == null) {
                emptyList()
            } else {
                card.photoSourceIds().take(MAX_VISIBLE_PHOTOS).mapNotNull { images.original(it) }
            }
        val resolvedPhotoUrls = photos.map { it.url }
        val partnerTile = images.tile(card.linkedPartnerThumbnailMediaAssetId)
        return SwipeCardDto(
            userId = card.userId.toString(),
            displayName = card.displayName,
            displayAge = card.displayAge,
            distanceLabel = if (card.showDistance) card.distanceMeters.toPublicDistanceLabel() else null,
            distanceMeters = if (card.showDistance) card.distanceMeters.toPublicDistanceMeters() else null,
            photoUrl = resolvedPhotoUrls.firstOrNull(),
            photoUrls = resolvedPhotoUrls,
            photoCacheKeys = photos.map { it.cacheKey },
            photoThumbnailCacheKey = images.thumbnailCacheKey(card.photoSourceIds().firstOrNull()),
            linkedPartner =
                card.linkedPartnerUserId?.let { partnerUserId ->
                    LinkedPartnerMiniDto(
                        userId = partnerUserId.toString(),
                        displayName = card.linkedPartnerDisplayName,
                        thumbnailUrl = partnerTile?.url,
                        thumbnailCacheKey = partnerTile?.cacheKey,
                        online = partnerUserId in onlineUserIds,
                    )
                },
            rankToken = rankToken,
            online = online,
            shortBio = card.bio.toShortBio(),
            tags = card.tags.toVisibleTags(),
            verified = card.verified,
            coupleVerified = card.verified && card.linkedPartnerUserId != null && card.linkedPartnerVerified,
            visiting = card.visiting,
        )
    }
}

private fun SwipeCardRecord.photoSourceIds(): List<UUID> = thumbnailMediaAssetIds.ifEmpty { listOfNotNull(thumbnailMediaAssetId) }

internal fun SwipeActionRecord.toResponseDto(viewerUserId: UUID): SwipeActionResponseDto =
    SwipeActionResponseDto(
        recorded = true,
        match = match?.toDto(viewerUserId),
    )

internal fun MatchRecord.toDto(viewerUserId: UUID): MatchDto =
    MatchDto(
        matchId = matchId.toString(),
        matchedUserId = if (userA == viewerUserId) userB.toString() else userA.toString(),
        matchedDisplayName = null,
        matchedPhotoUrl = null,
        createdAt = createdAt.toString(),
    )

private fun String?.toShortBio(): String? {
    val trimmed = this?.trim().orEmpty()
    if (trimmed.isEmpty()) return null
    return if (trimmed.length <= MAX_SHORT_BIO_CHARS) trimmed else trimmed.take(MAX_SHORT_BIO_CHARS)
}

private fun List<String>.toVisibleTags(): List<String> {
    if (isEmpty()) return emptyList()
    val seen = LinkedHashSet<String>()
    val display = mutableListOf<String>()
    for (raw in this) {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) continue
        val key = trimmed.lowercase()
        if (seen.add(key)) {
            display += trimmed
            if (display.size >= MAX_VISIBLE_TAGS) break
        }
    }
    return display
}

private const val MAX_SHORT_BIO_CHARS = 120
private const val MAX_VISIBLE_TAGS = 3
private const val MAX_VISIBLE_PHOTOS = 5
