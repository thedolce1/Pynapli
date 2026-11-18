package com.pynapli.server.location

import java.time.Instant
import java.util.UUID

interface LocationRepository {
    suspend fun upsertCurrentLocation(
        userId: UUID,
        update: LocationUpdate,
    ): CurrentLocationRecord

    /**
     * Server-internal read of the stored fix for spoof-signal comparison
     * (TRUST_AND_SAFETY_SPEC). Never exposed through the API: current location
     * is write-only from the client's perspective.
     */
    suspend fun getCurrentCoordinates(userId: UUID): CurrentCoordinatesRecord?
}

data class CurrentCoordinatesRecord(
    val latitude: Double,
    val longitude: Double,
    val source: String,
    val updatedAt: Instant,
    /** The server's own record of an active Travel mode session (never the client's declared source). */
    val travelSessionActive: Boolean = false,
)

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
