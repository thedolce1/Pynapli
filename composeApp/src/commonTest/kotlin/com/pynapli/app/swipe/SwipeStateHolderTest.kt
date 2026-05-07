package com.pynapli.app.swipe

import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.network.dto.MatchDto
import com.pynapli.shared.network.dto.SwipeCardDto
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SwipeStateHolderTest {
    @Test
    fun loadLocksPaidFiltersWhenDeckIsAdSupported() = runBlocking {
        val repository = FakeSwipeRepository(
            deck = SwipeDeckUi(cards = emptyList(), adSupported = true)
        )
        val stateHolder = SwipeStateHolder(repository)

        stateHolder.load()

        val filters = stateHolder.state.value.filters
        assertFalse(filters.first { it.type == SwipeFilterType.ForYou }.locked)
        assertTrue(filters.first { it.type == SwipeFilterType.Linked }.locked)
        assertTrue(filters.first { it.type == SwipeFilterType.Age }.locked)
        assertTrue(stateHolder.state.value.adSupported)
    }

    @Test
    fun introNoteValidationStopsOverLimitRequest() = runBlocking {
        val card = swipeCard(userId = "target-1")
        val repository = FakeSwipeRepository(
            deck = SwipeDeckUi(cards = listOf(card), adSupported = false)
        )
        val stateHolder = SwipeStateHolder(repository)

        stateHolder.load()
        stateHolder.submitIntroNote(card, "x".repeat(141))

        val state = stateHolder.state.value
        assertEquals(0, repository.submitCount)
        assertEquals("Intro note must be 140 characters or less.", state.errorMessage)
        assertEquals(card, state.topCard)
    }
}

private class FakeSwipeRepository(
    private val deck: SwipeDeckUi
) : SwipeRepository {
    var submitCount = 0
        private set

    override suspend fun loadDeck(filter: SwipeFilterType): AppResult<SwipeDeckUi> =
        AppResult.Success(deck)

    override suspend fun submitAction(
        targetUserId: String,
        action: String,
        noteText: String?
    ): AppResult<MatchDto?> {
        submitCount += 1
        return AppResult.Success(null)
    }
}

private fun swipeCard(userId: String): SwipeCardDto =
    SwipeCardDto(
        userId = userId,
        displayName = "Maya",
        rankToken = "swipe:test:$userId"
    )
