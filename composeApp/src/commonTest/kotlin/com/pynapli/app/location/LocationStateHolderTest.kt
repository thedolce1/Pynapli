package com.pynapli.app.location

import com.pynapli.shared.core.result.AppError
import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.location.LocationCell
import com.pynapli.shared.network.dto.CurrentLocationDto
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LocationStateHolderTest {
    @Test
    fun firstLocationSubmitsAndRefreshesGrid() =
        runTest {
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
        runTest {
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
        runTest {
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
        runTest {
            val repository = FakeLocationUpdateRepository()
            val stateHolder = LocationStateHolder(repository)

            stateHolder.submitLocation(DeviceLocation(latitude = 45.5017, longitude = -73.5673, accuracyMeters = 20))
            val result = stateHolder.submitLocation(DeviceLocation(latitude = 45.5140, longitude = -73.5673, accuracyMeters = 20))

            assertTrue(result.submitted)
            assertEquals(GridLocationRefresh.Refresh, result.gridRefresh)
            assertEquals(2, repository.locations.size)
        }

    @Test
    fun onlyTheCellLeavesTheDeviceWithoutAccuracy() =
        runTest {
            val repository = FakeLocationUpdateRepository()
            val stateHolder = LocationStateHolder(repository)

            stateHolder.submitLocation(DeviceLocation(latitude = 45.501734, longitude = -73.567261, accuracyMeters = 20))

            val sent = repository.locations.single()
            assertEquals(LocationCell.snapCoordinate(45.501734), sent.latitude)
            assertEquals(LocationCell.snapCoordinate(-73.567261), sent.longitude)
            assertEquals(4550, LocationCell.hundredths(sent.latitude))
            assertEquals(-7357, LocationCell.hundredths(sent.longitude))
            assertNull(sent.accuracyMeters)
        }

    @Test
    fun thresholdsActOnTheUnsnappedMoveWhileSubmissionsAreSnapped() =
        runTest {
            val repository = FakeLocationUpdateRepository()
            val stateHolder = LocationStateHolder(repository)

            stateHolder.submitLocation(DeviceLocation(latitude = 45.5017, longitude = -73.5673, accuracyMeters = 20))
            // ~345 m north but still inside the same cell (45.50): measured on the snapped points the
            // move would be zero and skipped; measured on the real fix it is a quiet refresh.
            val sameCell = stateHolder.submitLocation(DeviceLocation(latitude = 45.5048, longitude = -73.5673, accuracyMeters = 20))
            // ~200 m further north and into the next cell (45.51): on snapped points this would read
            // as a 1.1 km jump and a full refresh; on the real fix it is under 250 m and skipped.
            val nextCell = stateHolder.submitLocation(DeviceLocation(latitude = 45.5066, longitude = -73.5673, accuracyMeters = 20))

            assertEquals(GridLocationRefresh.QuietRefresh, sameCell.gridRefresh)
            assertTrue(sameCell.submitted)
            assertEquals(GridLocationRefresh.None, nextCell.gridRefresh)
            assertFalse(nextCell.submitted)
            assertEquals(2, repository.locations.size)
            repository.locations.forEach { sent ->
                assertEquals(4550, LocationCell.hundredths(sent.latitude))
                assertEquals(-7357, LocationCell.hundredths(sent.longitude))
                assertNull(sent.accuracyMeters)
            }
        }

    @Test
    fun failedSubmitDoesNotUpdateMovementAnchor() =
        runTest {
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

    // Section 0 repro B2 (onboarding + first grid plan), fixed in Section 1: App.kt resets this holder
    // on sign-out next to the Grid and Favorites.
    @Test
    fun theNextAccountOnThisDeviceSendsItsOwnLocation() =
        runTest {
            val repository = FakeLocationUpdateRepository()
            // One holder per app process: every shell builds it once in PynapliCommonGraph.
            val stateHolder = LocationStateHolder(repository)
            val here = DeviceLocation(latitude = 45.5017, longitude = -73.5673, accuracyMeters = 20)

            // Account A shares its location during sign-up.
            assertTrue(stateHolder.submitLocation(here).submitted)

            // Account A signs out and account B signs up in the same process, standing in the same
            // place. App.kt's sign-out effect resets this holder.
            stateHolder.reset()

            // B's Location step auto-continues on a non-null currentLocation, so B skips it ...
            assertNull(stateHolder.state.value.currentLocation, "account A's location must not carry over to account B")
            // ... and if B does share, the <250 m rule drops it, so B's location never reaches the
            // server and B's grid stays on "Use current location".
            val forB = stateHolder.submitLocation(here)
            assertTrue(forB.submitted)
            assertEquals(2, repository.locations.size)
        }

    @Test
    fun aSubmitStillInFlightAtSignOutNeverWritesIntoTheNextAccount() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val repository = FakeLocationUpdateRepository(gate = gate)
            val stateHolder = LocationStateHolder(repository)
            val here = DeviceLocation(latitude = 45.5017, longitude = -73.5673, accuracyMeters = 20)

            // Account A's fix is on the wire when A signs out.
            val forA = async { stateHolder.submitLocation(here) }
            runCurrent()
            stateHolder.reset()
            gate.complete(Unit)

            assertFalse(forA.await().submitted)
            assertNull(stateHolder.state.value.currentLocation, "A's answer must not land in B's state")
            assertFalse(stateHolder.state.value.isUpdating)
            // ... nor become B's movement anchor: B's own fix from the same spot is still sent.
            assertTrue(stateHolder.submitLocation(here).submitted)
        }
}

private class FakeLocationUpdateRepository(
    private val gate: CompletableDeferred<Unit>? = null,
) : LocationUpdateRepository {
    val locations = mutableListOf<DeviceLocation>()
    var nextResult: AppResult<CurrentLocationDto> = AppResult.Success(testCurrentLocation())

    override suspend fun updateCurrentLocation(location: DeviceLocation): AppResult<CurrentLocationDto> {
        locations += location
        if (gate != null && locations.size == 1) gate.await()
        return nextResult
    }
}

private fun testCurrentLocation(): CurrentLocationDto =
    CurrentLocationDto(
        updatedAt = "2026-05-08T00:00:00Z",
        accuracyMeters = 20,
        source = "gps",
        city = null,
        region = null,
        country = null,
    )
