package com.pynapli.server.location

import com.pynapli.server.safety.LocationRiskSignalPublisher
import com.pynapli.shared.location.LocationCell
import com.pynapli.shared.network.dto.UpdateCurrentLocationRequestDto
import kotlinx.coroutines.runBlocking
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LocationServiceTest {
    private val now = Instant.parse("2026-06-10T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    @Test
    fun updateCurrentLocationStoresCoarseResponseWithoutExactCoordinates() {
        runBlocking {
            val userId = UUID.randomUUID()
            val repository = InMemoryLocationRepository()
            val service = LocationService(repository)

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

            // No accuracy figure is kept or echoed any more (it described the precise fix).
            assertNull(response.accuracyMeters)
            // The repository only ever receives the cell centre, never the precise fix.
            val stored = assertNotNull(repository.lastUpdate)
            assertEquals(4550, LocationCell.hundredths(stored.latitude))
            assertEquals(-7357, LocationCell.hundredths(stored.longitude))
            assertEquals(LocationCell.snapCoordinate(45.5017), stored.latitude)
            assertEquals(LocationCell.snapCoordinate(-73.5673), stored.longitude)
            assertEquals("gps", response.source)
            assertEquals("Montreal", response.city)
            // L-40: the write acknowledgement echoes no location of any kind (no bucket, no
            // coordinates): nothing in the serialized response can be read back as a position.
            val json =
                com.pynapli.shared.network.PynapliJson
                    .encodeToString(
                        com.pynapli.shared.network.dto.CurrentLocationDto
                            .serializer(),
                        response,
                    )
            listOf("geohash", "45.5", "-73.5", "latitude", "longitude").forEach {
                assertTrue(it !in json, "location echoed back ($it): $json")
            }
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

    // L-13: the client-declared source is not trusted. Declaring `manual_city` or `travel` on either
    // side used to skip the impossible-travel signal; now every combination is evaluated.
    @Test
    fun aDeclaredManualOrTravelSourceNoLongerSkipsTheSpoofSignal() =
        runBlocking {
            val userId = UUID.randomUUID()
            val montreal =
                CurrentCoordinatesRecord(
                    latitude = 45.5017,
                    longitude = -73.5673,
                    source = "gps",
                    updatedAt = now.minusSeconds(60),
                )
            listOf("gps", "network", "travel", "manual_city").forEach { previousSource ->
                listOf("gps", "network", "travel", "manual_city").forEach { newSource ->
                    val signals = RecordingLocationRiskSignals()
                    LocationService(InMemoryLocationRepository(stored = montreal.copy(source = previousSource)), signals, clock)
                        .updateCurrentLocation(
                            userId,
                            UpdateCurrentLocationRequestDto(latitude = 51.5074, longitude = -0.1278, source = newSource),
                        )
                    assertNotNull(signals.lastSignal, "no signal for $previousSource -> $newSource")
                }
            }
        }

    // Legitimate Travel mode still never flags: the exemption is the server's own record of an
    // active travel session, whatever the client declares.
    @Test
    fun anActiveServerSideTravelSessionIsALegitimateJump() =
        runBlocking {
            val signals = RecordingLocationRiskSignals()
            val travelling =
                CurrentCoordinatesRecord(
                    latitude = 45.5017,
                    longitude = -73.5673,
                    source = "gps",
                    updatedAt = now.minusSeconds(60),
                    travelSessionActive = true,
                )
            LocationService(InMemoryLocationRepository(stored = travelling), signals, clock)
                .updateCurrentLocation(
                    UUID.randomUUID(),
                    UpdateCurrentLocationRequestDto(latitude = 51.5074, longitude = -0.1278, source = "gps"),
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
    var lastUpdate: LocationUpdate? = null
        private set

    override suspend fun upsertCurrentLocation(
        userId: UUID,
        update: LocationUpdate,
    ): CurrentLocationRecord {
        upserts += 1
        lastUpdate = update
        return CurrentLocationRecord(
            updatedAt = Instant.parse("2026-04-24T00:00:00Z"),
            source = update.source,
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
