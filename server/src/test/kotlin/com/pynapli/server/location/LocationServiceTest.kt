package com.pynapli.server.location

import com.pynapli.shared.network.dto.UpdateCurrentLocationRequestDto
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class LocationServiceTest {
    @Test
    fun updateCurrentLocationStoresCoarseResponseWithoutExactCoordinates() {
        runBlocking {
            val userId = UUID.randomUUID()
            val service = LocationService(InMemoryLocationRepository())

            val response = service.updateCurrentLocation(
                userId,
                UpdateCurrentLocationRequestDto(
                    latitude = 45.5017,
                    longitude = -73.5673,
                    accuracyMeters = 30,
                    source = "gps",
                    city = "Montreal",
                    region = "QC",
                    country = "CA"
                )
            )

            assertEquals(30, response.accuracyMeters)
            assertEquals("gps", response.source)
            assertEquals("45.50:-73.57", response.geohash)
            assertEquals("Montreal", response.city)
            assertNotNull(response.updatedAt)
        }
    }

    @Test
    fun updateCurrentLocationRejectsInvalidLatitude() = runBlocking {
        val service = LocationService(InMemoryLocationRepository())

        val error = assertFailsWith<LocationException> {
            service.updateCurrentLocation(
                UUID.randomUUID(),
                UpdateCurrentLocationRequestDto(
                    latitude = 100.0,
                    longitude = -73.5673,
                    source = "gps"
                )
            )
        }

        assertEquals("INVALID_LATITUDE", error.code)
    }

    @Test
    fun updateCurrentLocationRejectsInvalidSource() = runBlocking {
        val service = LocationService(InMemoryLocationRepository())

        val error = assertFailsWith<LocationException> {
            service.updateCurrentLocation(
                UUID.randomUUID(),
                UpdateCurrentLocationRequestDto(
                    latitude = 45.5017,
                    longitude = -73.5673,
                    source = "unknown"
                )
            )
        }

        assertEquals("INVALID_LOCATION_SOURCE", error.code)
    }
}

private class InMemoryLocationRepository : LocationRepository {
    override suspend fun upsertCurrentLocation(userId: UUID, update: LocationUpdate): CurrentLocationRecord =
        CurrentLocationRecord(
            updatedAt = Instant.parse("2026-04-24T00:00:00Z"),
            accuracyMeters = update.accuracyMeters,
            source = update.source,
            geohash = update.geohash,
            city = update.city,
            region = update.region,
            country = update.country
        )
}
