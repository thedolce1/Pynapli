package com.pynapli.app.activity

import com.pynapli.app.grid.GridCardTone
import com.pynapli.app.grid.GridProfileCardUi
import com.pynapli.shared.network.dto.ActivityEventDto

/**
 * Maps an Activity event actor to a [GridProfileCardUi] so a tapped Views/Flirts FOMO tile can
 * open the same profile detail sheet the Grid uses. `id` is the actor user id, which the profile
 * detail loader resolves to the canonical server profile.
 */
internal fun ActivityEventDto.toActivityProfileCardUi(): GridProfileCardUi =
    GridProfileCardUi(
        id = actorUserId,
        displayName = actorDisplayName,
        thumbnailUrl = actorPhotoUrl,
        thumbnailCacheKey = actorPhotoCacheKey,
        online = false,
        distanceLabel = distanceLabel,
        distanceMeters = distanceMeters,
        tone = GridCardTone.entries[actorUserId.stableIndex(GridCardTone.entries.size)],
    )

/** The event a profile opened from this tile can answer with "Like back" (likes only), else null. */
internal fun ActivityEventDto.likeBackEventIdOrNull(): String? = eventId.takeIf { type == "swipe_like" || type == "pineapple_like" }

private fun String.stableIndex(size: Int): Int = fold(0) { total, char -> total + char.code }.mod(size)
