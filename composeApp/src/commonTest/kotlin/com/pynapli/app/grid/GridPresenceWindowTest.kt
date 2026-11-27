package com.pynapli.app.grid

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** G3-A: the presence subscription follows the cards on screen, settled, and never moves the Grid. */
class GridPresenceWindowTest {
    @Test
    fun beforeTheGridLaysOutItWatchesFromTheTop() {
        val cards = presenceWindowCards(5)
        assertEquals(listOf("p0", "p1", "p2", "p3", "p4"), presenceWindowIds(cards, visibleCards = null).toList())
    }

    @Test
    fun theWindowIsTheVisibleCardsThenBelowThenAboveClamped() {
        val cards = presenceWindowCards(20)

        val ids = presenceWindowIds(cards, visibleCards = 5..7, margin = 3, limit = 9).toList()

        assertEquals(listOf("p5", "p6", "p7", "p8", "p9", "p10", "p4", "p3", "p2"), ids)
        assertEquals(listOf("p18", "p19", "p17", "p16"), presenceWindowIds(cards, visibleCards = 18..40, margin = 2, limit = 4).toList())
        assertEquals(listOf("p0", "p1", "p2"), presenceWindowIds(cards, visibleCards = 0..0, margin = 2, limit = 3).toList())
    }

    @Test
    fun pastTheMarginItFillsTheCapOutwardFromTheScreen() {
        val ids = presenceWindowIds(presenceWindowCards(20), visibleCards = 10..10, margin = 1, limit = 6).toList()
        assertEquals(listOf("p10", "p11", "p9", "p12", "p8", "p13"), ids)
    }

    @Test
    fun aListWithinTheCapIsWatchedWholeWhereverTheViewerIs() {
        val cards = presenceWindowCards(150)
        val everyone = cards.map { it.id }.toSet()

        assertEquals(everyone, presenceWindowIds(cards, visibleCards = null))
        assertEquals(everyone, presenceWindowIds(cards, visibleCards = 0..20), "card 140 stays live with the viewer at the top")
        assertEquals(everyone, presenceWindowIds(cards, visibleCards = 130..149))
    }

    @Test
    fun lockedCardsInTheWindowNeverSubscribe() {
        val cards = presenceWindowCards(4) + presenceWindowLocked("locked_a") + presenceWindowCards(4, from = 4)
        val ids = presenceWindowIds(cards, visibleCards = 3..5, margin = 1, limit = 4)
        assertTrue(ids.none { it.startsWith(LOCKED_CARD_ID_PREFIX) })
        assertEquals(setOf("p3", "p4", "p5", "p2"), ids)
    }

    @Test
    fun aViewerPastCard200GetsLiveDotsForTheCardsOnScreen() =
        runTest {
            val repository = PresenceWindowRepository(presenceWindowCards(300))
            val holder = GridStateHolder(repository)
            holder.load()
            val emissions = mutableListOf<Set<String>>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { holder.visibleUserIds.collect { emissions += it } }

            assertTrue("p0" in emissions.last(), "from the top before the grid reports a range")

            holder.onGridItemsVisible(240, 260)
            advanceTimeBy(GridStateHolder.PRESENCE_WINDOW_SETTLE.inWholeMilliseconds - 1)
            runCurrent()
            assertTrue("p0" in emissions.last(), "not followed until the range settles")

            advanceTimeBy(2)
            runCurrent()
            val watched = emissions.last()
            assertEquals((240..260).map { "p$it" }, watched.take(21), "the cards on screen come first")
            assertTrue("p210" in watched && "p290" in watched, "plus the margin either side")
            assertEquals(PRESENCE_SUBSCRIPTION_CAP, watched.size, "then outward until the cap is spent")
            assertTrue("p299" in watched && "p100" in watched)
            assertFalse("p0" in watched || "p99" in watched, "the top of the list is the farthest from the screen")
            val subscription = presenceSubscription(favorites = linkedSetOf("fav"), grid = watched)
            assertEquals("fav", subscription.first(), "favourites still go first")
            assertTrue(subscription.size <= PRESENCE_SUBSCRIPTION_CAP)
        }

    @Test
    fun aFlingSettlesOnceOnTheLastRange() =
        runTest {
            val holder = GridStateHolder(PresenceWindowRepository(presenceWindowCards(300)))
            holder.load()
            val emissions = mutableListOf<Set<String>>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { holder.visibleUserIds.collect { emissions += it } }
            val before = emissions.size

            listOf(30, 60, 90, 120, 150).forEach { first ->
                holder.onGridItemsVisible(first, first + 20)
                advanceTimeBy(100)
                runCurrent()
            }
            assertEquals(before, emissions.size, "no subscription change while the list is still moving")

            advanceTimeBy(GridStateHolder.PRESENCE_WINDOW_SETTLE.inWholeMilliseconds)
            runCurrent()
            assertEquals(before + 1, emissions.size, "one change once it stopped")
            assertEquals("p150", emissions.last().first())
        }

    @Test
    fun followingTheScreenNeverRefetchesOrReordersTheGrid() =
        runTest {
            val repository = PresenceWindowRepository(presenceWindowCards(300))
            val holder = GridStateHolder(repository)
            holder.load()
            holder.rememberScroll(firstVisibleItemIndex = 240, firstVisibleItemScrollOffset = 17)
            val shown = holder.state.value
            val calls = repository.calls
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { holder.visibleUserIds.collect { } }

            holder.onGridItemsVisible(240, 260)
            advanceTimeBy(GridStateHolder.PRESENCE_WINDOW_SETTLE.inWholeMilliseconds + 1)
            runCurrent()

            assertEquals(calls, repository.calls, "no request")
            assertEquals(shown, holder.state.value, "same cards, same order, same state")
            assertEquals(GridScrollAnchor(240, 17), holder.scrollAnchor, "the profile open/close anchor is untouched")
        }

    @Test
    fun itemsPastTheLockedBannerMapBackToTheirCards() =
        runTest {
            val cards = presenceWindowCards(10) + presenceWindowLocked("locked_x") + presenceWindowLocked("locked_y")
            val holder = GridStateHolder(PresenceWindowRepository(cards))
            holder.load()
            val emissions = mutableListOf<Set<String>>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { holder.visibleUserIds.collect { emissions += it } }

            // Item 10 is the banner (before card 10); items 11 and 12 are the two locked cards.
            holder.onGridItemsVisible(9, 12)
            advanceTimeBy(GridStateHolder.PRESENCE_WINDOW_SETTLE.inWholeMilliseconds + 1)
            runCurrent()

            assertEquals("p9", emissions.last().first())
            assertTrue(emissions.last().none { it.startsWith(LOCKED_CARD_ID_PREFIX) })
        }
}

private fun presenceWindowCards(
    count: Int,
    from: Int = 0,
): List<GridProfileCardUi> = (from until from + count).map { GridProfileCardUi(id = "p$it", displayName = "p$it", online = false, distanceLabel = null) }

private fun presenceWindowLocked(token: String) = GridProfileCardUi(id = token, displayName = "", online = false, distanceLabel = null, locked = true)

private class PresenceWindowRepository(
    private val cards: List<GridProfileCardUi>,
) : GridFeedRepository {
    var calls = 0
        private set

    override suspend fun loadGrid(
        filters: GridFilters,
        cursor: String?,
    ): GridFeedUi {
        calls += 1
        return GridFeedUi(cards = cards, nextCursor = null)
    }
}
