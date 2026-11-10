package com.pynapli.app.grid

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.pynapli.app.conversation.ConversationStateHolder
import com.pynapli.app.conversation.SampleConversationRepository
import com.pynapli.app.profile.ProfileDetailStateHolder
import com.pynapli.app.profile.SamplePublicProfileRepository
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GridBodyStatesTest {
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun errorStateShowsRetryAndReloads() =
        runComposeUiTest {
            val repository = ThrowingGridFeedRepository()
            val gridStateHolder = GridStateHolder(repository)

            setContent {
                GridScreen(
                    stateHolder = gridStateHolder,
                    profileDetailStateHolder = ProfileDetailStateHolder(SamplePublicProfileRepository()),
                    conversationStateHolder = ConversationStateHolder(SampleConversationRepository()),
                )
            }
            waitForIdle()
            waitUntil(timeoutMillis = 5_000) { gridStateHolder.state.value.errorMessage != null }
            waitForIdle()

            onNodeWithText("Couldn't load nearby profiles").assertExists()
            onNodeWithText("Retry").assertExists()

            val callsBefore = repository.loadCount
            onNodeWithText("Retry").performClick()
            waitUntil(timeoutMillis = 5_000) { repository.loadCount > callsBefore }
        }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun emptyFilteredStateShowsClearFilters() =
        runComposeUiTest {
            val gridStateHolder = GridStateHolder(EmptyGridFeedRepository())

            setContent {
                GridScreen(
                    stateHolder = gridStateHolder,
                    profileDetailStateHolder = ProfileDetailStateHolder(SamplePublicProfileRepository()),
                    conversationStateHolder = ConversationStateHolder(SampleConversationRepository()),
                )
            }
            waitForIdle()
            // Activate a filter so the empty result reads as "no one matches these filters".
            runBlocking { gridStateHolder.toggleQuickFilter(QuickFilterId.Online) }
            waitUntil(timeoutMillis = 5_000) { gridStateHolder.state.value.filters.isAnyActive }
            waitForIdle()

            onNodeWithText("No one matches these filters right now.").assertExists()
            onNodeWithText("Clear filters").performClick()
            waitUntil(timeoutMillis = 5_000) {
                !gridStateHolder.state.value.filters.isAnyActive
            }
            assertFalse(gridStateHolder.state.value.filters.isAnyActive)
            assertTrue(
                gridStateHolder.state.value.cards
                    .isEmpty(),
            )
        }
}

private class ThrowingGridFeedRepository : GridFeedRepository {
    var loadCount = 0
        private set

    override suspend fun loadGrid(
        filters: GridFilters,
        cursor: String?,
    ): GridFeedUi {
        loadCount += 1
        throw RuntimeException("Grid could not be loaded.")
    }
}

private class EmptyGridFeedRepository : GridFeedRepository {
    override suspend fun loadGrid(
        filters: GridFilters,
        cursor: String?,
    ): GridFeedUi = GridFeedUi(cards = emptyList(), adSupported = false)
}
