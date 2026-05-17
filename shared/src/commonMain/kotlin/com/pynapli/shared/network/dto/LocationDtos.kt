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
    val country: String? = null
)

@Serializable
data class CurrentLocationDto(
    val updatedAt: String,
    val accuracyMeters: Int?,
    val source: String,
    val geohash: String?,
    val city: String?,
    val region: String?,
    val country: String?
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
    val country: String? = null
)

@Serializable
data class TravelLocationDto(
    val city: String,
    val region: String?,
    val country: String?,
    val latitude: Double,
    val longitude: Double,
    val activeFrom: String,
    val activeUntil: String? = null,
    val status: String
)

object TravelLocationStatus {
    const val ACTIVE = "active"
    const val CLEARED = "cleared"
    const val EXPIRED = "expired"
}

