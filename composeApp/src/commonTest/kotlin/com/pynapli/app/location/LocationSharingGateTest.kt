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

/** The silent resume refresh follows what each account chose in the app, not the per-install OS grant. */
class LocationSharingGateTest {
    private val here = DeviceLocation(latitude = 45.5017, longitude = -73.5673, accuracyMeters = 20)

    @Test
    fun noSilentRefreshUntilThisAccountSharesInTheApp() =
        runTest {
            val gate = LocationSharingGate(InMemoryLocationSharingConsentStore(), currentUserId = { "user-a" })

            assertFalse(gate.allowsSilentRefresh(onboardingActive = false), "an OS grant alone is not this account's choice")
            gate.recordSharedInApp()
            assertTrue(gate.allowsSilentRefresh(onboardingActive = false))
        }

    @Test
    fun accountAsShareNeverCoversAccountBsSignUp() =
        runTest {
            var userId = "user-a"
            val gate = LocationSharingGate(InMemoryLocationSharingConsentStore(), currentUserId = { userId })
            gate.recordSharedInApp()

            // A signs out; B signs up on the same device (the OS permission is still granted).
            userId = "user-b"

            assertFalse(gate.allowsSilentRefresh(onboardingActive = true))
            // Even after B's stepper is done, B has not shared: B's location is never sent silently.
            assertFalse(gate.allowsSilentRefresh(onboardingActive = false))
        }

    @Test
    fun skippingStopsTheSilentRefreshUntilTheNextShare() =
        runTest {
            val gate = LocationSharingGate(InMemoryLocationSharingConsentStore(), currentUserId = { "user-a" })
            gate.recordSharedInApp()

            gate.recordSkipped()
            assertFalse(gate.allowsSilentRefresh(onboardingActive = false))

            gate.recordSharedInApp()
            assertTrue(gate.allowsSilentRefresh(onboardingActive = false))
        }

    @Test
    fun neverWhileTheSignUpStepperIsOnScreen() =
        runTest {
            // A resumed sign-up whose account already shared: the stepper still owns the screen, and
            // the Location step auto-continues on a location, so nothing is sent behind it.
            val gate = LocationSharingGate(InMemoryLocationSharingConsentStore(), currentUserId = { "user-a" })
            gate.recordSharedInApp()

            assertFalse(gate.allowsSilentRefresh(onboardingActive = true))
        }

    @Test
    fun signedOutIsNeverAllowedAndRecordsNothing() =
        runTest {
            val store = InMemoryLocationSharingConsentStore()
            val gate = LocationSharingGate(store, currentUserId = { null })

            gate.recordSharedInApp()

            assertFalse(gate.allowsSilentRefresh(onboardingActive = false))
            assertTrue(store.load().sharedInAppUserIds.isEmpty())
        }

    @Test
    fun theDeviceListStaysSmall() {
        val consent = (1..12).fold(LocationSharingConsent()) { acc, n -> acc.withShared("user-$n") }

        assertEquals(LocationSharingConsent.MAX_ACCOUNTS, consent.sharedInAppUserIds.size)
        assertFalse(consent.sharedInApp("user-1"), "the oldest falls off first")
        assertTrue(consent.sharedInApp("user-12"))
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
            assertFalse(gate.allowsSilentRefresh(onboardingActive = false), "a share the server never took is not recorded")

            repository.next = AppResult.Success(location())
            holder.shareAndRefresh(here, grid, gate)
            assertTrue(gate.allowsSilentRefresh(onboardingActive = false))
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
