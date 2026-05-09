package com.pynapli.app.grid

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GridStateHolderTest {
    @Test
    fun loadLocksPaidFiltersWhenFeedIsAdSupported() = runBlocking {
        val repository = FakeGridFeedRepository(
            feed = GridFeedUi(cards = emptyList(), adSupported = true)
        )
        val stateHolder = GridStateHolder(repository)

        stateHolder.load()

        val filters = stateHolder.state.value.filters
        assertFalse(filters.first { it.type == GridFilterType.Nearby }.locked)
        assertTrue(filters.first { it.type == GridFilterType.Online }.locked)
        assertTrue(filters.first { it.type == GridFilterType.Linked }.locked)
        assertTrue(filters.first { it.type == GridFilterType.Age }.locked)
        assertTrue(stateHolder.state.value.adSupported)
    }

    @Test
    fun selectingLockedFilterShowsPaywallWithoutReloading() = runBlocking {
        val repository = FakeGridFeedRepository(
            feed = GridFeedUi(cards = emptyList(), adSupported = true)
        )
        val stateHolder = GridStateHolder(repository)

        stateHolder.load()
        stateHolder.selectFilter(GridFilterType.Online)

        val state = stateHolder.state.value
        assertEquals(1, repository.loadCount)
        assertEquals("Online is included with Social or Select.", state.paywallMessage)
        assertTrue(state.filters.first { it.type == GridFilterType.Nearby }.active)
    }

    @Test
    fun quietLoadKeepsCardsVisibleAndNeverFlipsRefreshing() = runBlocking {
        val firstCard = GridProfileCardUi(
            id = "first",
            displayName = "First",
            online = true,
            distanceLabel = "1 km",
            tone = GridCardTone.Warm
        )
        val secondCard = GridProfileCardUi(
            id = "second",
            displayName = "Second",
            online = false,
            distanceLabel = "2 km",
            tone = GridCardTone.Slate
        )
        val gate = CompletableDeferred<Unit>()
        val repository = GatedGridFeedRepository(
            feeds = listOf(
                GridFeedUi(cards = listOf(firstCard)),
                GridFeedUi(cards = listOf(secondCard))
            ),
            gateBeforeIndex = 1,
            gate = gate
        )
        val stateHolder = GridStateHolder(repository)

        stateHolder.load()

        coroutineScope {
            val refreshingValues = mutableListOf<Boolean>()
            val cardIdsDuringQuiet = mutableListOf<List<String>>()
            val observerJob = stateHolder.state
                .onEach {
                    refreshingValues += it.isRefreshing
                    cardIdsDuringQuiet += it.cards.map { card -> card.id }
                }
                .launchIn(this)

            val quietJob = async(Dispatchers.Default) { stateHolder.load(quiet = true) }
            // Let the quiet load enter the repository call and yield a few times.
            repeat(3) { yield() }

            assertTrue(
                refreshingValues.none { it },
                "Quiet load must never flip isRefreshing to true while cards are visible. Saw: $refreshingValues"
            )
            assertTrue(
                cardIdsDuringQuiet.all { it == listOf("first") },
                "Existing cards must remain visible during a quiet load. Saw: $cardIdsDuringQuiet"
            )

            gate.complete(Unit)
            quietJob.await()
            observerJob.cancel()
        }

        val finalState = stateHolder.state.value
        assertEquals(listOf("second"), finalState.cards.map { it.id })
        assertFalse(finalState.isRefreshing)
        assertNull(finalState.errorMessage)
    }

    @Test
    fun quietLoadFailureKeepsExistingCardsAndDoesNotSurfaceError() = runBlocking {
        val initialCard = GridProfileCardUi(
            id = "first",
            displayName = "First",
            online = true,
            distanceLabel = "1 km",
            tone = GridCardTone.Warm
        )
        val repository = ScriptedGridFeedRepository(
            outcomes = listOf(
                GridFeedOutcome.Success(GridFeedUi(cards = listOf(initialCard))),
                GridFeedOutcome.Failure(RuntimeException("network down"))
            )
        )
        val stateHolder = GridStateHolder(repository)

        stateHolder.load()
        stateHolder.load(quiet = true)

        val state = stateHolder.state.value
        assertEquals(listOf("first"), state.cards.map { it.id })
        assertNull(state.errorMessage)
        assertNull(state.errorCode)
        assertFalse(state.isRefreshing)
    }

    @Test
    fun nonQuietLoadFailurePreservesErrorSemantics() = runBlocking {
        val repository = ScriptedGridFeedRepository(
            outcomes = listOf(
                GridFeedOutcome.Failure(RuntimeException("network down"))
            )
        )
        val stateHolder = GridStateHolder(repository)

        stateHolder.load()

        val state = stateHolder.state.value
        assertTrue(state.cards.isEmpty())
        assertEquals("network down", state.errorMessage)
        assertFalse(state.isRefreshing)
    }

    @Test
    fun filterChangeKeepsCardsVisibleUntilNewFeedLands() = runBlocking {
        val firstCard = GridProfileCardUi(
            id = "first",
            displayName = "First",
            online = true,
            distanceLabel = "1 km",
            tone = GridCardTone.Warm
        )
        val secondCard = GridProfileCardUi(
            id = "second",
            displayName = "Second",
            online = false,
            distanceLabel = "2 km",
            tone = GridCardTone.Slate
        )
        val gate = CompletableDeferred<Unit>()
        val repository = GatedGridFeedRepository(
            feeds = listOf(
                GridFeedUi(cards = listOf(firstCard), adSupported = false),
                GridFeedUi(cards = listOf(secondCard), adSupported = false)
            ),
            gateBeforeIndex = 1,
            gate = gate
        )
        val stateHolder = GridStateHolder(repository)

        stateHolder.load()

        coroutineScope {
            val cardIdsDuringSelect = mutableListOf<List<String>>()
            val observerJob = stateHolder.state
                .onEach { cardIdsDuringSelect += it.cards.map { card -> card.id } }
                .launchIn(this)

            val selectJob = async(Dispatchers.Default) { stateHolder.selectFilter(GridFilterType.Online) }
            repeat(3) { yield() }

            assertTrue(
                cardIdsDuringSelect.all { it == listOf("first") },
                "Filter change must not clear visible cards before the new feed lands. Saw: $cardIdsDuringSelect"
            )

            gate.complete(Unit)
            selectJob.await()
            observerJob.cancel()
        }

        val finalState = stateHolder.state.value
        assertEquals(listOf("second"), finalState.cards.map { it.id })
        assertTrue(finalState.filters.first { it.type == GridFilterType.Online }.active)
        assertFalse(finalState.isRefreshing)
    }

    @Test
    fun loadMoreAppendsUniqueCardsAndAdvancesCursor() = runBlocking {
        val first = GridProfileCardUi(
            id = "first",
            displayName = "First",
            online = true,
            distanceLabel = "1 km",
            tone = GridCardTone.Warm
        )
        val duplicateFirst = first.copy(displayName = "First duplicate")
        val second = GridProfileCardUi(
            id = "second",
            displayName = "Second",
            online = false,
            distanceLabel = "2 km",
            tone = GridCardTone.Slate
        )
        val repository = FakeGridFeedRepository(
            feeds = listOf(
                GridFeedUi(cards = listOf(first), nextCursor = "cursor-1"),
                GridFeedUi(cards = listOf(duplicateFirst, second), nextCursor = "cursor-2")
            )
        )
        val stateHolder = GridStateHolder(repository)

        stateHolder.load()
        stateHolder.loadMore()

        val state = stateHolder.state.value
        assertEquals(listOf("first", "second"), state.cards.map { it.id })
        assertEquals("cursor-2", state.nextCursor)
        assertEquals(listOf(null, "cursor-1"), repository.receivedCursors)
    }
}

