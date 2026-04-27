package com.pynapli.server.location

import com.pynapli.shared.network.dto.CurrentLocationDto
import com.pynapli.shared.network.dto.UpdateCurrentLocationRequestDto
import java.util.UUID
import kotlin.math.floor

class LocationService(
    private val repository: LocationRepository
) {
    suspend fun updateCurrentLocation(
        userId: UUID,
        request: UpdateCurrentLocationRequestDto
    ): CurrentLocationDto {
        validate(request)
        val update = LocationUpdate(
            latitude = request.latitude,
            longitude = request.longitude,
            accuracyMeters = request.accuracyMeters,
            source = request.source.trim().lowercase(),
            geohash = coarseLocationBucket(request.latitude, request.longitude),
            city = request.city.cleanOptional(80),
            region = request.region.cleanOptional(80),
            country = request.country.cleanOptional(80)
        )

        return repository.upsertCurrentLocation(userId, update).toDto()
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

    private fun CurrentLocationRecord.toDto(): CurrentLocationDto = CurrentLocationDto(
        updatedAt = updatedAt.toString(),
        accuracyMeters = accuracyMeters,
        source = source,
        geohash = geohash,
        city = city,
        region = region,
        country = country
    )

    private fun String?.cleanOptional(maxLength: Int): String? {
        val value = this?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (value.length > maxLength) throw LocationException("INVALID_LOCATION_LABEL")
        return value
    }

    private fun coarseLocationBucket(latitude: Double, longitude: Double): String {
        val latBucket = floor(latitude * 100.0) / 100.0
        val lonBucket = floor(longitude * 100.0) / 100.0
        return "%.2f:%.2f".format(latBucket, lonBucket)
    }

    companion object {
        private val SOURCES = setOf("gps", "network", "travel", "manual_city")
    }
}

class LocationException(val code: String) : RuntimeException(code)

