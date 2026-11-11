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
    val country: String?,
    // ISO 3166-1 alpha-2 country of the destination, resolved by the server from the coordinates
    // (never trusted from the client's `country` label). Null over open water or outside the data.
    val countryCode: String? = null,
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

    /**
     * Travel mode can't be set to this destination at all (for example where the lifestyle is
     * dangerous for users). Surfaced as `TRAVEL_REGION_UNAVAILABLE`; such places are also left out
     * of destination search.
     */
    data object Blocked : TravelRegionRequirement
}

/**
 * Production default: no destination requires extra verification. Ops swaps
 * this for a real policy via DI when the safety review is in place.
 */
object EmptyTravelRegionPolicy : TravelRegionPolicy {
    override fun requirementFor(destination: TravelRegionRef): TravelRegionRequirement = TravelRegionRequirement.None
}

/**
 * Ops-configured policy, keyed on the destination's country code. Set with
 * `PYNAPLI_TRAVEL_REGION_POLICY`, a comma-separated list of `CC=requirement` where requirement is
 * `blocked` or `age_verified`, e.g. `AE=blocked,QA=age_verified`. Countries not listed need nothing
 * extra. A destination whose country can't be resolved needs nothing extra either.
 */
class ConfiguredTravelRegionPolicy(
    private val rules: Map<String, TravelRegionRequirement>,
) : TravelRegionPolicy {
    override fun requirementFor(destination: TravelRegionRef): TravelRegionRequirement = destination.countryCode?.uppercase()?.let { rules[it] } ?: TravelRegionRequirement.None

    companion object {
        /**
         * Parses the env value; blank means [EmptyTravelRegionPolicy]. A malformed entry fails
         * startup rather than silently allowing a destination ops meant to restrict.
         */
        fun parse(spec: String?): TravelRegionPolicy {
            val entries =
                spec
                    ?.split(',')
                    ?.map { it.trim() }
                    ?.filter { it.isNotEmpty() }
                    .orEmpty()
            if (entries.isEmpty()) return EmptyTravelRegionPolicy
            val rules =
                entries.associate { entry ->
                    val parts = entry.split('=').map { it.trim() }
                    require(parts.size == 2 && parts[0].length == 2 && parts[0].all { it.isLetter() }) {
                        "PYNAPLI_TRAVEL_REGION_POLICY entry '$entry' must look like CC=blocked or CC=age_verified"
                    }
                    val requirement =
                        when (parts[1].lowercase()) {
                            "blocked" -> TravelRegionRequirement.Blocked
                            "age_verified" -> TravelRegionRequirement.AgeVerified
                            else -> throw IllegalArgumentException(
                                "PYNAPLI_TRAVEL_REGION_POLICY entry '$entry': requirement must be blocked or age_verified",
                            )
                        }
                    parts[0].uppercase() to requirement
                }
            return ConfiguredTravelRegionPolicy(rules)
        }
    }
}
