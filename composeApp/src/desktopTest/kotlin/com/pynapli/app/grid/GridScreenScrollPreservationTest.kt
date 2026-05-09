package com.pynapli.app.grid

import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import com.pynapli.app.profile.ProfileDetailStateHolder
import com.pynapli.app.profile.SamplePublicProfileRepository
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

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
}

private class StaticGridFeedRepository(
    private val cards: List<GridProfileCardUi>
) : GridFeedRepository {
    override suspend fun loadGrid(filter: GridFilterType, cursor: String?): GridFeedUi =
        GridFeedUi(cards = cards)
}
