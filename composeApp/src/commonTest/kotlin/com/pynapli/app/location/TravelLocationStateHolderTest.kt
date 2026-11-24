package com.pynapli.app.location

import com.pynapli.shared.core.result.AppError
import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.network.dto.SetTravelLocationRequestDto
import com.pynapli.shared.network.dto.TravelLocationDto
import com.pynapli.shared.network.dto.TravelPlaceDto
import com.pynapli.shared.network.dto.TravelPlaceSearchResponseDto
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TravelLocationStateHolderTest {
    private val vegas =
        TravelPlaceDto(
            displayName = "Las Vegas, Nevada, US",
            city = "Las Vegas",
            region = "Nevada",
            country = "US",
            latitude = 36.17497,
            longitude = -115.13722,
        )

    private val activeInVegas =
        TravelLocationDto(
            city = "Las Vegas",
            latitude = vegas.latitude,
            longitude = vegas.longitude,
            activeFrom = "2026-06-09T00:00:00Z",
            status = "active",
        )

    @Test
    fun signOutForgetsTheAccountsTravelStateEvenWithALoadInFlight() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val repository = FakeRepository(currentResult = AppResult.Success(activeInVegas))
            val stateHolder = TravelLocationStateHolder(repository)
            stateHolder.load()
            assertEquals("Las Vegas", stateHolder.state.value.current?.city)

            // Account A's refetch is on the wire when A signs out.
            repository.currentGate = gate
            val loading = async { stateHolder.load() }
            runCurrent()
            stateHolder.reset()
            gate.complete(Unit)
            loading.await()

            assertNull(stateHolder.state.value.current, "A's travel city must not reach the next account")
            assertFalse(stateHolder.state.value.isLoading)
        }

    @Test
    fun aPickedPlaceCanBePlannedForDatesAndThenCancelled() =
        runTest {
            val repository = FakeRepository()
            val stateHolder = TravelLocationStateHolder(repository)

            stateHolder.selectPlace(vegas)
            assertEquals(vegas, stateHolder.state.value.selectedPlace)
            assertTrue(stateHolder.planTrip(vegas, "2026-11-14", "2026-11-17", "America/Toronto"))

            val sent = repository.planned.single()
            assertEquals(listOf("Las Vegas", "2026-11-14", "2026-11-17", "America/Toronto"), listOf(sent.city, sent.startsOn, sent.endsOn, sent.timezone))
            assertNull(stateHolder.state.value.selectedPlace, "the picker closes once the trip is saved")
            assertEquals(
                listOf("Las Vegas"),
                stateHolder.state.value.trips
                    .map { it.city },
            )
            assertTrue(repository.setRequests.isEmpty(), "planning never switches Travel mode on by itself")

            stateHolder.cancelTrip("trip-1")
            assertTrue(
                stateHolder.state.value.trips
                    .isEmpty(),
            )
            assertEquals(
                "14–17 Nov",
                com.pynapli.app.swipe
                    .tripDateRange("2026-11-14", "2026-11-17"),
            )
            assertEquals(
                "28 Nov – 2 Dec",
                com.pynapli.app.swipe
                    .tripDateRange("2026-11-28", "2026-12-02"),
            )
        }

    @Test
    fun setActiveAgeVerificationRequiredFlagsPromptAndSuppressesRawError() =
        runTest {
            // TravelLocationService throws AGE_VERIFICATION_REQUIRED for restricted regions when the
            // viewer is unverified. The holder must flag the verify prompt without leaving an active
            // travel row or a raw error toast (the sheet's verify CTA drives the next step).
            val repository =
                FakeRepository(
                    setResult = AppResult.Failure(AppError("AGE_VERIFICATION_REQUIRED", "Verify age")),
                )
            val stateHolder = TravelLocationStateHolder(repository)

            stateHolder.setActive(vegas)

            val state = stateHolder.state.value
            assertTrue(state.ageVerificationRequired)
            assertFalse(state.entitlementRequired)
            assertNull(state.current, "a gated set must not produce an active travel row")
            assertNull(state.errorMessage)
            assertFalse(state.isSaving)
        }

    @Test
    fun setActiveEntitlementRequiredStillFlagsPaywallNotVerification() =
        runTest {
            val repository =
                FakeRepository(
                    setResult = AppResult.Failure(AppError("ENTITLEMENT_REQUIRED", "Upgrade")),
                )
            val stateHolder = TravelLocationStateHolder(repository)

            stateHolder.setActive(vegas)

            val state = stateHolder.state.value
            assertTrue(state.entitlementRequired)
            assertFalse(state.ageVerificationRequired)
        }

    @Test
    fun aUsedSocialPreviewLeadsToTheSelectPaywallAndKeepsTheSheetOpen() =
        runTest {
            val repository =
                FakeRepository(
                    setResult = AppResult.Failure(AppError("TRAVEL_PREVIEW_RATE_LIMITED", "Preview used")),
                )
            val stateHolder = TravelLocationStateHolder(repository)

            val activated = stateHolder.setActive(vegas)

            assertFalse(activated)
            val state = stateHolder.state.value
            assertTrue(state.entitlementRequired)
            assertTrue(state.previewUsed)
            assertNull(state.errorMessage, "the paywall banner explains it")
        }

    @Test
    fun otherRefusalsKeepTheSheetOpenWithTheReason() =
        runTest {
            val repository =
                FakeRepository(
                    setResult = AppResult.Failure(AppError("TRAVEL_SWITCH_LIMITED", "You've changed destination a lot today.")),
                )
            val stateHolder = TravelLocationStateHolder(repository)

            val activated = stateHolder.setActive(vegas)

            assertFalse(activated)
            assertEquals("You've changed destination a lot today.", stateHolder.state.value.errorMessage)
            assertFalse(stateHolder.state.value.entitlementRequired)
        }

    @Test
    fun typingThenSearchingReturnsPlacesAndPickingSendsTheirCoordinates() =
        runTest {
            val active =
                TravelLocationDto(
                    city = "Las Vegas",
                    region = "Nevada",
                    country = "US",
                    latitude = vegas.latitude,
                    longitude = vegas.longitude,
                    activeFrom = "2026-06-09T00:00:00Z",
                    status = "active",
                )
            val repository =
                FakeRepository(
                    searchResult = AppResult.Success(TravelPlaceSearchResponseDto(items = listOf(vegas))),
                    setResult = AppResult.Success(active),
                )
            val stateHolder = TravelLocationStateHolder(repository)

            stateHolder.updateSearchQuery("las v")
            stateHolder.runSearch()
            assertEquals(listOf(vegas), stateHolder.state.value.searchResults)
            assertEquals("las v", repository.searchQueries.single())

            stateHolder.setActive(
                stateHolder.state.value.searchResults
                    .single(),
            )

            val state = stateHolder.state.value
            assertEquals("Las Vegas", state.current?.city)
            // Picking resets the picker for the next open.
            assertEquals("", state.searchQuery)
            assertTrue(state.searchResults.isEmpty())
            val request = repository.setRequests.single()
            assertEquals(vegas.latitude, request.latitude)
            assertEquals(vegas.longitude, request.longitude)
            assertEquals("Nevada", request.region)
        }

    @Test
    fun subMinimumQueryClearsResultsWithoutSearching() =
        runTest {
            val repository =
                FakeRepository(
                    searchResult = AppResult.Success(TravelPlaceSearchResponseDto(items = listOf(vegas))),
                )
            val stateHolder = TravelLocationStateHolder(repository)

            stateHolder.updateSearchQuery("L")
            stateHolder.runSearch()

            assertTrue(
                stateHolder.state.value.searchResults
                    .isEmpty(),
            )
            assertTrue(repository.searchQueries.isEmpty(), "short queries must not hit the network")
        }

    @Test
    fun failedSearchStaysQuietWithEmptyResults() =
        runTest {
            val repository =
                FakeRepository(
                    searchResult = AppResult.Failure(AppError("RATE_LIMITED", "slow down")),
                )
            val stateHolder = TravelLocationStateHolder(repository)

            stateHolder.updateSearchQuery("las vegas")
            stateHolder.runSearch()

            val state = stateHolder.state.value
            assertTrue(state.searchResults.isEmpty())
            assertNull(state.errorCode, "search failures must not raise the sheet error banner")
            assertFalse(state.isSearching)
        }
}

