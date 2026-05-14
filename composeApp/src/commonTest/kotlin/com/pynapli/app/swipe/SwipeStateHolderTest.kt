package com.pynapli.app.swipe

import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.core.result.AppError
import com.pynapli.shared.network.dto.MatchDto
import com.pynapli.shared.network.dto.SwipeAction
import com.pynapli.shared.network.dto.SwipeCardDto
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
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

    @Test
    fun recoverableFailureKeepsSwipeQueuedForRetry() = runBlocking {
        val card = swipeCard(userId = "target-1")
        val repository = FakeSwipeRepository(
            deck = SwipeDeckUi(cards = listOf(card), deckSessionId = "deck-1"),
            actionResult = AppResult.Failure(AppError("NETWORK_ERROR", "Offline", recoverable = true))
        )
        val stateHolder = SwipeStateHolder(repository, actionIdFactory = { "action-1" })

        stateHolder.load()
        stateHolder.submitAction(card, SwipeAction.LIKE)

        assertEquals(1, repository.submitCount)
        assertEquals("action-1", repository.submittedActions.single().actionId)
        assertEquals("deck-1", repository.submittedActions.single().deckSessionId)
        assertEquals(1, stateHolder.state.value.pendingActionCount)
        assertNull(stateHolder.state.value.topCard)
        assertEquals("Swipe saved. We will retry when the connection is back.", stateHolder.state.value.errorMessage)

        repository.actionResult = AppResult.Success(null)
        stateHolder.retryPendingActions()

        assertEquals(2, repository.submitCount)
        assertEquals("action-1", repository.submittedActions.last().actionId)
        assertEquals(0, stateHolder.state.value.pendingActionCount)
        assertNull(stateHolder.state.value.errorMessage)
    }

    @Test
    fun nonRecoverableFailureRestoresCardAndClearsQueue() = runBlocking {
        val card = swipeCard(userId = "target-1")
        val repository = FakeSwipeRepository(
            deck = SwipeDeckUi(cards = listOf(card), deckSessionId = "deck-1"),
            actionResult = AppResult.Failure(AppError("INVALID_SWIPE_CARD_TOKEN", "Card expired.", recoverable = false))
        )
        val stateHolder = SwipeStateHolder(repository, actionIdFactory = { "action-1" })

        stateHolder.load()
        stateHolder.submitAction(card, SwipeAction.PASS)

        val state = stateHolder.state.value
        assertEquals(1, repository.submitCount)
        assertEquals(0, state.pendingActionCount)
        assertEquals(card, state.topCard)
        assertEquals("Card expired.", state.errorMessage)
    }

    @Test
    fun queueFullStopsAdditionalOptimisticActions() = runBlocking {
        val cards = (1..11).map { index -> swipeCard(userId = "target-$index") }
        val repository = FakeSwipeRepository(
            deck = SwipeDeckUi(cards = cards, deckSessionId = "deck-1"),
            actionResult = AppResult.Failure(AppError("NETWORK_ERROR", "Offline", recoverable = true))
        )
        var actionIndex = 0
        val stateHolder = SwipeStateHolder(repository, actionIdFactory = { "action-${++actionIndex}" })

        stateHolder.load()
        repeat(10) {
            stateHolder.submitAction(stateHolder.state.value.topCard ?: error("expected top card"), SwipeAction.LIKE)
        }
        stateHolder.submitAction(stateHolder.state.value.topCard ?: error("expected top card"), SwipeAction.LIKE)

        val state = stateHolder.state.value
        assertEquals(10, state.pendingActionCount)
        assertEquals("SWIPE_ACTION_QUEUE_FULL", state.errorCode)
        assertEquals(cards[10], state.topCard)
        assertEquals(10, actionIndex)
    }
}

private class FakeSwipeRepository(
    private val deck: SwipeDeckUi,
    var actionResult: AppResult<MatchDto?> = AppResult.Success(null)
) : SwipeRepository {
    var submitCount = 0
        private set
    val submittedActions = mutableListOf<QueuedSwipeAction>()

    override suspend fun loadDeck(filter: SwipeFilterType): AppResult<SwipeDeckUi> =
        AppResult.Success(deck)

    override suspend fun submitAction(action: QueuedSwipeAction): AppResult<MatchDto?> {
        submitCount += 1
        submittedActions += action
        return actionResult
    }
}

private fun swipeCard(userId: String): SwipeCardDto =
    SwipeCardDto(
        userId = userId,
        displayName = "Maya",
        rankToken = "swipe:test:$userId"
    )
