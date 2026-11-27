package com.pynapli.app.profile

import com.pynapli.app.grid.GridProfileCardUi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProfilePagerControllerTest {
    @Test
    fun thePagerStartsOnTheTappedCardAndDoesNotFetchItTwice() {
        val controller = ProfilePagerController()
        val live = cards("a", "b", "c", "d")

        val model = assertNotNull(controller.reconcile(live, live[2]))

        assertEquals(2, model.currentIndex)
        assertEquals(live.map { it.id }, model.pages.map { it.id })
        // The tap already opened c: its first settle must not open (and record a view of) it again.
        assertNull(controller.onPageSettled(2, hasMore = false, isLoadingMore = false).open)
    }

    @Test
    fun aSettledPageOpensItsProfileOnceAndPassingPagesOpenNothing() {
        val controller = ProfilePagerController()
        val live = cards("a", "b", "c", "d")
        controller.reconcile(live, live[0])

        // Only settles reach the controller; pages composed mid-swipe never do.
        assertEquals("c", controller.onPageSettled(2, hasMore = false, isLoadingMore = false).open?.id)
        assertNull(controller.onPageSettled(2, hasMore = false, isLoadingMore = false).open, "same settle twice")

        // The open lands: the sheet's selection is now c, still no second open.
        controller.reconcile(live, live[2])
        assertNull(controller.onPageSettled(2, hasMore = false, isLoadingMore = false).open)

        // Swiping back to a once-seen page is a new settle and opens it again.
        assertEquals("a", controller.onPageSettled(0, hasMore = false, isLoadingMore = false).open?.id)
    }

    @Test
    fun settlingNearTheEndLoadsTheNextPageOncePerListSize() {
        val controller = ProfilePagerController()
        val first = cards(*Array(10) { "u$it" })
        controller.reconcile(first, first[0])

        assertFalse(controller.onPageSettled(5, hasMore = true, isLoadingMore = false).loadMore, "not near the end yet")
        assertTrue(controller.onPageSettled(7, hasMore = true, isLoadingMore = false).loadMore)
        assertFalse(controller.onPageSettled(8, hasMore = true, isLoadingMore = false).loadMore, "already asked at this size")

        val second = cards(*Array(20) { "u$it" })
        controller.reconcile(second, second[8])
        assertFalse(controller.onPageSettled(17, hasMore = true, isLoadingMore = true).loadMore, "a load is in flight")
        assertTrue(controller.onPageSettled(17, hasMore = true, isLoadingMore = false).loadMore)

        val last = cards(*Array(25) { "u$it" })
        controller.reconcile(last, last[17])
        assertFalse(controller.onPageSettled(24, hasMore = false, isLoadingMore = false).loadMore, "no more pages")
    }

    @Test
    fun blockingTheOpenPersonMovesOnToTheNextCard() {
        val controller = ProfilePagerController()
        val live = cards("a", "b", "c")
        controller.reconcile(live, live[1])

        // The grid has already dropped b (the App-level removal can win the race), and b is marked.
        val afterBlock = assertNotNull(controller.reconcile(cards("a", "c"), live[1], removed = setOf("b")))

        assertEquals("c", afterBlock.replacement?.id)
        assertEquals(1, afterBlock.currentIndex)
        assertEquals(listOf("a", "c"), afterBlock.pages.map { it.id })
        assertFalse(afterBlock.closeRequested)

        // The host opens c; the pager carries on from there.
        val next = assertNotNull(controller.reconcile(cards("a", "c"), afterBlock.replacement, removed = setOf("b")))
        assertNull(next.replacement)
        assertEquals(1, next.currentIndex)
    }

    @Test
    fun blockingTheLastCardMovesBackAndBlockingTheOnlyCardCloses() {
        val controller = ProfilePagerController()
        val live = cards("a", "b")
        controller.reconcile(live, live[1])
        val back = assertNotNull(controller.reconcile(cards("a"), live[1], removed = setOf("b")))
        assertEquals("a", back.replacement?.id)

        val lone = ProfilePagerController()
        val one = cards("z")
        lone.reconcile(one, one[0])
        val closed = assertNotNull(lone.reconcile(emptyList(), one[0], removed = setOf("z")))
        assertTrue(closed.closeRequested)
        assertNull(closed.replacement)
    }

    @Test
    fun theOpenCardLeavingTheListForAnotherReasonStaysOnScreen() {
        val controller = ProfilePagerController()
        val live = cards("a", "b", "c")
        controller.reconcile(live, live[1])

        // Un-starred in Favorites (or dropped by a quiet refresh): no jump to someone else.
        val kept = assertNotNull(controller.reconcile(cards("a", "c"), live[1]))

        assertNull(kept.replacement)
        assertEquals(1, kept.currentIndex)
        assertEquals(listOf("a", "b", "c"), kept.pages.map { it.id })
    }

    @Test
    fun aProfileFromOutsideTheListIsNotPaged() {
        val controller = ProfilePagerController()
        val live = cards("a", "b")
        controller.reconcile(live, live[0])

        assertNull(controller.reconcile(live, card("partner-of-a")), "a linked partner opens in the single sheet")
        assertNull(controller.reconcile(live, null))
    }

    @Test
    fun aLockedPageNeverOpensAProfile() {
        val controller = ProfilePagerController(isLocked = { it.id == "c" })
        val live = cards("a", "b", "c")
        controller.reconcile(live, live[0])

        assertNull(controller.onPageSettled(2, hasMore = false, isLoadingMore = false).open)
        assertEquals("b", controller.onPageSettled(1, hasMore = false, isLoadingMore = false).open?.id)
    }

    @Test
    fun thePagerEndsOnOneLockedPageAndAsksForNoMoreLocks() {
        // The default seam: the server's locked flag (and the "locked_" token prefix).
        val controller = ProfilePagerController()
        val live = cards("a", "b") + lockedCards("locked_1", "locked_2", "locked_3")

        val model = assertNotNull(controller.reconcile(live, live[1]))

        assertEquals(listOf("a", "b", "locked_1"), model.pages.map { it.id }, "one See plans page, not hundreds")
        // Swiping from the last person onto the locked page: no profile, and no next page of locks.
        val effects = controller.onPageSettled(2, hasMore = true, isLoadingMore = false)
        assertNull(effects.open)
        assertFalse(effects.loadMore)
    }

    @Test
    fun beforeAnyLockIsLoadedTheLastPersonStillAsksForTheNextPage() {
        val controller = ProfilePagerController()
        val live = cards("a", "b")
        controller.reconcile(live, live[0])

        assertTrue(controller.onPageSettled(1, hasMore = true, isLoadingMore = false).loadMore)
    }

    @Test
    fun blockingTheLastUnlockedPersonMovesBackNeverOntoTheLockedPage() {
        val controller = ProfilePagerController()
        val live = cards("a", "b") + lockedCards("locked_1")
        controller.reconcile(live, live[1])

        val model = assertNotNull(controller.reconcile(live.filterNot { it.id == "b" }, live[1], removed = setOf("b")))

        assertEquals("a", model.replacement?.id, "the previous person, not a locked token to fetch")
        assertFalse(model.closeRequested)
    }

    @Test
    fun blockingTheOnlyUnlockedPersonClosesThePager() {
        val controller = ProfilePagerController()
        val live = cards("a") + lockedCards("locked_1")
        controller.reconcile(live, live[0])

        val model = assertNotNull(controller.reconcile(live.drop(1), live[0], removed = setOf("a")))

        assertTrue(model.closeRequested)
        assertNull(model.replacement)
    }

    private fun lockedCards(vararg ids: String): List<GridProfileCardUi> = ids.map { card(it).copy(displayName = "", locked = true) }

    private fun cards(vararg ids: String): List<GridProfileCardUi> = ids.map(::card)

    private fun card(id: String): GridProfileCardUi =
        GridProfileCardUi(
            id = id,
            displayName = "User $id",
            online = false,
            distanceLabel = null,
        )
}
