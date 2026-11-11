package com.pynapli.server.safety

import com.pynapli.server.location.TravelLocationRepository
import java.time.Clock
import java.util.UUID

/**
 * TRUST_AND_SAFETY_SPEC "Location Spoofing": temporarily disable Travel mode. Blocking new
 * activations isn't enough on its own: an account flagged mid-trip would keep appearing as
 * "visiting" another city until it turned Travel mode off itself. Whenever an account lands at a
 * travel-restricted tier (from a risk signal or an admin action), its active trip is ended.
 * Best effort: a failure here must never undo the risk action that triggered it.
 */
class TravelRiskEnforcement(
    private val travelLocations: TravelLocationRepository?,
    private val clock: Clock = Clock.systemUTC(),
) {
    suspend fun onTierChanged(
        userId: UUID,
        tier: String,
    ) {
        if (tier !in RiskPolicyService.TRAVEL_RESTRICTED_TIERS) return
        val repository = travelLocations ?: return
        runCatching { repository.clearActive(userId, clock.instant()) }
    }
}
