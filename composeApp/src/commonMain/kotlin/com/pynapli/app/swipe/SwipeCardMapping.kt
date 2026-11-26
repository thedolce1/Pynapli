package com.pynapli.app.swipe

import com.pynapli.app.grid.GridProfileCardUi
import com.pynapli.app.grid.LinkedPartnerTone
import com.pynapli.app.grid.toUi
import com.pynapli.shared.network.dto.SwipeCardDto

internal fun SwipeCardDto.toProfileCardUi(): GridProfileCardUi {
    val linkedToneIndex = userId.stableIndex(LinkedPartnerTone.entries.size)
    return GridProfileCardUi(
        id = userId,
        displayName = displayName,
        thumbnailUrl = photoUrl,
        online = online,
        distanceLabel = distanceLabel,
        linkedPartner = linkedPartner?.toUi(),
        linkedPartnerTone = linkedPartner?.let { LinkedPartnerTone.entries[linkedToneIndex] },
        distanceMeters = distanceMeters,
        verified = verified,
        coupleVerified = coupleVerified && linkedPartner != null,
    )
}
