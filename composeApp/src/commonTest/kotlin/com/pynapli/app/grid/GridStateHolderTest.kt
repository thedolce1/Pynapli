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
}

private class FakeGridFeedRepository(
    private val feed: GridFeedUi
) : GridFeedRepository {
    var loadCount = 0
        private set

    override suspend fun loadGrid(filter: GridFilterType): GridFeedUi {
        loadCount += 1
        return feed
    }
}
