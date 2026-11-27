package com.pynapli.app.location

import com.pynapli.app.grid.GridFeedRepository
import com.pynapli.app.grid.GridFeedUi
import com.pynapli.app.grid.GridFilters
import com.pynapli.app.grid.GridStateHolder
import com.pynapli.app.swipe.QueuedSwipeAction
import com.pynapli.app.swipe.SwipeDeckUi
import com.pynapli.app.swipe.SwipeRepository
import com.pynapli.app.swipe.SwipeRewindUi
import com.pynapli.app.swipe.SwipeStateHolder
import com.pynapli.shared.core.result.AppError
import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.network.dto.MatchDto
import com.pynapli.shared.network.dto.SetTravelLocationRequestDto
import com.pynapli.shared.network.dto.SwipeDeckRequestDto
import com.pynapli.shared.network.dto.TravelLocationDto
import com.pynapli.shared.network.dto.TravelPlaceDto
import com.pynapli.shared.network.dto.TravelPlaceSearchResponseDto
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * Travel mode covers the Grid and the Swipe deck: switching it from either screen (here, the Grid
 * header) leaves both stale, wired as PynapliCommonGraph wires it.
 */
class TravelChangeFanOutTest {
    private val vegas =
        TravelPlaceDto(
            displayName = "Las Vegas, Nevada, US",
            city = "Las Vegas",
            region = "Nevada",
            country = "US",
            latitude = 36.17497,
            longitude = -115.13722,
        )

    private fun wired(travelRepository: TravelLocationRepository): Triple<TravelLocationStateHolder, SwipeStateHolder, GridStateHolder> {
        val swipe = SwipeStateHolder(EmptySwipeRepository())
        val grid = GridStateHolder(EmptyGridRepository())
        val travel =
            TravelLocationStateHolder(travelRepository) {
                swipe.markStale()
                grid.invalidate()
            }
        return Triple(travel, swipe, grid)
    }

    @Test
    fun switchingTravelOnFromTheGridMarksTheSwipeDeckStaleAndTheSwipeReloadClearsIt() =
        runTest {
            val (travel, swipe, grid) = wired(TravelRepository(setOk = true))
            swipe.load()
            grid.load()
            assertFalse(swipe.deckStale.value)
            assertFalse(grid.resultsOlderThan(15.minutes))

            assertTrue(travel.setActive(vegas))

            assertTrue(swipe.deckStale.value, "the Swipe screen reloads on seeing this")
            assertTrue(grid.resultsOlderThan(15.minutes), "the Grid refetches on its next showing")
            swipe.load()
            assertFalse(swipe.deckStale.value)
        }

    @Test
    fun switchingTravelOffAlsoMarksSwipeStale() =
        runTest {
            val (travel, swipe, _) = wired(TravelRepository(setOk = true))
            swipe.load()
            travel.clear()
            assertTrue(swipe.deckStale.value)
        }

    @Test
    fun aRefusedSwitchChangesNothing() =
        runTest {
            val (travel, swipe, _) = wired(TravelRepository(setOk = false))
            swipe.load()
            assertFalse(travel.setActive(vegas))
            assertFalse(swipe.deckStale.value)
        }
}

private class TravelRepository(
    private val setOk: Boolean,
) : TravelLocationRepository {
    override suspend fun getCurrent(): AppResult<TravelLocationDto?> = AppResult.Success(null)

    override suspend fun setActive(request: SetTravelLocationRequestDto): AppResult<TravelLocationDto> =
        if (setOk) {
            AppResult.Success(TravelLocationDto(city = request.city, latitude = request.latitude, longitude = request.longitude, activeFrom = "2026-11-25T00:00:00Z", status = "active"))
        } else {
            AppResult.Failure(AppError("ENTITLEMENT_REQUIRED", "Select unlocks Travel."))
        }

    override suspend fun clear(): AppResult<Unit> = AppResult.Success(Unit)

    override suspend fun searchPlaces(query: String): AppResult<TravelPlaceSearchResponseDto> = AppResult.Success(TravelPlaceSearchResponseDto(items = emptyList()))
}

private class EmptyGridRepository : GridFeedRepository {
    override suspend fun loadGrid(
        filters: GridFilters,
        cursor: String?,
    ): GridFeedUi = GridFeedUi(cards = emptyList())
}

private class EmptySwipeRepository : SwipeRepository {
    override suspend fun loadDeck(request: SwipeDeckRequestDto): AppResult<SwipeDeckUi> = AppResult.Success(SwipeDeckUi(cards = emptyList()))

    override suspend fun submitAction(action: QueuedSwipeAction): AppResult<MatchDto?> = AppResult.Success(null)

    override suspend fun rewind(): AppResult<SwipeRewindUi?> = AppResult.Success(null)
}
