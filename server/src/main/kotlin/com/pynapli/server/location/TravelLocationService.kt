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
 * Gating in this MVP slice: Select tier only. Social-tier "basic travel preview"
 * (MONETIZATION_SPEC L186) is deferred to a later slice — for now, Free and
 * Social both get ENTITLEMENT_REQUIRED. The hardcoded gate lives here so the
 * Social-preview slice only has to widen this single check.
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
    private val clock: Clock = Clock.systemUTC()
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
        request: SetTravelLocationRequestDto
    ): TravelLocationDto {
        requireTravelTier(viewerUserId, applyRateLimit = true)
        val city = request.city.trim()
        if (city.isEmpty() || city.length > MAX_CITY_LENGTH) {
            throw TravelLocationException("INVALID_TRAVEL_CITY")
        }
        val region = request.region?.trim()?.takeIf { it.isNotEmpty() && it.length <= MAX_REGION_LENGTH }
            ?: run { if (request.region.isNullOrEmpty()) null else throw TravelLocationException("INVALID_TRAVEL_REGION") }
        val country = request.country?.trim()?.takeIf { it.isNotEmpty() && it.length <= MAX_COUNTRY_LENGTH }
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
        when (regionPolicy.requirementFor(TravelRegionRef(city, region, country))) {
            TravelRegionRequirement.None -> Unit
            TravelRegionRequirement.AgeVerified -> {
                val verified = viewerTrustRepository?.isAgeVerified(viewerUserId) ?: true
                if (!verified) throw TravelLocationException("AGE_VERIFICATION_REQUIRED")
            }
        }
        val record = repository.setActive(
            userId = viewerUserId,
            request = TravelLocationUpsert(
                latitude = request.latitude,
                longitude = request.longitude,
                city = city,
                region = region,
                country = country
            ),
            now = clock.instant()
        )
        return record.toDto()
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
    private suspend fun requireTravelTier(viewerUserId: UUID, applyRateLimit: Boolean) {
        val policy = subscriptionService?.getPolicy(viewerUserId) ?: EntitlementPolicy.unrestricted()
        when (policy.tier) {
            SubscriptionTier.SELECT -> Unit
            SubscriptionTier.SOCIAL -> {
                if (!applyRateLimit) return
                val recent = repository.countActivationsSince(
                    userId = viewerUserId,
                    since = clock.instant().minus(SOCIAL_PREVIEW_WINDOW)
                )
                if (recent >= SOCIAL_PREVIEW_ACTIVATIONS_PER_WINDOW) {
                    throw TravelLocationException("TRAVEL_PREVIEW_RATE_LIMITED")
                }
            }
            else -> throw TravelLocationException("ENTITLEMENT_REQUIRED")
        }
    }

    private fun TravelLocationRecord.toDto(): TravelLocationDto = TravelLocationDto(
        city = city,
        region = region,
        country = country,
        latitude = latitude,
        longitude = longitude,
        activeFrom = activeFrom.toString(),
        activeUntil = activeUntil?.toString(),
        status = status
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
        const val MAX_CITY_LENGTH = 120
        const val MAX_REGION_LENGTH = 120
        const val MAX_COUNTRY_LENGTH = 120
        const val MIN_LATITUDE = -90.0
        const val MAX_LATITUDE = 90.0
        const val MIN_LONGITUDE = -180.0
        const val MAX_LONGITUDE = 180.0
    }
}

class TravelLocationException(val code: String) : RuntimeException(code)
