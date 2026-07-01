package com.pynapli.server.admin

/**
 * Read-only backlog counts for the unified admin dashboard, aggregated across the operator queues
 * (`safety_reports`, `moderation_appeals`, `message_review_queue`, `account_risk_states`). Aggregate
 * counts only — no per-user data — so no reason or audit row is required (the metrics pattern).
 */
interface AdminDashboardRepository {
    suspend fun counts(): AdminDashboardCounts
}

data class AdminDashboardCounts(
    val reportsOpen: Int,
    val reportsUnderReview: Int,
    val appealsOpen: Int,
    val appealsUnderReview: Int,
    val appealsEscalated: Int,
    val heldMessagesOpen: Int,
    val riskWatch: Int,
    val riskFriction: Int,
    val riskQuarantine: Int,
    val riskTerminate: Int,
)
