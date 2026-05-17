package com.pynapli.server.location

import com.pynapli.server.grid.GridViewerTrustRepository
import com.pynapli.server.subscriptions.EntitlementPolicy
import com.pynapli.server.subscriptions.SubscriptionService
import com.pynapli.shared.network.dto.SetTravelLocationRequestDto
import com.pynapli.shared.network.dto.SubscriptionTier
import com.pynapli.shared.network.dto.TravelLocationDto
import java.time.Clock
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
        requireSelectTier(viewerUserId)
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
        requireSelectTier(viewerUserId)
        return repository.clearActive(viewerUserId, clock.instant())
    }

    private suspend fun requireSelectTier(viewerUserId: UUID) {
        val policy = subscriptionService?.getPolicy(viewerUserId) ?: EntitlementPolicy.unrestricted()
        if (policy.tier != SubscriptionTier.SELECT) {
            throw TravelLocationException("ENTITLEMENT_REQUIRED")
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
