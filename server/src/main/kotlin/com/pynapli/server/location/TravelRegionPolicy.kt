package com.pynapli.server.location

/**
 * Per SWIPE_IMPLEMENTATION_SPEC L349 ("Some regions may require additional
 * verification before using travel mode"): when a viewer attempts to set their
 * passport to certain destinations, the server requires them to have completed
 * additional verifications first. The mechanism lives in
 * [TravelLocationService]; this policy decides *which* destinations trigger it.
 *
 * The production default ([EmptyTravelRegionPolicy]) requires no extra
 * verification for any destination. Ops configures the real allow/deny list
 * out-of-code so that regional safety calls aren't baked into source —
 * jurisdictions where the lifestyle app surface is risky for users (or for
 * Pynapli) change over time and shouldn't require a code deploy to amend.
 *
 * To enable the gate, inject a policy that returns
 * [TravelRegionRequirement.AgeVerified] for the relevant ISO country codes.
 * Country/region matching is case-insensitive and uses the values supplied
 * with `SetTravelLocationRequestDto`.
 */
fun interface TravelRegionPolicy {
    /**
     * Returns the verification requirement for travelling to the given
     * destination. Implementations should be cheap (constant-time lookup);
     * the policy is consulted on every travel-mode set call.
     */
    fun requirementFor(destination: TravelRegionRef): TravelRegionRequirement
}

/**
 * Minimal context a policy needs to make a decision: the destination's
 * city/region/country labels as supplied by the client. The server has already
 * validated/trimmed these values before the policy sees them.
 */
data class TravelRegionRef(
    val city: String,
    val region: String?,
    val country: String?
)

sealed interface TravelRegionRequirement {
    /** Default — no extra verification required. */
    data object None : TravelRegionRequirement

    /**
     * Viewer must have completed age verification (profiles.age_verified_at IS
     * NOT NULL) before this destination is permitted. Surfaced to the client
     * as the existing `AGE_VERIFICATION_REQUIRED` error so the same UI flow
     * the lifestyle filter gate uses can route the user into verification.
     */
    data object AgeVerified : TravelRegionRequirement
}

/**
 * Production default: no destination requires extra verification. Ops swaps
 * this for a real policy via DI when the safety review is in place.
 */
object EmptyTravelRegionPolicy : TravelRegionPolicy {
    override fun requirementFor(destination: TravelRegionRef): TravelRegionRequirement =
        TravelRegionRequirement.None
}
