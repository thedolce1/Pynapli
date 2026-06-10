package com.pynapli.server.location

import com.pynapli.server.safety.LocationRiskSignalPublisher
import com.pynapli.shared.network.dto.CurrentLocationDto
import com.pynapli.shared.network.dto.UpdateCurrentLocationRequestDto
import java.time.Clock
import java.time.Duration
import java.util.UUID
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
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
        publishSpoofSignalIfImpossible(userId, request, source)
        val update =
            LocationUpdate(
                latitude = request.latitude,
                longitude = request.longitude,
                accuracyMeters = request.accuracyMeters,
                source = source,
                geohash = coarseLocationBucket(request.latitude, request.longitude),
                city = request.city.cleanOptional(80),
                region = request.region.cleanOptional(80),
                country = request.country.cleanOptional(80),
            )

        return repository.upsertCurrentLocation(userId, update).toDto()
    }

    /**
     * Location-spoofing signal (TRUST_AND_SAFETY_SPEC): a device fix that implies
     * physically impossible travel versus the previous device fix feeds the risk
     * model. Signal only — the update itself always proceeds, and any failure in
     * the risk path must never break location updates. Travel-mode and manual-city
     * sources are legitimate jumps and are excluded on both sides; the distance
     * floor keeps GPS jitter and tiny intervals from ever flagging.
     */
    private suspend fun publishSpoofSignalIfImpossible(
        userId: UUID,
        request: UpdateCurrentLocationRequestDto,
        source: String,
    ) {
        val publisher = locationRiskSignals ?: return
        if (source !in DEVICE_FIX_SOURCES) return
        runCatching {
            val previous = repository.getCurrentCoordinates(userId) ?: return
            if (previous.source !in DEVICE_FIX_SOURCES) return

            val distanceKm = haversineKm(previous.latitude, previous.longitude, request.latitude, request.longitude)
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
            accuracyMeters = accuracyMeters,
            source = source,
            geohash = geohash,
            city = city,
            region = region,
            country = country,
        )

    private fun String?.cleanOptional(maxLength: Int): String? {
        val value = this?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (value.length > maxLength) throw LocationException("INVALID_LOCATION_LABEL")
        return value
    }

    private fun coarseLocationBucket(
        latitude: Double,
        longitude: Double,
    ): String {
        val latBucket = floor(latitude * 100.0) / 100.0
        val lonBucket = floor(longitude * 100.0) / 100.0
        return "%.2f:%.2f".format(latBucket, lonBucket)
    }

    companion object {
        private val SOURCES = setOf("gps", "network", "travel", "manual_city")
        private val DEVICE_FIX_SOURCES = setOf("gps", "network")

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
