package com.pynapli.server.swipe

import com.pynapli.server.distance.toPublicDistanceLabel
import com.pynapli.server.distance.toPublicDistanceMeters
import com.pynapli.server.media.MediaAssetUrlResolver
import com.pynapli.shared.network.dto.LinkedPartnerMiniDto
import com.pynapli.shared.network.dto.MatchDto
import com.pynapli.shared.network.dto.SwipeActionResponseDto
import com.pynapli.shared.network.dto.SwipeCardDto
import java.util.UUID

internal class SwipeDtoMapper(
    private val mediaAssetUrlResolver: MediaAssetUrlResolver?
) {
    fun cardToDto(
        card: SwipeCardRecord,
        rankToken: String,
        online: Boolean,
        onlineUserIds: Set<UUID>
    ): SwipeCardDto {
        val resolvedPhotoUrls = if (mediaAssetUrlResolver == null) {
            emptyList()
        } else {
            val sourceIds = if (card.thumbnailMediaAssetIds.isNotEmpty()) {
                card.thumbnailMediaAssetIds
            } else {
                listOfNotNull(card.thumbnailMediaAssetId)
            }
            sourceIds.take(MAX_VISIBLE_PHOTOS).mapNotNull { mediaAssetUrlResolver.publicContentUrl(it) }
        }
        return SwipeCardDto(
            userId = card.userId.toString(),
            displayName = card.displayName,
            displayAge = card.displayAge,
            distanceLabel = if (card.showDistance) card.distanceMeters.toPublicDistanceLabel() else null,
            distanceMeters = if (card.showDistance) card.distanceMeters.toPublicDistanceMeters() else null,
            photoUrl = resolvedPhotoUrls.firstOrNull(),
            photoUrls = resolvedPhotoUrls,
            linkedPartner = card.linkedPartnerUserId?.let { partnerUserId ->
                LinkedPartnerMiniDto(
                    userId = partnerUserId.toString(),
                    displayName = card.linkedPartnerDisplayName,
                    thumbnailUrl = card.linkedPartnerThumbnailMediaAssetId
                        ?.let { mediaAssetUrlResolver?.publicContentUrl(it) },
                    online = partnerUserId in onlineUserIds
                )
            },
            rankToken = rankToken,
            online = online,
            shortBio = card.bio.toShortBio(),
            tags = card.tags.toVisibleTags(),
            verified = card.verified,
            visiting = card.visiting
        )
    }

    fun matchSummaryToDto(record: MatchSummaryRecord): MatchDto =
        MatchDto(
            matchId = record.matchId.toString(),
            matchedUserId = record.matchedUserId.toString(),
            matchedDisplayName = record.matchedDisplayName,
            matchedPhotoUrl = record.matchedThumbnailMediaAssetId
                ?.let { mediaAssetUrlResolver?.publicContentUrl(it) },
            createdAt = record.createdAt.toString()
        )
}

internal fun SwipeActionRecord.toResponseDto(viewerUserId: UUID): SwipeActionResponseDto =
    SwipeActionResponseDto(
        recorded = true,
        match = match?.toDto(viewerUserId)
    )

internal fun MatchRecord.toDto(viewerUserId: UUID): MatchDto =
    MatchDto(
        matchId = matchId.toString(),
        matchedUserId = if (userA == viewerUserId) userB.toString() else userA.toString(),
        matchedDisplayName = null,
        matchedPhotoUrl = null,
        createdAt = createdAt.toString()
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
