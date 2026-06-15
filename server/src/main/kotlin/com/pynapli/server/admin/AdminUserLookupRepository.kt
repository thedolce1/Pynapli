package com.pynapli.server.admin

import java.time.Instant
import java.util.UUID

/**
 * Read-only moderation facts for the admin user-lookup view, plus the audit write that records the
 * lookup itself. The profile summary, subscription entitlement, and partner-link state are composed
 * from their owning services; this repository covers only the cross-cutting moderation signals.
 */
interface AdminUserLookupRepository {
    suspend fun moderationFacts(userId: UUID): AdminUserModerationFacts

    suspend fun recordLookupAudit(
        actorId: String,
        userId: UUID,
        reason: String,
    )
}

data class AdminUserModerationFacts(
    val riskTier: String,
    val riskScore: Int,
    val lastReviewedAt: Instant?,
    val recentRiskReasons: List<AdminUserRiskReason>,
    val reportsAgainstCount: Int,
    val reportsByCount: Int,
    val blocksAgainstCount: Int,
    val blocksByCount: Int,
    val activeSessionCount: Int,
    val activeDeviceCount: Int,
    val recentModerationActions: List<AdminUserModerationAction>,
)

data class AdminUserRiskReason(
    val source: String,
    val reason: String,
    val delta: Int,
    val occurredAt: Instant,
)

data class AdminUserModerationAction(
    val action: String,
    val targetType: String,
    val reason: String?,
    val actorId: String,
    val occurredAt: Instant,
)
