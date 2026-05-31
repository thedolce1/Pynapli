package com.pynapli.app.swipe

import com.pynapli.app.grid.GridCardTone
import com.pynapli.app.grid.GridProfileCardUi
import com.pynapli.app.grid.LinkedPartnerTone
import com.pynapli.app.grid.toUi
import com.pynapli.shared.network.dto.SwipeCardDto

internal fun SwipeCardDto.toProfileCardUi(): GridProfileCardUi {
    val toneIndex = userId.stableIndex(GridCardTone.entries.size)
    val linkedToneIndex = userId.stableIndex(LinkedPartnerTone.entries.size)
    return GridProfileCardUi(
        id = userId,
        displayName = displayName,
        thumbnailUrl = photoUrl,
        online = online,
        distanceLabel = distanceLabel,
        tone = GridCardTone.entries[toneIndex],
        linkedPartner = linkedPartner?.toUi(),
        linkedPartnerTone = linkedPartner?.let { LinkedPartnerTone.entries[linkedToneIndex] },
        distanceMeters = distanceMeters,
    )
}