private class FakeRepository(
    private val currentResult: AppResult<TravelLocationDto?> = AppResult.Success(null),
    private val setResult: AppResult<TravelLocationDto> = AppResult.Failure(AppError("UNIMPLEMENTED", "no")),
    private val clearResult: AppResult<Unit> = AppResult.Success(Unit),
    private val searchResult: AppResult<TravelPlaceSearchResponseDto> =
        AppResult.Success(TravelPlaceSearchResponseDto(items = emptyList())),
) : TravelLocationRepository {
    val searchQueries = mutableListOf<String>()
    val setRequests = mutableListOf<SetTravelLocationRequestDto>()

    var currentGate: CompletableDeferred<Unit>? = null

    override suspend fun getCurrent(): AppResult<TravelLocationDto?> {
        currentGate?.await()
        return currentResult
    }

    override suspend fun setActive(request: SetTravelLocationRequestDto): AppResult<TravelLocationDto> {
        setRequests += request
        return setResult
    }

    override suspend fun clear(): AppResult<Unit> = clearResult

    override suspend fun searchPlaces(query: String): AppResult<TravelPlaceSearchResponseDto> {
        searchQueries += query
        return searchResult
    }

    val planned = mutableListOf<com.pynapli.shared.network.dto.PlanTravelTripRequestDto>()
    private var trips = listOf<com.pynapli.shared.network.dto.TravelTripDto>()

    override suspend fun listTrips(): AppResult<com.pynapli.shared.network.dto.TravelTripsDto> =
        AppResult.Success(
            com.pynapli.shared.network.dto
                .TravelTripsDto(trips),
        )

    override suspend fun planTrip(request: com.pynapli.shared.network.dto.PlanTravelTripRequestDto): AppResult<com.pynapli.shared.network.dto.TravelTripDto> {
        planned += request
        val trip =
            com.pynapli.shared.network.dto
                .TravelTripDto("trip-1", request.city, request.region, request.country, request.startsOn, request.endsOn, "planned")
        trips = trips + trip
        return AppResult.Success(trip)
    }

    override suspend fun cancelTrip(tripId: String): AppResult<Unit> {
        trips = trips.filterNot { it.tripId == tripId }
        return AppResult.Success(Unit)
    }
}
