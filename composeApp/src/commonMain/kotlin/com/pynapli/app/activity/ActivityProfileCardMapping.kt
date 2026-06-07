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
        online = false,
        distanceLabel = distanceLabel,
        distanceMeters = distanceMeters,
        tone = GridCardTone.entries[actorUserId.stableIndex(GridCardTone.entries.size)],
    )

private fun String.stableIndex(size: Int): Int = fold(0) { total, char -> total + char.code }.mod(size)
