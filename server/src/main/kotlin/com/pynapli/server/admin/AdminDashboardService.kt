package com.pynapli.server.admin

import com.pynapli.shared.network.dto.AdminDashboardDto

/**
 * Builds the unified admin dashboard from the aggregated queue backlog counts. Read-only and
 * aggregate — the route only requires the admin token (no reason/audit), matching the metrics routes.
 */
class AdminDashboardService(
    private val repository: AdminDashboardRepository,
) {
    suspend fun dashboard(): AdminDashboardDto {
        val counts = repository.counts()
        return AdminDashboardDto(
            reportsOpen = counts.reportsOpen,
            reportsUnderReview = counts.reportsUnderReview,
            appealsOpen = counts.appealsOpen,
            appealsUnderReview = counts.appealsUnderReview,
            appealsEscalated = counts.appealsEscalated,
            heldMessagesOpen = counts.heldMessagesOpen,
            riskWatch = counts.riskWatch,
            riskFriction = counts.riskFriction,
            riskQuarantine = counts.riskQuarantine,
            riskTerminate = counts.riskTerminate,
        )
    }
}
