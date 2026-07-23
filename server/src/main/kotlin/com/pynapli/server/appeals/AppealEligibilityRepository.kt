package com.pynapli.server.appeals

import java.time.Instant

interface AppealEligibilityRepository {
    /**
     * Current risk state for the user, or null when no risk row exists. `actionedAt` is the
     * `account_risk_states.updated_at` — i.e. when the current tier was last set — which the
     * service uses as the basis for the 30-day appeal window.
     */
    suspend fun riskStateForUser(userId: java.util.UUID): AppealRiskState?
}

data class AppealRiskState(
    val tier: String,
    val actionedAt: Instant,
)
