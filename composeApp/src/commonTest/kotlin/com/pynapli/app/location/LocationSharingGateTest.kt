package com.pynapli.app.location

import com.pynapli.app.grid.GridFeedRepository
import com.pynapli.app.grid.GridFeedUi
import com.pynapli.app.grid.GridFilters
import com.pynapli.app.grid.GridStateHolder
import com.pynapli.shared.core.result.AppError
import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.network.dto.CurrentLocationDto
import com.pynapli.shared.settings.InMemoryLocationSharingConsentStore
import com.pynapli.shared.settings.LocationSharingConsent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The silent resume refresh follows what each account chose, not the per-install OS grant. */
class LocationSharingGateTest {
    private val here = DeviceLocation(latitude = 45.5017, longitude = -73.5673, accuracyMeters = 20)

    private suspend fun LocationSharingGate.allows(
        onboardingActive: Boolean = false,
        osPermissionGranted: Boolean = true,
        serverHasLocation: Boolean = false,
    ) = allowsSilentRefresh(onboardingActive, osPermissionGranted, serverHasLocation)

    @Test
    fun anOsGrantAloneIsNotThisAccountsChoice() =
        runTest {
            val gate = LocationSharingGate(InMemoryLocationSharingConsentStore(), currentUserId = { "user-a" })

            assertFalse(gate.allows())
            gate.recordSharedInApp()
            assertTrue(gate.allows())
            assertFalse(gate.allows(osPermissionGranted = false), "never without the permission: no prompt")
        }

    @Test
    fun aMemberWhoseLocationTheServerAlreadyHoldsIsCoveredAndRecorded() =
        runTest {
            // Shared before this record existed (or on another device): no in-app share here, but the
            // first live Grid answer came back with cards, not LOCATION_REQUIRED.
            val store = InMemoryLocationSharingConsentStore()
            val gate = LocationSharingGate(store, currentUserId = { "user-a" })

            assertTrue(gate.allows(serverHasLocation = true))
            assertEquals(LocationSharingConsent(userId = "user-a", shared = true), store.load(), "recorded when seen")
            // Recorded: it holds on a later resume before this session's Grid has answered.
            assertTrue(gate.allows(serverHasLocation = false))
        }

    @Test
    fun accountAsGrantNeverReachesAccountBsSignUp() =
        runTest {
            var userId = "user-a"
            val store = InMemoryLocationSharingConsentStore()
            val gate = LocationSharingGate(store, currentUserId = { userId })
            gate.recordSharedInApp()

            // A signs out (record cleared); B signs up on the same device, OS permission still granted.
            gate.clearForSignOut()
            userId = "user-b"

            assertFalse(gate.allows(onboardingActive = true))
            gate.recordSkipped()
            // After the stepper: B skipped and the server has no location for B (LOCATION_REQUIRED).
            assertFalse(gate.allows(serverHasLocation = false))
        }

    @Test
    fun aSkipWinsOverTheServerHavingALocationUntilTheMemberShares() =
        runTest {
            val gate = LocationSharingGate(InMemoryLocationSharingConsentStore(), currentUserId = { "user-a" })
            gate.recordSharedInApp()

            gate.recordSkipped()
            assertFalse(gate.allows(serverHasLocation = true), "rule (b) must not re-grant after Skip")

            gate.recordSharedInApp()
            assertTrue(gate.allows())
        }

    @Test
    fun neverWhileTheSignUpStepperIsOnScreen() =
        runTest {
            val gate = LocationSharingGate(InMemoryLocationSharingConsentStore(), currentUserId = { "user-a" })
            gate.recordSharedInApp()

            assertFalse(gate.allows(onboardingActive = true, serverHasLocation = true))
        }

    @Test
    fun onlyTheCurrentAccountIsKeptAndSignOutClearsIt() =
        runTest {
            var userId: String? = "user-a"
            val store = InMemoryLocationSharingConsentStore()
            val gate = LocationSharingGate(store, currentUserId = { userId })
            gate.recordSharedInApp()

            // Another account's record counts as none, and is replaced by the next write.
            userId = "user-b"
            assertFalse(gate.allows())
            gate.recordSharedInApp()
            assertEquals("user-b", store.load().userId)

            gate.clearForSignOut()
            assertEquals(LocationSharingConsent(), store.load())
            userId = null
            gate.recordSharedInApp()
            assertFalse(gate.allows(serverHasLocation = true), "signed out: never")
            assertEquals(LocationSharingConsent(), store.load())
        }

    @Test
    fun anAcceptedShareRecordsTheChoiceAndARefusedOneDoesNot() =
        runTest {
            val repository = ScriptedLocationRepository()
            val holder = LocationStateHolder(repository)
            val gate = LocationSharingGate(InMemoryLocationSharingConsentStore(), currentUserId = { "user-a" })
            val grid = GridStateHolder(EmptyGrid)

            repository.next = AppResult.Failure(AppError(code = "NETWORK_ERROR", message = "No connection."))
            holder.shareAndRefresh(here, grid, gate)
            assertFalse(gate.allows(), "a share the server never took is not recorded")
            // ... but once a Grid answer shows the server has a location, rule (b) covers it.
            assertTrue(gate.allows(serverHasLocation = true))

            gate.recordSkipped()
            repository.next = AppResult.Success(location())
            holder.shareAndRefresh(here, grid, gate)
            assertTrue(gate.allows(), "an in-app Share undoes an earlier Skip")
        }

    private object EmptyGrid : GridFeedRepository {
        override suspend fun loadGrid(
            filters: GridFilters,
            cursor: String?,
        ): GridFeedUi = GridFeedUi(cards = emptyList())
    }

    private class ScriptedLocationRepository : LocationUpdateRepository {
        var next: AppResult<CurrentLocationDto> = AppResult.Success(location())

        override suspend fun updateCurrentLocation(location: DeviceLocation): AppResult<CurrentLocationDto> = next
    }
}

private fun location(): CurrentLocationDto =
    CurrentLocationDto(
        updatedAt = "2026-11-24T00:00:00Z",
        accuracyMeters = null,
        source = "gps",
        city = null,
        region = null,
        country = null,
    )
