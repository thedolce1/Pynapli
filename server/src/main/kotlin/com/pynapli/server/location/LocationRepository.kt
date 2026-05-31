package com.pynapli.server.location

import java.time.Instant
import java.util.UUID

interface LocationRepository {
    suspend fun upsertCurrentLocation(
        userId: UUID,
        update: LocationUpdate,
    ): CurrentLocationRecord
}

data class LocationUpdate(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Int?,
    val source: String,
    val geohash: String?,
    val city: String?,
    val region: String?,
    val country: String?,
)

data class CurrentLocationRecord(
    val updatedAt: Instant,
    val accuracyMeters: Int?,
    val source: String,
    val geohash: String?,
    val city: String?,
    val region: String?,
    val country: String?,
)
