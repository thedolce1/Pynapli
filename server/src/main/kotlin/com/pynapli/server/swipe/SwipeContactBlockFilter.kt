package com.pynapli.server.swipe

import java.util.UUID

/**
 * "Hide from people you know" on a Swipe card: a partner mini of someone hiding from the viewer is
 * removed, with the couple badge it carries, exactly like a partner whose visibility is hidden.
 */
internal fun SwipeCardRecord.withoutPartnerIn(hiddenUserIds: Set<UUID>): SwipeCardRecord =
    if (linkedPartnerUserId != null && linkedPartnerUserId in hiddenUserIds) {
        copy(
            linkedPartnerUserId = null,
            linkedPartnerDisplayName = null,
            linkedPartnerThumbnailMediaAssetId = null,
            linkedPartnerVerified = false,
        )
    } else {
        this
    }
