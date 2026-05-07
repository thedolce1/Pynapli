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
    val endsAt: String? = null
)

@Serializable
data class SubscriptionInventoryResponseDto(
    val tiers: List<SubscriptionTierDto>
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
    val appStoreProductId: String? = null
)

object SubscriptionPurchaseSyncStatus {
    const val PENDING_VERIFICATION = "pending_verification"
    const val ALREADY_ACTIVE = "already_active"
}

@Serializable
data class SubmitPlayStoreSubscriptionPurchaseRequestDto(
    val productId: String,
    val purchaseToken: String,
    val packageName: String
)

@Serializable
data class SubscriptionPurchaseSyncResponseDto(
    val status: String,
    val entitlement: SubscriptionEntitlementDto
)
