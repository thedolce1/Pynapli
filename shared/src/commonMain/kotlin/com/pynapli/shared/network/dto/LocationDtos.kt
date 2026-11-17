package com.pynapli.shared.network.dto

import kotlinx.serialization.Serializable

@Serializable
data class UpdateCurrentLocationRequestDto(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Int? = null,
    val source: String,
    val city: String? = null,
    val region: String? = null,
    val country: String? = null,
)

@Serializable
data class CurrentLocationDto(
    val updatedAt: String,
    val accuracyMeters: Int? = null,
    val source: String,
    val geohash: String? = null,
    val city: String? = null,
    val region: String? = null,
    val country: String? = null,
)

// Passport/travel mode (DATA_MODEL_SPEC L286-301, SWIPE_IMPLEMENTATION_SPEC L339-354).
// One active travel location per user; setting a new one supersedes the prior
// active row. Latitude/longitude are mandatory because all discovery queries are
// PostGIS-spatial — the city/region/country labels are display metadata only.
@Serializable
data class SetTravelLocationRequestDto(
    val latitude: Double,
    val longitude: Double,
    val city: String,
    val region: String? = null,
    val country: String? = null,
)

@Serializable
data class TravelLocationDto(
    val city: String,
    val region: String? = null,
    val country: String? = null,
    val latitude: Double,
    val longitude: Double,
    val activeFrom: String,
    val activeUntil: String? = null,
    val status: String,
)

/** Plan Travel mode ahead: it switches on at local midnight on [startsOn] and off after [endsOn]. */
@Serializable
data class PlanTravelTripRequestDto(
    val latitude: Double,
    val longitude: Double,
    val city: String,
    val region: String? = null,
    val country: String? = null,
    val startsOn: String, // yyyy-MM-dd
    val endsOn: String, // yyyy-MM-dd, inclusive
    val timezone: String, // the planner's IANA zone, e.g. "America/Toronto"
    // Opt-in: people near the destination may see "Visiting 14-17 Nov" before the trip starts.
    val announce: Boolean = false,
)

@Serializable
data class TravelTripDto(
    val tripId: String,
    val city: String,
    val region: String? = null,
    val country: String? = null,
    val startsOn: String,
    val endsOn: String,
    val status: String, // planned | active
    val announce: Boolean = false,
)

@Serializable
data class TravelTripsDto(
    val trips: List<TravelTripDto>,
)

object TravelLocationStatus {
    const val ACTIVE = "active"
    const val CLEARED = "cleared"
    const val EXPIRED = "expired"
}

// Travel-mode type-ahead search: free-text city or ZIP/postal query resolved
// server-side against the packaged place directory. The picked item's fields
// feed SetTravelLocationRequestDto verbatim.
@Serializable
data class TravelPlaceDto(
    val displayName: String,
    val city: String,
    val region: String? = null,
    val country: String? = null,
    val latitude: Double,
    val longitude: Double,
)

@Serializable
data class TravelPlaceSearchResponseDto(
    val items: List<TravelPlaceDto>,
)
