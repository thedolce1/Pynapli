package com.pynapli.shared.network.dto

import kotlinx.serialization.Serializable

object SubscriptionTier {
    const val FREE = "free"
    const val SOCIAL = "social"
    const val SELECT = "select"
}

object SubscriptionProductId {
    const val PLAY_SOCIAL_MONTHLY = "pynapli_social_monthly"
    const val PLAY_SELECT_MONTHLY = "pynapli_select_monthly"
    const val BOOST_SINGLE = "pynapli_boost_1"
    const val PINEAPPLE_LIKES_FIVE = "pynapli_pineapple_likes_5"
    const val INTRO_NOTES_THREE = "pynapli_intro_notes_3"
}

object PremiumInventoryType {
    const val BOOST = "boost"
    const val SUPER_BOOST = "super_boost"
    const val PINEAPPLE_LIKE = "pineapple_like"
    const val INTRO_NOTE = "intro_note"
    const val EXTRA_PICK = "extra_pick"
}

object EntitlementSource {
    const val FREE = "free"
    const val OWN_SUBSCRIPTION = "own_subscription"
    const val LINKED_PARTNER_BENEFIT = "linked_partner_benefit"
    const val PROMO = "promo"
    const val ADMIN = "admin"
}

@Serializable
data class SubscriptionEntitlementDto(
    val tier: String,
    val source: String,
    val displayName: String,
    val adSupported: Boolean,
    val linkedPartnerIncluded: Boolean,
    val sourceSubscriptionId: String? = null,
    val sourceUserId: String? = null,
    val linkedPartnerUserId: String? = null,
    val startsAt: String? = null,
    val endsAt: String? = null,
)

@Serializable
data class SubscriptionInventoryResponseDto(
    val tiers: List<SubscriptionTierDto>,
    val premiumProducts: List<PremiumProductDto> = emptyList(),
    val balances: List<PremiumInventoryBalanceDto> = emptyList(),
    // When a Boost is running, when it ends (ISO-8601). Null when none is active.
    val activeBoostEndsAt: String? = null,
)

@Serializable
data class SubscriptionTierDto(
    val tier: String,
    val displayName: String,
    val monthlyPriceUsdCents: Int,
    val adSupported: Boolean,
    val linkedPartnerIncluded: Boolean,
    val features: List<String>,
    val playStoreProductId: String? = null,
    val appStoreProductId: String? = null,
)

@Serializable
data class PremiumProductDto(
    val productId: String,
    val inventoryType: String,
    val displayName: String,
    val description: String,
    val priceUsdCents: Int,
    val quantity: Int,
    val features: List<String>,
    val playStoreProductId: String? = null,
    val appStoreProductId: String? = null,
)

@Serializable
data class PremiumInventoryBalanceDto(
    val inventoryType: String,
    val quantity: Int,
    val nextExpiresAt: String? = null,
)

object SubscriptionPurchaseSyncStatus {
    const val ACTIVE = "active"
    const val PENDING_VERIFICATION = "pending_verification"
    const val ALREADY_ACTIVE = "already_active"
}

@Serializable
data class SubmitPlayStoreSubscriptionPurchaseRequestDto(
    val productId: String,
    val purchaseToken: String,
    val packageName: String,
)

@Serializable
data class SubmitDevStorePurchaseRequestDto(
    val productId: String,
)

/** A one-time Google Play purchase (Boost, Pineapple Likes, Intro Notes) to verify and credit. */
@Serializable
data class SubmitPlayStoreProductPurchaseRequestDto(
    val productId: String,
    val purchaseToken: String,
    val packageName: String,
)

@Serializable
data class StorePurchaseResponseDto(
    val status: String,
    val entitlement: SubscriptionEntitlementDto,
    val inventory: SubscriptionInventoryResponseDto,
)

@Serializable
data class SubscriptionPurchaseSyncResponseDto(
    val status: String,
    val entitlement: SubscriptionEntitlementDto,
)

@Serializable
data class BoostActivationDto(
    val activationId: String,
    val boostType: String,
    val startsAt: String,
    val endsAt: String,
    val alreadyActive: Boolean,
)
