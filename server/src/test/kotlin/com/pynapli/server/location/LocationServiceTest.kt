package com.pynapli.server.location

import com.pynapli.server.safety.LocationRiskSignalPublisher
import com.pynapli.shared.network.dto.UpdateCurrentLocationRequestDto
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LocationServiceTest {
    private val now = Instant.parse("2026-06-10T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    @Test
    fun updateCurrentLocationStoresCoarseResponseWithoutExactCoordinates() {
        runBlocking {
            val userId = UUID.randomUUID()
            val service = LocationService(InMemoryLocationRepository())

            val response =
                service.updateCurrentLocation(
                    userId,
                    UpdateCurrentLocationRequestDto(
                        latitude = 45.5017,
                        longitude = -73.5673,
                        accuracyMeters = 30,
                        source = "gps",
                        city = "Montreal",
                        region = "QC",
                        country = "CA",
                    ),
                )

            assertEquals(30, response.accuracyMeters)
            assertEquals("gps", response.source)
            assertEquals("45.50:-73.57", response.geohash)
            assertEquals("Montreal", response.city)
            assertNotNull(response.updatedAt)
        }
    }

    @Test
    fun updateCurrentLocationRejectsInvalidLatitude() =
        runBlocking {
            val service = LocationService(InMemoryLocationRepository())

            val error =
                assertFailsWith<LocationException> {
                    service.updateCurrentLocation(
                        UUID.randomUUID(),
                        UpdateCurrentLocationRequestDto(
                            latitude = 100.0,
                            longitude = -73.5673,
                            source = "gps",
                        ),
                    )
                }

            assertEquals("INVALID_LATITUDE", error.code)
        }

    @Test
    fun updateCurrentLocationRejectsInvalidSource() =
        runBlocking {
            val service = LocationService(InMemoryLocationRepository())

            val error =
                assertFailsWith<LocationException> {
                    service.updateCurrentLocation(
                        UUID.randomUUID(),
                        UpdateCurrentLocationRequestDto(
                            latitude = 45.5017,
                            longitude = -73.5673,
                            source = "unknown",
                        ),
                    )
                }

            assertEquals("INVALID_LOCATION_SOURCE", error.code)
        }

    @Test
    fun impossibleTravelBetweenDeviceFixesPublishesRiskSignal() =
        runBlocking {
            val userId = UUID.randomUUID()
            // Previous fix: Montreal, 10 minutes ago.
            val repository =
                InMemoryLocationRepository(
                    stored =
                        CurrentCoordinatesRecord(
                            latitude = 45.5017,
                            longitude = -73.5673,
                            source = "gps",
                            updatedAt = now.minusSeconds(600),
                        ),
                )
            val signals = RecordingLocationRiskSignals()
            val service = LocationService(repository, signals, clock)

            // New fix: London — ~5,200 km in 10 minutes.
            service.updateCurrentLocation(
                userId,
                UpdateCurrentLocationRequestDto(latitude = 51.5074, longitude = -0.1278, source = "gps"),
            )

            val signal = assertNotNull(signals.lastSignal)
            assertEquals(userId, signal.userId)
            assertTrue(signal.distanceKm > 5_000)
            assertEquals(600, signal.elapsedSeconds)
            assertTrue(signal.speedKmh > LocationService.IMPOSSIBLE_TRAVEL_MIN_SPEED_KMH)
            // The update itself still went through: signal, not block.
            assertEquals(1, repository.upserts)
        }

    @Test
    fun plausibleTravelDoesNotPublishRiskSignal() =
        runBlocking {
            val userId = UUID.randomUUID()
            // Montreal -> Toronto (~500 km) over five hours: ~100 km/h.
            val repository =
                InMemoryLocationRepository(
                    stored =
                        CurrentCoordinatesRecord(
                            latitude = 45.5017,
                            longitude = -73.5673,
                            source = "gps",
                            updatedAt = now.minusSeconds(5 * 3600),
                        ),
                )
            val signals = RecordingLocationRiskSignals()
            val service = LocationService(repository, signals, clock)

            service.updateCurrentLocation(
                userId,
                UpdateCurrentLocationRequestDto(latitude = 43.6532, longitude = -79.3832, source = "gps"),
            )

            assertEquals(null, signals.lastSignal)
        }

    @Test
    fun shortHopsNeverFlagEvenWithTinyIntervals() =
        runBlocking {
            val userId = UUID.randomUUID()
            // 1-2 km of GPS jitter one second apart computes to a huge speed but
            // stays under the distance floor.
            val repository =
                InMemoryLocationRepository(
                    stored =
                        CurrentCoordinatesRecord(
                            latitude = 45.5017,
                            longitude = -73.5673,
                            source = "network",
                            updatedAt = now.minusSeconds(1),
                        ),
                )
            val signals = RecordingLocationRiskSignals()
            val service = LocationService(repository, signals, clock)

            service.updateCurrentLocation(
                userId,
                UpdateCurrentLocationRequestDto(latitude = 45.5150, longitude = -73.5700, source = "gps"),
            )

            assertEquals(null, signals.lastSignal)
        }

    @Test
    fun travelModeSourcesAreLegitimateJumpsAndNeverFlag() =
        runBlocking {
            val userId = UUID.randomUUID()
            val farAway =
                CurrentCoordinatesRecord(
                    latitude = 45.5017,
                    longitude = -73.5673,
                    source = "travel",
                    updatedAt = now.minusSeconds(60),
                )
            val signals = RecordingLocationRiskSignals()

            // Previous fix was a travel/manual jump: not comparable.
            LocationService(InMemoryLocationRepository(stored = farAway), signals, clock)
                .updateCurrentLocation(
                    userId,
                    UpdateCurrentLocationRequestDto(latitude = 51.5074, longitude = -0.1278, source = "gps"),
                )
            assertEquals(null, signals.lastSignal)

            // New fix is itself a travel/manual jump: not a device fix.
            LocationService(
                InMemoryLocationRepository(stored = farAway.copy(source = "gps")),
                signals,
                clock,
            ).updateCurrentLocation(
                userId,
                UpdateCurrentLocationRequestDto(latitude = 51.5074, longitude = -0.1278, source = "manual_city"),
            )
            assertEquals(null, signals.lastSignal)
        }

    @Test
    fun firstFixAndRiskPathFailuresNeverBreakTheUpdate() =
        runBlocking {
            val userId = UUID.randomUUID()
            // No previous fix at all.
            val signals = RecordingLocationRiskSignals()
            val freshRepository = InMemoryLocationRepository()
            LocationService(freshRepository, signals, clock)
                .updateCurrentLocation(
                    userId,
                    UpdateCurrentLocationRequestDto(latitude = 51.5074, longitude = -0.1278, source = "gps"),
                )
            assertEquals(null, signals.lastSignal)
            assertEquals(1, freshRepository.upserts)

            // Publisher throwing must not surface to the caller.
            val throwingRepository =
                InMemoryLocationRepository(
                    stored =
                        CurrentCoordinatesRecord(
                            latitude = 45.5017,
                            longitude = -73.5673,
                            source = "gps",
                            updatedAt = now.minusSeconds(600),
                        ),
                )
            val response =
                LocationService(throwingRepository, ThrowingLocationRiskSignals, clock)
                    .updateCurrentLocation(
                        userId,
                        UpdateCurrentLocationRequestDto(latitude = 51.5074, longitude = -0.1278, source = "gps"),
                    )
            assertNotNull(response.updatedAt)
            assertEquals(1, throwingRepository.upserts)
        }
}

private class InMemoryLocationRepository(
    private val stored: CurrentCoordinatesRecord? = null,
) : LocationRepository {
    var upserts = 0
        private set

    override suspend fun upsertCurrentLocation(
        userId: UUID,
        update: LocationUpdate,
    ): CurrentLocationRecord {
        upserts += 1
        return CurrentLocationRecord(
            updatedAt = Instant.parse("2026-04-24T00:00:00Z"),
            accuracyMeters = update.accuracyMeters,
            source = update.source,
            geohash = update.geohash,
            city = update.city,
            region = update.region,
            country = update.country,
        )
    }

    override suspend fun getCurrentCoordinates(userId: UUID): CurrentCoordinatesRecord? = stored
}

private class RecordingLocationRiskSignals : LocationRiskSignalPublisher {
    data class Signal(
        val userId: UUID,
        val distanceKm: Double,
        val elapsedSeconds: Long,
        val speedKmh: Double,
    )

    var lastSignal: Signal? = null
        private set

    override suspend fun recordImpossibleTravel(
        userId: UUID,
        distanceKm: Double,
        elapsedSeconds: Long,
        speedKmh: Double,
    ) {
        lastSignal = Signal(userId, distanceKm, elapsedSeconds, speedKmh)
    }
}

private object ThrowingLocationRiskSignals : LocationRiskSignalPublisher {
    override suspend fun recordImpossibleTravel(
        userId: UUID,
        distanceKm: Double,
        elapsedSeconds: Long,
        speedKmh: Double,
    ): Unit = throw IllegalStateException("risk path down")
}
