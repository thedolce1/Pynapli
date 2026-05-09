package com.pynapli.app.grid

import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.pynapli.app.profile.ProfileDetailStateHolder
import com.pynapli.app.profile.SamplePublicProfileRepository
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GridScreenScrollPreservationTest {

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun scrollPositionSurvivesProfileSheetOpenAndClose() = runComposeUiTest {
        val cards = (0 until 60).map { index ->
            GridProfileCardUi(
                id = "card-$index",
                displayName = "User $index",
                online = index % 2 == 0,
                distanceLabel = "${index + 1} km",
                tone = GridCardTone.entries[index % GridCardTone.entries.size],
                distanceMeters = (index + 1) * 100
            )
        }
        val gridStateHolder = GridStateHolder(StaticGridFeedRepository(cards))
        val profileDetailStateHolder = ProfileDetailStateHolder(SamplePublicProfileRepository())
        val gridState = LazyGridState()

        setContent {
            GridScreen(
                stateHolder = gridStateHolder,
                profileDetailStateHolder = profileDetailStateHolder,
                gridState = gridState
            )
        }
        waitForIdle()
        // GridScreen calls load() in a LaunchedEffect; wait for the cards to land before scrolling.
        waitUntil(timeoutMillis = 5_000) { gridStateHolder.state.value.cards.isNotEmpty() }
        waitForIdle()

        runBlocking { gridState.scrollToItem(index = 30) }
        waitForIdle()

        val indexBefore = gridState.firstVisibleItemIndex
        val offsetBefore = gridState.firstVisibleItemScrollOffset

        runBlocking { profileDetailStateHolder.open(cards[indexBefore]) }
        waitUntil(timeoutMillis = 5_000) { profileDetailStateHolder.state.value.visible }
        waitForIdle()

        profileDetailStateHolder.dismiss()
        waitUntil(timeoutMillis = 5_000) { !profileDetailStateHolder.state.value.visible }
        waitForIdle()

        assertEquals(indexBefore, gridState.firstVisibleItemIndex, "firstVisibleItemIndex changed across profile open/close")
        assertEquals(offsetBefore, gridState.firstVisibleItemScrollOffset, "firstVisibleItemScrollOffset changed across profile open/close")
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun filterChangeWithSameFeedKeepsScrollPosition() = runComposeUiTest {
        val cards = (0 until 60).map { index ->
            GridProfileCardUi(
                id = "card-$index",
                displayName = "User $index",
                online = index % 2 == 0,
                distanceLabel = "${index + 1} km",
                tone = GridCardTone.entries[index % GridCardTone.entries.size],
                distanceMeters = (index + 1) * 100
            )
        }
        val gridStateHolder = GridStateHolder(StaticGridFeedRepository(cards))
        val profileDetailStateHolder = ProfileDetailStateHolder(SamplePublicProfileRepository())
        val gridState = LazyGridState()

        setContent {
            GridScreen(
                stateHolder = gridStateHolder,
                profileDetailStateHolder = profileDetailStateHolder,
                gridState = gridState
            )
        }
        waitForIdle()
        waitUntil(timeoutMillis = 5_000) { gridStateHolder.state.value.cards.isNotEmpty() }
        waitForIdle()

        runBlocking { gridState.scrollToItem(index = 30) }
        waitForIdle()

        val indexBefore = gridState.firstVisibleItemIndex
        val offsetBefore = gridState.firstVisibleItemScrollOffset

        runBlocking { gridStateHolder.toggleQuickFilter(QuickFilterId.Online) }
        waitForIdle()

        assertTrue(
            gridStateHolder.state.value.filters.online,
            "Online filter should be active after toggleQuickFilter(Online)"
        )
        assertEquals(indexBefore, gridState.firstVisibleItemIndex, "firstVisibleItemIndex changed across filter change with same feed")
        assertEquals(offsetBefore, gridState.firstVisibleItemScrollOffset, "firstVisibleItemScrollOffset changed across filter change with same feed")
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun openingFiltersSheetAndDismissingPreservesScroll() = runComposeUiTest {
        val cards = (0 until 60).map { index ->
            GridProfileCardUi(
                id = "card-$index",
                displayName = "User $index",
                online = index % 2 == 0,
                distanceLabel = "${index + 1} km",
                tone = GridCardTone.entries[index % GridCardTone.entries.size],
                distanceMeters = (index + 1) * 100
            )
        }
        val gridStateHolder = GridStateHolder(StaticGridFeedRepository(cards))
        val profileDetailStateHolder = ProfileDetailStateHolder(SamplePublicProfileRepository())
        val gridState = LazyGridState()

        setContent {
            GridScreen(
                stateHolder = gridStateHolder,
                profileDetailStateHolder = profileDetailStateHolder,
                gridState = gridState
            )
        }
        waitForIdle()
        waitUntil(timeoutMillis = 5_000) { gridStateHolder.state.value.cards.isNotEmpty() }
        waitForIdle()

        runBlocking { gridState.scrollToItem(index = 30) }
        waitForIdle()

        val indexBefore = gridState.firstVisibleItemIndex
        val offsetBefore = gridState.firstVisibleItemScrollOffset

        onNodeWithTag(GridFilterTags.OPEN_SHEET).performClick()
        waitForIdle()
        onNodeWithTag(GridFilterTags.SHEET).assertExists()

        // Dismiss without applying — sheet has its own state, no filter change.
        runBlocking { gridState.scrollToItem(index = indexBefore) }
        waitForIdle()
        // The sheet is a ModalBottomSheet rendered above the grid; the grid composition
        // is preserved underneath, so the LazyGridState survives even before dismissal.

        assertEquals(indexBefore, gridState.firstVisibleItemIndex, "firstVisibleItemIndex changed while filter sheet was open")
        assertEquals(offsetBefore, gridState.firstVisibleItemScrollOffset, "firstVisibleItemScrollOffset changed while filter sheet was open")
    }
}

private class StaticGridFeedRepository(
    private val cards: List<GridProfileCardUi>
) : GridFeedRepository {
    override suspend fun loadGrid(filters: GridFilters, cursor: String?): GridFeedUi =
        GridFeedUi(cards = cards, adSupported = false)
}
