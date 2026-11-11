package com.pynapli.server.safety

import java.util.UUID

class RiskPolicyService(
    private val loadRiskState: suspend (UUID) -> AccountRiskStateRecord?,
) {
    constructor(riskService: RiskService) : this(loadRiskState = { userId -> riskService.getState(userId) })

    suspend fun isSocialActionRestricted(userId: UUID): Boolean = loadRiskState(userId)?.tier in SOCIAL_ACTION_RESTRICTED_TIERS

    suspend fun shouldHideFromDiscovery(userId: UUID): Boolean = loadRiskState(userId)?.tier in DISCOVERY_SUPPRESSED_TIERS

    /** TRUST_AND_SAFETY_SPEC "Location Spoofing": temporarily disable Travel mode from friction up. */
    suspend fun isTravelRestricted(userId: UUID): Boolean = loadRiskState(userId)?.tier in TRAVEL_RESTRICTED_TIERS

    suspend fun shouldSuppressPassiveActivity(userId: UUID): Boolean = shouldHideFromDiscovery(userId)

    companion object {
        val SOCIAL_ACTION_RESTRICTED_TIERS = setOf("quarantine", "terminate")
        val DISCOVERY_SUPPRESSED_TIERS = SOCIAL_ACTION_RESTRICTED_TIERS
        val TRAVEL_RESTRICTED_TIERS = setOf("friction", "quarantine", "terminate")
    }
}
