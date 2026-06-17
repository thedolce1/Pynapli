package com.pynapli.server.admin

import java.time.Instant
import java.util.UUID

/**
 * Read-only partner-link facts for the admin review view (all from `user_profile_links`,
 * `discovery_settings`, and `safety_reports`), plus the audit write that records the review.
 */
interface AdminPartnerLinkReviewRepository {
    /** Returns null when the user does not exist. */
    suspend fun review(userId: UUID): AdminPartnerLinkReviewFacts?

    suspend fun recordReviewAudit(
        actorId: String,
        userId: UUID,
        reason: String,
    )
}

data class AdminPartnerLinkReviewFacts(
    val partnerVisibility: String,
    val links: List<AdminPartnerLinkFact>,
    val reportsAgainstUserCount: Int,
    val reportsAgainstCurrentPartnerCount: Int,
)

data class AdminPartnerLinkFact(
    val linkId: UUID,
    val otherUserId: UUID,
    val status: String,
    val direction: String,
    val createdAt: Instant,
    val acceptedAt: Instant?,
    val unlinkedAt: Instant?,
)
