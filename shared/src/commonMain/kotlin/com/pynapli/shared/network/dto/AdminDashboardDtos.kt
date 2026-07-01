package com.pynapli.shared.network.dto

import kotlinx.serialization.Serializable

/**
 * Unified admin/moderation console dashboard (ADMIN_MODERATION_CONSOLE_SPEC "unified moderation
 * console"). Counts-only backlog snapshot across the four operator queues — reports, appeals, held
 * messages, and risk — so an operator sees "what needs attention" at a glance. Aggregate counts only;
 * no per-user data, so it follows the metrics pattern (admin-token gated, no reason/audit).
 */
@Serializable
data class AdminDashboardDto(
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
