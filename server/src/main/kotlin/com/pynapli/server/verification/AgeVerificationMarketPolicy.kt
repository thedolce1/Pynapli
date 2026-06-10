package com.pynapli.server.verification

/**
 * Decides whether a market legally requires enhanced (third-party provider) age
 * verification instead of internal manual review
 * (MODERATION_AND_AGE_VERIFICATION_SPEC "Market flags controlled server-side").
 *
 * The production default flags nothing: which markets need enhanced assurance is
 * an ops/legal decision wired via DI after review, mirroring [TravelRegionPolicy].
 * The service only honors a flag while a provider is configured — with no provider
 * it falls back to manual review, so a policy misconfiguration can never lock
 * users out of verification entirely.
 */
fun interface AgeVerificationMarketPolicy {
    fun enhancedRequired(market: String): Boolean
}

/** Default policy: no market requires enhanced verification. */
object BaselineAgeVerificationMarketPolicy : AgeVerificationMarketPolicy {
    override fun enhancedRequired(market: String): Boolean = false
}
