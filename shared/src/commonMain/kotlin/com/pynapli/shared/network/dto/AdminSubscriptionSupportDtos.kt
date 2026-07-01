package com.pynapli.shared.network.dto

import kotlinx.serialization.Serializable

/**
 * Admin subscription support view (ADMIN_MODERATION_CONSOLE_SPEC "Subscription support view").
 * Read-only, for support/billing questions: the user's resolved entitlement, their owned store
 * subscription records, and the linked-partner benefits they grant or receive. Raw purchase tokens
 * and order ids are never exposed — only product id, status, and verification timestamps a support
 * agent needs to answer "why does this user have this tier?".
 */
@Serializable
data class AdminSubscriptionSupportDto(
    val userId: String,
    val entitlement: SubscriptionEntitlementDto,
    val subscriptions: List<AdminSubscriptionRecordDto> = emptyList(),
    // Benefits this user grants to a linked partner (this user is the subscriber).
    val benefitsGranted: List<AdminLinkedBenefitDto> = emptyList(),
    // Benefits this user receives from a linked partner (this user is the beneficiary).
    val benefitsReceived: List<AdminLinkedBenefitDto> = emptyList(),
)

@Serializable
data class AdminSubscriptionRecordDto(
    val platform: String,
    val tier: String,
    val status: String,
    val externalProductId: String? = null,
    val externalStatus: String? = null,
    val acknowledgementState: String? = null,
    val currentPeriodStart: String,
    val currentPeriodEnd: String,
    val lastVerifiedAt: String? = null,
)

@Serializable
data class AdminLinkedBenefitDto(
    val otherUserId: String,
    val tier: String,
    val status: String,
    val startsAt: String,
    val endsAt: String? = null,
)
