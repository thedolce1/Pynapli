package com.pynapli.server.activity

import com.pynapli.server.distance.toPublicDistanceLabel
import com.pynapli.server.distance.toPublicDistanceMeters
import com.pynapli.server.media.MediaAssetUrlResolver
import com.pynapli.server.media.MediaImageSet
import com.pynapli.shared.network.dto.ActivityEventDto

internal fun ActivityEventRecord.toDto(
    mediaAssetUrlResolver: MediaAssetUrlResolver?,
    // G2-2: the row's small photo is the Grid thumbnail when ready, with a stable cache key.
    images: MediaImageSet = MediaImageSet.originalsOnly(mediaAssetUrlResolver),
): ActivityEventDto {
    val actorPhoto = images.tile(actorProfilePhotoMediaAssetId)
    return ActivityEventDto(
        eventId = eventId.toString(),
        type = type,
        actorUserId = actorUserId.toString(),
        actorDisplayName = actorDisplayName,
        actorPhotoUrl = actorPhoto?.url,
        actorPhotoCacheKey = actorPhoto?.cacheKey,
        count = count,
        occurredAt = occurredAt.toString(),
        seen = seen,
        distanceLabel = if (showDistance) distanceMeters?.toPublicDistanceLabel() else null,
        distanceMeters = if (showDistance) distanceMeters?.toPublicDistanceMeters() else null,
        albumRequestStatus = albumRequestStatus,
        matchId = matchId,
        conversationId = conversationId,
        source = source,
        psyncSessionId = psyncSessionId,
        pairUserId = pairUserId?.toString(),
        pairDisplayName = pairDisplayName,
        noteText = noteText,
    )
}
