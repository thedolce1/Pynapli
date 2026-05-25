package com.pynapli.server.subscriptions

import com.pynapli.shared.network.dto.SubscriptionProductId
import com.pynapli.shared.network.dto.SubscriptionTier

internal fun playStoreTierForProduct(productId: String): String? =
    when (productId) {
        SubscriptionProductId.PLAY_SOCIAL_MONTHLY -> SubscriptionTier.SOCIAL
        SubscriptionProductId.PLAY_SELECT_MONTHLY -> SubscriptionTier.SELECT
        else -> null
    }
