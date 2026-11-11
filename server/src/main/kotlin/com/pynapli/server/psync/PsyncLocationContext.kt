package com.pynapli.server.psync

import com.pynapli.server.location.TravelLocationRecord
import com.pynapli.server.location.TravelLocationRepository
import com.pynapli.server.subscriptions.EntitlementPolicy
import com.pynapli.shared.network.dto.SubscriptionTier
import java.util.UUID

/** Where a Psync deck is dealt: the initiator's active travel destination, or null for home. */
internal data class PsyncLocation(
    val travelLocationId: UUID?,
    val label: String?,
)

internal suspend fun TravelLocationRepository?.psyncLocationFor(initiatorUserId: UUID): PsyncLocation {
    val travel = this?.findActive(initiatorUserId) ?: return PsyncLocation(null, null)
    return PsyncLocation(travel.id, travel.label())
}

/** PSYNC_SPEC Monetization: "Travel-mode Psync" is a Select feature. */
internal fun requirePsyncTravelAllowed(
    location: PsyncLocation,
    policy: EntitlementPolicy,
) {
    if (location.travelLocationId != null && policy.tier != SubscriptionTier.SELECT) {
        throw PsyncException("PSYNC_TRAVEL_NOT_ALLOWED")
    }
}

private fun TravelLocationRecord.label(): String = listOfNotNull(city, region?.takeIf { it.isNotBlank() }).joinToString(", ")
