package com.pynapli.server.admin

import com.pynapli.shared.network.dto.SubscriptionEntitlementDto
import kotlinx.serialization.Serializable

/**
 * Admin subscription support view (ADMIN_MODERATION_CONSOLE_SPEC "Subscription support view").
 * Read-only, for support/billing questions: the user's resolved entitlement, their owned store
 * subscription records. A linked-partner benefit shows up in the entitlement itself (its source and
 * sourceUserId), computed live from the partner link. Raw purchase tokens and order ids are never exposed — only product id, status, and verification timestamps a support
 * agent needs to answer "why does this user have this tier?".
 */
@Serializable
data class AdminSubscriptionSupportDto(
    val userId: String,
    val entitlement: SubscriptionEntitlementDto,
    val subscriptions: List<AdminSubscriptionRecordDto> = emptyList(),
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
