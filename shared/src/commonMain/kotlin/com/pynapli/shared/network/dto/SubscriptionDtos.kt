package com.pynapli.shared.network.dto

import kotlinx.serialization.Serializable

object SubscriptionTier {
    const val FREE = "free"
    const val SOCIAL = "social"
    const val SELECT = "select"
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
    val features: List<String>
)
