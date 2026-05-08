package com.pynapli.app.grid

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
