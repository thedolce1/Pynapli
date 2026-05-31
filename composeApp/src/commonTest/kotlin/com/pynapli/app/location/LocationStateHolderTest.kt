package com.pynapli.app.location

import com.pynapli.shared.core.result.AppError
import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.network.dto.CurrentLocationDto
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LocationStateHolderTest {
    @Test
    fun firstLocationSubmitsAndRefreshesGrid() =
        runBlocking {
            val repository = FakeLocationUpdateRepository()
            val stateHolder = LocationStateHolder(repository)

            val result = stateHolder.submitLocation(DeviceLocation(latitude = 45.5017, longitude = -73.5673, accuracyMeters = 20))

            assertTrue(result.submitted)
            assertEquals(GridLocationRefresh.Refresh, result.gridRefresh)
            assertEquals(1, repository.locations.size)
            assertFalse(stateHolder.state.value.isUpdating)
        }

    @Test
    fun smallMovementSkipsBackendAndGridRefresh() =
        runBlocking {
            val repository = FakeLocationUpdateRepository()
            val stateHolder = LocationStateHolder(repository)

            stateHolder.submitLocation(DeviceLocation(latitude = 45.5017, longitude = -73.5673, accuracyMeters = 20))
            val result = stateHolder.submitLocation(DeviceLocation(latitude = 45.5020, longitude = -73.5673, accuracyMeters = 20))

            assertFalse(result.submitted)
            assertEquals(GridLocationRefresh.None, result.gridRefresh)
            assertEquals(1, repository.locations.size)
            assertFalse(stateHolder.state.value.isUpdating)
        }

    @Test
    fun mediumMovementSubmitsAndRequestsQuietGridRefresh() =
        runBlocking {
            val repository = FakeLocationUpdateRepository()
            val stateHolder = LocationStateHolder(repository)

            stateHolder.submitLocation(DeviceLocation(latitude = 45.5017, longitude = -73.5673, accuracyMeters = 20))
            val result = stateHolder.submitLocation(DeviceLocation(latitude = 45.5052, longitude = -73.5673, accuracyMeters = 20))

            assertTrue(result.submitted)
            assertEquals(GridLocationRefresh.QuietRefresh, result.gridRefresh)
            assertEquals(2, repository.locations.size)
        }

    @Test
    fun largeMovementSubmitsAndRequestsGridRefresh() =
        runBlocking {
            val repository = FakeLocationUpdateRepository()
            val stateHolder = LocationStateHolder(repository)

            stateHolder.submitLocation(DeviceLocation(latitude = 45.5017, longitude = -73.5673, accuracyMeters = 20))
            val result = stateHolder.submitLocation(DeviceLocation(latitude = 45.5140, longitude = -73.5673, accuracyMeters = 20))

            assertTrue(result.submitted)
            assertEquals(GridLocationRefresh.Refresh, result.gridRefresh)
            assertEquals(2, repository.locations.size)
        }

    @Test
    fun failedSubmitDoesNotUpdateMovementAnchor() =
        runBlocking {
            val repository = FakeLocationUpdateRepository()
            val stateHolder = LocationStateHolder(repository)

            stateHolder.submitLocation(DeviceLocation(latitude = 45.5017, longitude = -73.5673, accuracyMeters = 20))
            repository.nextResult = AppResult.Failure(AppError(code = "LOCATION_FAILED", message = "Location failed."))
            val failed = stateHolder.submitLocation(DeviceLocation(latitude = 45.5052, longitude = -73.5673, accuracyMeters = 20))
            repository.nextResult = AppResult.Success(testCurrentLocation())
            val retried = stateHolder.submitLocation(DeviceLocation(latitude = 45.5052, longitude = -73.5673, accuracyMeters = 20))

            assertFalse(failed.submitted)
            assertEquals(GridLocationRefresh.None, failed.gridRefresh)
            assertTrue(retried.submitted)
            assertEquals(GridLocationRefresh.QuietRefresh, retried.gridRefresh)
            assertEquals(3, repository.locations.size)
        }
}

private class FakeLocationUpdateRepository : LocationUpdateRepository {
    val locations = mutableListOf<DeviceLocation>()
    var nextResult: AppResult<CurrentLocationDto> = AppResult.Success(testCurrentLocation())

    override suspend fun updateCurrentLocation(location: DeviceLocation): AppResult<CurrentLocationDto> {
        locations += location
        return nextResult
    }
}

private fun testCurrentLocation(): CurrentLocationDto =
    CurrentLocationDto(
        updatedAt = "2026-05-08T00:00:00Z",
        accuracyMeters = 20,
        source = "gps",
        geohash = "45.50:-73.57",
        city = null,
        region = null,
        country = null,
    )
