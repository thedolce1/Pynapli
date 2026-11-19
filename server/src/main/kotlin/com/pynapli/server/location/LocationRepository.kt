package com.pynapli.server.location

import java.time.Instant
import java.util.UUID

interface LocationRepository {
    /**
     * Stores the user's current location. Implementations must store only the point's CELL
     * (`pynapli_location_cell`, a 0.01-degree grid) and no accuracy figure: the service never
     * receives or keeps a member's precise location, whatever [update] carries.
     */
    suspend fun upsertCurrentLocation(
        userId: UUID,
        update: LocationUpdate,
    ): CurrentLocationRecord

    /**
     * Server-internal read of the stored point (a cell centre) for spoof-signal comparison
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

/**
 * A location write. [latitude]/[longitude] are already snapped by [LocationService]; the JDBC
 * repository snaps again in SQL so no write can store a finer point. There is deliberately no
 * accuracy or geohash field: the stored accuracy is always NULL and the geohash is derived from the
 * stored cell in SQL.
 */
data class LocationUpdate(
    val latitude: Double,
    val longitude: Double,
    val source: String,
    val city: String?,
    val region: String?,
    val country: String?,
)

data class CurrentLocationRecord(
    val updatedAt: Instant,
    val source: String,
    val city: String?,
    val region: String?,
    val country: String?,
)
