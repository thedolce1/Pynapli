package com.pynapli.app.location

import com.pynapli.app.grid.GridFeedRepository
import com.pynapli.app.grid.GridFeedUi
import com.pynapli.app.grid.GridFilters
import com.pynapli.app.grid.GridStateHolder
import com.pynapli.shared.core.result.AppError
import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.location.LocationCell
import com.pynapli.shared.network.dto.CurrentLocationDto
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
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

    // Resume refresh: a member who already granted location, coming back to the app.

    @Test
    fun aResumeFixInTheSameCellIsNotSentAndShowsNothing() =
        runTest {
            val repository = FakeLocationUpdateRepository()
            val stateHolder = LocationStateHolder(repository)
            val grid = CountingGrid()
            stateHolder.submitLocation(DeviceLocation(latitude = 45.5017, longitude = -73.5673, accuracyMeters = 20))
            val states = mutableListOf<LocationUiState>()
            val watcher = launch { stateHolder.state.collect { states += it } }
            runCurrent()

            // About 345 m north, still inside cell 45.50: over the 250 m line, but the same cell.
            val result = stateHolder.refreshInBackground(DeviceLocation(latitude = 45.5048, longitude = -73.5673, accuracyMeters = 20), grid.holder)
            runCurrent()
            watcher.cancel()

            assertFalse(result.submitted)
            assertEquals(1, repository.locations.size)
            assertEquals(0, grid.loads)
            assertTrue(states.none { it.isUpdating }, "a silent refresh never shows progress: $states")
        }

    @Test
    fun aResumeFixInANewCellIsSentAndRefreshesTheGridQuietly() =
        runTest {
            val repository = FakeLocationUpdateRepository()
            val stateHolder = LocationStateHolder(repository)
            val grid = CountingGrid()
            stateHolder.submitLocation(DeviceLocation(latitude = 45.5017, longitude = -73.5673, accuracyMeters = 20))

            // Over 1 km away, a different cell: sent, and the grid refreshes in place.
            val result = stateHolder.refreshInBackground(DeviceLocation(latitude = 45.5200, longitude = -73.5673, accuracyMeters = 20), grid.holder)

            assertTrue(result.submitted)
            assertEquals(2, repository.locations.size)
            assertEquals(LocationCell.snapCoordinate(45.5200), repository.locations.last().latitude)
            assertNull(repository.locations.last().accuracyMeters)
            assertEquals(1, grid.loads)
        }

    @Test
    fun aFailedResumeSendSurfacesNoError() =
        runTest {
            val repository = FakeLocationUpdateRepository()
            repository.nextResult = AppResult.Failure(AppError(code = "NETWORK_ERROR", message = "No connection."))
            val stateHolder = LocationStateHolder(repository)

            val result = stateHolder.refreshInBackground(DeviceLocation(latitude = 45.5017, longitude = -73.5673, accuracyMeters = 20), CountingGrid().holder)

            assertFalse(result.submitted)
            assertNull(stateHolder.state.value.errorMessage)
            assertFalse(stateHolder.state.value.isUpdating)
            // Not anchored: the next resume tries again.
            repository.nextResult = AppResult.Success(testCurrentLocation())
            assertTrue(stateHolder.refreshInBackground(DeviceLocation(latitude = 45.5017, longitude = -73.5673, accuracyMeters = 20), CountingGrid().holder).submitted)
        }

    @Test
    fun aBlockedPermissionOrServicesOffSaysSoUntilTheNextAttempt() =
        runTest {
            val stateHolder = LocationStateHolder(FakeLocationUpdateRepository())

            stateHolder.markPermissionDenied()
            assertNull(stateHolder.state.value.blockedReason, "a first refusal can still be asked again")

            stateHolder.markPermissionDenied(permanently = true)
            assertEquals(LocationBlockedReason.PermissionBlocked, stateHolder.state.value.blockedReason)
            assertEquals("Location permission is required to load nearby profiles.", stateHolder.state.value.errorMessage)

            stateHolder.markServicesOff()
            assertEquals(LocationBlockedReason.ServicesOff, stateHolder.state.value.blockedReason)

            // Back from Settings: the next attempt clears it, and a fix that lands keeps it clear.
            stateHolder.beginUpdate()
            assertNull(stateHolder.state.value.blockedReason)
            stateHolder.markServicesOff()
            stateHolder.submitLocation(DeviceLocation(latitude = 45.5017, longitude = -73.5673, accuracyMeters = 20))
            assertNull(stateHolder.state.value.blockedReason)
            assertNull(stateHolder.state.value.errorMessage)
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

/** A real GridStateHolder over a repository that only counts first-page loads. */
private class CountingGrid {
    var loads = 0
        private set
    val holder =
        GridStateHolder(
            object : GridFeedRepository {
                override suspend fun loadGrid(
                    filters: GridFilters,
                    cursor: String?,
                ): GridFeedUi {
                    loads += 1
                    return GridFeedUi(cards = emptyList())
                }
            },
        )
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