private class FakeGridFeedRepository(
    private val feeds: List<GridFeedUi>
) : GridFeedRepository {
    constructor(feed: GridFeedUi) : this(listOf(feed))

    var loadCount = 0
        private set
    val receivedCursors = mutableListOf<String?>()

    override suspend fun loadGrid(filter: GridFilterType, cursor: String?): GridFeedUi {
        receivedCursors += cursor
        loadCount += 1
        return feeds.getOrElse(loadCount - 1) { feeds.last() }
    }
}

private class GatedGridFeedRepository(
    private val feeds: List<GridFeedUi>,
    private val gateBeforeIndex: Int,
    private val gate: CompletableDeferred<Unit>
) : GridFeedRepository {
    private var loadCount = 0

    override suspend fun loadGrid(filter: GridFilterType, cursor: String?): GridFeedUi {
        val index = loadCount
        loadCount += 1
        if (index >= gateBeforeIndex) {
            gate.await()
        }
        return feeds.getOrElse(index) { feeds.last() }
    }
}

private sealed class GridFeedOutcome {
    data class Success(val feed: GridFeedUi) : GridFeedOutcome()
    data class Failure(val error: Throwable) : GridFeedOutcome()
}

private class ScriptedGridFeedRepository(
    private val outcomes: List<GridFeedOutcome>
) : GridFeedRepository {
    private var loadCount = 0

    override suspend fun loadGrid(filter: GridFilterType, cursor: String?): GridFeedUi {
        val outcome = outcomes.getOrElse(loadCount) { outcomes.last() }
        loadCount += 1
        return when (outcome) {
            is GridFeedOutcome.Success -> outcome.feed
            is GridFeedOutcome.Failure -> throw outcome.error
        }
    }
}
