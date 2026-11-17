package com.pynapli.server.admin

import java.time.Instant
import java.util.UUID

/**
 * Read-only subscription facts for the admin support view: the user's owned store-subscription rows
 * (from `subscriptions`), plus the audit write that records the reveal. The resolved entitlement,
 * including any live linked-partner benefit, is composed by the service from the existing
 * subscription entitlement path.
 */
interface AdminSubscriptionSupportRepository {
    /** Returns null when the user does not exist. */
    suspend fun facts(userId: UUID): AdminSubscriptionSupportFacts?

    suspend fun recordReviewAudit(
        actorId: String,
        userId: UUID,
        reason: String,
    )
}

data class AdminSubscriptionSupportFacts(
    val subscriptions: List<AdminSubscriptionRecordFact>,
)

data class AdminSubscriptionRecordFact(
    val platform: String,
    val tier: String,
    val status: String,
    val externalProductId: String?,
    val externalStatus: String?,
    val acknowledgementState: String?,
    val currentPeriodStart: Instant,
    val currentPeriodEnd: Instant,
    val lastVerifiedAt: Instant?,
)
