package com.pynapli.server.location

import com.pynapli.server.grid.GridViewerTrustRepository
import com.pynapli.server.subscriptions.EntitlementPolicy
import com.pynapli.server.subscriptions.SubscriptionService
import com.pynapli.shared.network.dto.SetTravelLocationRequestDto
import com.pynapli.shared.network.dto.SubscriptionTier
import com.pynapli.shared.network.dto.TravelLocationDto
import java.time.Clock
import java.time.Duration
import java.util.UUID

/**
 * Passport / travel mode (SWIPE_IMPLEMENTATION_SPEC L339-354).
 *
 * Gating: Free has no travel mode; Social gets a one-activation-per-30-days preview; Select is
 * unlimited (see [requireTravelTier]). Every tier is capped at [DAILY_ACTIVATION_LIMIT] switches a
 * day, and accounts at the friction risk tier or above can't turn it on (TRUST_AND_SAFETY_SPEC
 * "Location Spoofing": temporarily disable Travel mode).
 *
 * One active location per user is enforced by the partial unique index on
 * `travel_locations`. Setting a new location atomically clears the prior active
 * row (status='cleared', active_until = now), preserving the audit trail.
 */
class TravelLocationService(
    private val repository: TravelLocationRepository,
    private val subscriptionService: SubscriptionService? = null,
    private val regionPolicy: TravelRegionPolicy = EmptyTravelRegionPolicy,
    private val viewerTrustRepository: GridViewerTrustRepository? = null,
    private val clock: Clock = Clock.systemUTC(),
    // Resolves the destination's real country for the region policy.
    private val placeRepository: TravelPlaceRepository? = null,
    private val riskPolicyService: com.pynapli.server.safety.RiskPolicyService? = null,
    private val riskSignals: com.pynapli.server.safety.LocationRiskSignalPublisher? = null,
) {
    /**
     * Returns the viewer's current travel location, or null if none active.
     * Free-tier callers get null rather than an exception so the client can
     * render the entry point and a paywall hint without a network round-trip.
     */
    suspend fun getCurrent(viewerUserId: UUID): TravelLocationDto? {
        // Read is open to all tiers — paid-tier gating only matters for SET.
        // Free users always read null because they can't have an active row.
        return repository.findActive(viewerUserId)?.toDto()
    }

    suspend fun set(
        viewerUserId: UUID,
        request: SetTravelLocationRequestDto,
    ): TravelLocationDto {
        if (riskPolicyService?.isTravelRestricted(viewerUserId) == true) {
            throw TravelLocationException("RISK_RESTRICTED")
        }
        requireTravelTier(viewerUserId, applyRateLimit = true)
        val switchesToday = requireDailySwitchAllowance(viewerUserId)
        val place = validatedPlace(viewerUserId, request)
        val record =
            repository.setActive(
                userId = viewerUserId,
                request = place,
                now = clock.instant(),
            )
        if (switchesToday + 1 == DAILY_ACTIVATION_LIMIT) {
            // Reached the day's cap: a risk signal, recorded once (refused retries add no rows).
            runCatching { riskSignals?.recordRepeatedTravelSwitching(viewerUserId, DAILY_ACTIVATION_LIMIT) }
        }
        return record.toDto()
    }

    /**
     * Checks a destination the way every Travel switch must (name lengths, coordinates, and the
     * region policy applied to where the coordinates really are) and returns it ready to store.
     * Used by [set] and by planned trips, both when one is planned and when it starts.
     */
    internal suspend fun validatedPlace(
        viewerUserId: UUID,
        request: SetTravelLocationRequestDto,
    ): TravelLocationUpsert {
        val city = request.city.trim()
        if (city.isEmpty() || city.length > MAX_CITY_LENGTH) {
            throw TravelLocationException("INVALID_TRAVEL_CITY")
        }
        val region =
            request.region?.trim()?.takeIf { it.isNotEmpty() && it.length <= MAX_REGION_LENGTH }
                ?: run { if (request.region.isNullOrEmpty()) null else throw TravelLocationException("INVALID_TRAVEL_REGION") }
        val country =
            request.country?.trim()?.takeIf { it.isNotEmpty() && it.length <= MAX_COUNTRY_LENGTH }
                ?: run { if (request.country.isNullOrEmpty()) null else throw TravelLocationException("INVALID_TRAVEL_COUNTRY") }
        if (request.latitude !in MIN_LATITUDE..MAX_LATITUDE) {
            throw TravelLocationException("INVALID_TRAVEL_COORDINATES")
        }
        if (request.longitude !in MIN_LONGITUDE..MAX_LONGITUDE) {
            throw TravelLocationException("INVALID_TRAVEL_COORDINATES")
        }
        // SWIPE_IMPLEMENTATION_SPEC L349: some regions require additional
        // verification before travel-mode can engage. Reuses the same
        // age_verified_at signal the lifestyle filter gate uses
        // (SavedGridFilterService) so the client routes through the existing
        // verification flow on AGE_VERIFICATION_REQUIRED.
        // The policy is applied to where the coordinates really are, not to the client's label.
        val countryCode =
            placeRepository?.nearestCountryCode(request.latitude, request.longitude)
                ?: country?.takeIf { it.length == 2 && it.all(Char::isLetter) }?.uppercase()
        when (regionPolicy.requirementFor(TravelRegionRef(city, region, country, countryCode))) {
            TravelRegionRequirement.None -> Unit
            TravelRegionRequirement.Blocked -> throw TravelLocationException("TRAVEL_REGION_UNAVAILABLE")
            TravelRegionRequirement.AgeVerified -> {
                val verified = viewerTrustRepository?.isAgeVerified(viewerUserId) ?: true
                if (!verified) throw TravelLocationException("AGE_VERIFICATION_REQUIRED")
            }
        }
        return TravelLocationUpsert(
            latitude = request.latitude,
            longitude = request.longitude,
            city = city,
            region = region,
            country = country,
        )
    }

    /**
     * Starts a planned trip: the same risk, plan and region rules as a manual switch, but no daily
     * switch allowance (the trip was planned ahead, not a city hop). Null when the user may no longer
     * use Travel mode (no longer on Select, or restricted); the caller cancels the trip then.
     */
    internal suspend fun activateForTrip(
        userId: UUID,
        place: SetTravelLocationRequestDto,
    ): TravelLocationRecord? {
        if (riskPolicyService?.isTravelRestricted(userId) == true) return null
        if (!canPlanTrips(userId)) return null
        val upsert = runCatching { validatedPlace(userId, place) }.getOrNull() ?: return null
        return repository.setActive(userId, upsert, clock.instant())
    }

    /** Ends a trip's Travel mode, but only if that trip's row is still the active one. */
    internal suspend fun clearIfStillActive(
        userId: UUID,
        travelLocationId: UUID,
    ): Boolean {
        val active = repository.findActive(userId) ?: return false
        if (active.id != travelLocationId) return false
        return repository.clearActive(userId, clock.instant())
    }

    /** Planned trips are a Select feature (Social keeps its one-switch preview). */
    internal suspend fun canPlanTrips(userId: UUID): Boolean {
        val policy = subscriptionService?.getPolicy(userId) ?: EntitlementPolicy.unrestricted()
        return policy.tier == SubscriptionTier.SELECT
    }

    /**
     * Returns true if an active row was cleared. False means there was nothing
     * active — not an error; the client just transitions back to local-only
     * state.
     */
    suspend fun clear(viewerUserId: UUID): Boolean {
        requireTravelTier(viewerUserId, applyRateLimit = false)
        return repository.clearActive(viewerUserId, clock.instant())
    }

    /**
     * Per-call entitlement gate for set/clear (MONETIZATION_SPEC L152, L186, L222):
     * - **Free**: no travel mode → `ENTITLEMENT_REQUIRED`
     * - **Social**: "basic travel preview" — full feature but rate-limited to
     *   [SOCIAL_PREVIEW_ACTIVATIONS_PER_WINDOW] activations per
     *   [SOCIAL_PREVIEW_WINDOW]. Second activation inside the window returns
     *   `TRAVEL_PREVIEW_RATE_LIMITED` so the client routes to the Select paywall
     *   with a try-again-in-Nd hint.
     * - **Select**: unlimited (no rate-limit check).
     *
     * `clear()` only applies the tier gate, not the rate limit — Social users
     * who are mid-preview should always be able to exit travel mode.
     */
    private suspend fun requireTravelTier(
        viewerUserId: UUID,
        applyRateLimit: Boolean,
    ) {
        val policy = subscriptionService?.getPolicy(viewerUserId) ?: EntitlementPolicy.unrestricted()
        when (policy.tier) {
            SubscriptionTier.SELECT -> Unit
            SubscriptionTier.SOCIAL -> {
                if (!applyRateLimit) return
                val recent =
                    repository.countActivationsSince(
                        userId = viewerUserId,
                        since = clock.instant().minus(SOCIAL_PREVIEW_WINDOW),
                    )
                if (recent >= SOCIAL_PREVIEW_ACTIVATIONS_PER_WINDOW) {
                    throw TravelLocationException("TRAVEL_PREVIEW_RATE_LIMITED")
                }
            }
            else -> throw TravelLocationException("ENTITLEMENT_REQUIRED")
        }
    }

    /**
     * TRUST_AND_SAFETY_SPEC "Repeated travel-mode abuse": hopping cities all day is how location
     * spoofers and spammers use passport modes. Every tier, Select included, gets
     * [DAILY_ACTIVATION_LIMIT] switches per rolling day; reaching the limit is also a risk signal.
     */
    private suspend fun requireDailySwitchAllowance(viewerUserId: UUID): Int {
        val today = repository.countActivationsSince(viewerUserId, clock.instant().minus(Duration.ofHours(24)))
        if (today >= DAILY_ACTIVATION_LIMIT) throw TravelLocationException("TRAVEL_SWITCH_LIMITED")
        return today
    }

    private fun TravelLocationRecord.toDto(): TravelLocationDto =
        TravelLocationDto(
            city = city,
            region = region,
            country = country,
            latitude = latitude,
            longitude = longitude,
            activeFrom = activeFrom.toString(),
            activeUntil = activeUntil?.toString(),
            status = status,
        )

    companion object {
        /**
         * Social-tier rate limit window (MONETIZATION_SPEC L186 "basic travel
         * preview"). Picked to mirror typical free-trial cadences: long enough
         * that the preview feels like a real try, short enough that frequent
         * travellers feel the Select upgrade tug.
         */
        val SOCIAL_PREVIEW_WINDOW: Duration = Duration.ofDays(30)
        const val SOCIAL_PREVIEW_ACTIVATIONS_PER_WINDOW: Int = 1

        /** Travel destinations any account can switch to in a rolling 24 hours. */
        const val DAILY_ACTIVATION_LIMIT: Int = 6
        const val MAX_CITY_LENGTH = 120
        const val MAX_REGION_LENGTH = 120
        const val MAX_COUNTRY_LENGTH = 120
        const val MIN_LATITUDE = -90.0
        const val MAX_LATITUDE = 90.0
        const val MIN_LONGITUDE = -180.0
        const val MAX_LONGITUDE = 180.0
    }
}

class TravelLocationException(
    val code: String,
) : RuntimeException(code)
