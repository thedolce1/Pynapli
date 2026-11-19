package com.pynapli.server.location

import com.pynapli.server.safety.LocationRiskSignalPublisher
import com.pynapli.shared.location.SnappedCoordinates
import com.pynapli.shared.location.snapToLocationCell
import com.pynapli.shared.network.dto.CurrentLocationDto
import com.pynapli.shared.network.dto.UpdateCurrentLocationRequestDto
import java.time.Clock
import java.time.Duration
import java.util.UUID
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class LocationService(
    private val repository: LocationRepository,
    private val locationRiskSignals: LocationRiskSignalPublisher? = null,
    private val clock: Clock = Clock.systemUTC(),
) {
    suspend fun updateCurrentLocation(
        userId: UUID,
        request: UpdateCurrentLocationRequestDto,
    ): CurrentLocationDto {
        validate(request)
        val source = request.source.trim().lowercase()
        // Location privacy: the precise fix (if an older client sent one) goes no further than this
        // line. Everything below, the spoof check included, sees only the cell centre, and the
        // repository snaps again in SQL. The accuracy figure is validated (old clients send it) and
        // then dropped: it described a precise fix we do not keep.
        val cell = snapToLocationCell(request.latitude, request.longitude)
        publishSpoofSignalIfImpossible(userId, cell)
        val update =
            LocationUpdate(
                latitude = cell.latitude,
                longitude = cell.longitude,
                source = source,
                city = request.city.cleanOptional(80),
                region = request.region.cleanOptional(80),
                country = request.country.cleanOptional(80),
            )

        return repository.upsertCurrentLocation(userId, update).toDto()
    }

    /**
     * Location-spoofing signal (TRUST_AND_SAFETY_SPEC): an update that implies
     * physically impossible travel versus the previous stored fix feeds the risk
     * model. Signal only — the update itself always proceeds, and any failure in
     * the risk path must never break location updates. The client-declared `source`
     * is NOT trusted to skip the check (L-13: declaring `manual_city` or `travel` used
     * to bypass it); the only exemption is a Travel mode session the server itself
     * holds as active. The distance floor keeps GPS jitter and tiny intervals from
     * ever flagging.
     *
     * Both points are cell centres (the stored point always is, and [cell] is the snapped request),
     * so the distance is off by at most a cell diagonal (~1.6 km) against the raw fixes. That cannot
     * matter: a flag needs at least [IMPOSSIBLE_TRAVEL_MIN_DISTANCE_KM] at
     * [IMPOSSIBLE_TRAVEL_MIN_SPEED_KMH] or more, and two fixes in one cell compare as 0 km.
     */
    private suspend fun publishSpoofSignalIfImpossible(
        userId: UUID,
        cell: SnappedCoordinates,
    ) {
        val publisher = locationRiskSignals ?: return
        runCatching {
            val previous = repository.getCurrentCoordinates(userId) ?: return
            if (previous.travelSessionActive) return

            val distanceKm = haversineKm(previous.latitude, previous.longitude, cell.latitude, cell.longitude)
            if (distanceKm < IMPOSSIBLE_TRAVEL_MIN_DISTANCE_KM) return

            val elapsed = Duration.between(previous.updatedAt, clock.instant())
            val elapsedSeconds = elapsed.seconds.coerceAtLeast(1)
            val speedKmh = distanceKm / (elapsedSeconds / 3600.0)
            if (speedKmh < IMPOSSIBLE_TRAVEL_MIN_SPEED_KMH) return

            publisher.recordImpossibleTravel(
                userId = userId,
                distanceKm = distanceKm,
                elapsedSeconds = elapsedSeconds,
                speedKmh = speedKmh,
            )
        }
    }

    private fun validate(request: UpdateCurrentLocationRequestDto) {
        if (request.latitude !in -90.0..90.0 || !request.latitude.isFinite()) {
            throw LocationException("INVALID_LATITUDE")
        }
        if (request.longitude !in -180.0..180.0 || !request.longitude.isFinite()) {
            throw LocationException("INVALID_LONGITUDE")
        }
        val accuracy = request.accuracyMeters
        if (accuracy != null && accuracy !in 0..100_000) {
            throw LocationException("INVALID_ACCURACY")
        }
        if (request.source.trim().lowercase() !in SOURCES) {
            throw LocationException("INVALID_LOCATION_SOURCE")
        }
    }

    private fun CurrentLocationRecord.toDto(): CurrentLocationDto =
        CurrentLocationDto(
            updatedAt = updatedAt.toString(),
            // Never stored any more; the field stays on the wire for older clients.
            accuracyMeters = null,
            source = source,
            city = city,
            region = region,
            country = country,
        )

    private fun String?.cleanOptional(maxLength: Int): String? {
        val value = this?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (value.length > maxLength) throw LocationException("INVALID_LOCATION_LABEL")
        return value
    }

    companion object {
        private val SOURCES = setOf("gps", "network", "travel", "manual_city")

        // Faster than any commercial flight over a distance no GPS glitch covers.
        const val IMPOSSIBLE_TRAVEL_MIN_DISTANCE_KM = 100.0
        const val IMPOSSIBLE_TRAVEL_MIN_SPEED_KMH = 1_000.0

        private const val EARTH_RADIUS_KM = 6_371.0

        internal fun haversineKm(
            lat1: Double,
            lon1: Double,
            lat2: Double,
            lon2: Double,
        ): Double {
            val dLat = Math.toRadians(lat2 - lat1)
            val dLon = Math.toRadians(lon2 - lon1)
            val a =
                sin(dLat / 2) * sin(dLat / 2) +
                    cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                    sin(dLon / 2) * sin(dLon / 2)
            return EARTH_RADIUS_KM * 2 * atan2(sqrt(a), sqrt(1 - a))
        }
    }
}

class LocationException(
    val code: String,
) : RuntimeException(code)
