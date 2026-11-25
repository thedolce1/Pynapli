package com.pynapli.app.grid

import com.pynapli.shared.network.dto.PresenceDeltaDto
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

/** Plan G7: the Grid stays live without a card ever moving under the finger. */
class GridLivenessTest {
    @Test
    fun aQuietRefreshUpdatesPresenceInPlaceAndHoldsNewPeopleBehindThePill() =
        runTest {
            val repository = SwitchableGridFeedRepository(feed(card("a"), card("b"), card("c")))
            val holder = GridStateHolder(repository)
            holder.load()

            repository.feed =
                feed(
                    card("d"),
                    card("b").copy(online = true, activeAgo = GridActiveAgo.ONLINE),
                    card("a").copy(activeAgo = GridActiveAgo.HOUR_1, fresh = true),
                )
            holder.refreshLive()

            val shown = holder.state.value
            assertEquals(listOf("a", "b", "c"), shown.cards.map { it.id }, "the shown order never changes on its own")
            assertTrue(shown.cards[1].online, "b's dot turns green in place")
            assertEquals(GridActiveAgo.HOUR_1, shown.cards[0].activeAgo)
            assertTrue(shown.cards[0].fresh)
            assertEquals(1, shown.newNearbyCount, "d is new nearby")
            assertFalse(shown.isRefreshing, "no spinner for a quiet refresh")

            assertTrue(holder.applyNewNearby())
            assertEquals(listOf("d", "b", "a"), holder.state.value.cards.map { it.id })
            assertEquals(0, holder.state.value.newNearbyCount)
            assertFalse(holder.applyNewNearby(), "nothing left to apply")
        }

    @Test
    fun aQuietRefreshWithNobodyNewRaisesNoPillAndKeepsCardsMissingFromPageOne() =
        runTest {
            val repository = SwitchableGridFeedRepository(feed(card("a"), card("b")))
            val holder = GridStateHolder(repository)
            holder.load()

            // b dropped off page one (it may be on page two now): it stays on screen.
            repository.feed = feed(card("a").copy(online = true))
            holder.refreshLive()

            assertEquals(listOf("a", "b"), holder.state.value.cards.map { it.id })
            assertTrue(holder.state.value.cards[0].online)
            assertEquals(0, holder.state.value.newNearbyCount)
            assertFalse(holder.applyNewNearby())
        }

    @Test
    fun aFailedQuietRefreshIsSilent() =
        runTest {
            val repository = SwitchableGridFeedRepository(feed(card("a")))
            val holder = GridStateHolder(repository)
            holder.load()
            val before = holder.state.value

            repository.failure = GridFeedException(code = "NETWORK_ERROR", message = "No connection.")
            holder.refreshLive()

            assertEquals(before, holder.state.value, "no error, no spinner, no filter change")
        }

    @Test
    fun theLoopRefreshesEveryMinuteOnlyWhileTheGridIsOnScreenVisibleAndShowingNearby() =
        runTest {
            val repository = SwitchableGridFeedRepository(feed(card("a")))
            val holder = GridStateHolder(repository, timeSource = testScheduler.timeSource)
            holder.load()
            assertEquals(1, repository.loadCount)
            backgroundScope.launch { holder.runLiveRefresh() }
            runCurrent()

            // Not on screen yet: no ticks at all.
            advanceTimeBy(3.minutes)
            assertEquals(1, repository.loadCount)

            holder.setOnScreen(true)
            runCurrent()
            advanceTimeBy(59.seconds)
            assertEquals(1, repository.loadCount, "not before a minute")
            advanceTimeBy(2.seconds)
            assertEquals(2, repository.loadCount, "one quiet refresh after a minute")
            advanceTimeBy(60.seconds)
            assertEquals(3, repository.loadCount)

            // Browser tab hidden: paused.
            holder.setAppVisible(false)
            runCurrent()
            advanceTimeBy(5.minutes)
            assertEquals(3, repository.loadCount)
            holder.setAppVisible(true)
            runCurrent()

            // Favorites covers Nearby: paused too.
            holder.setNearbyShowing(false)
            runCurrent()
            advanceTimeBy(5.minutes)
            assertEquals(3, repository.loadCount)
            holder.setNearbyShowing(true)
            runCurrent()
            advanceTimeBy(61.seconds)
            assertEquals(4, repository.loadCount)

            // Leaving the tab stops it.
            holder.setOnScreen(false)
            runCurrent()
            advanceTimeBy(5.minutes)
            assertEquals(4, repository.loadCount)
        }

    @Test
    fun aTickRightAfterAnExplicitRefreshIsSkipped() =
        runTest {
            val repository = SwitchableGridFeedRepository(feed(card("a")))
            val holder = GridStateHolder(repository, timeSource = testScheduler.timeSource)
            holder.load()
            holder.setOnScreen(true)
            backgroundScope.launch { holder.runLiveRefresh() }
            runCurrent()

            advanceTimeBy(50.seconds)
            holder.refresh()
            assertEquals(2, repository.loadCount)
            // The tick at 60 s finds an answer only 10 s old.
            advanceTimeBy(11.seconds)
            assertEquals(2, repository.loadCount)
        }

    @Test
    fun returningRefreshesPresenceAfterAMinuteAndReplacesTheOrderOnlyAfterFifteen() =
        runTest {
            val clock = TestTimeSource()
            val repository = SwitchableGridFeedRepository(feed(card("a"), card("b")))
            val holder = GridStateHolder(repository, timeSource = clock)
            holder.load()
            repository.feed = feed(card("c"), card("b"), card("a"))

            clock += 30.seconds
            holder.refreshOnReturn()
            assertEquals(1, repository.loadCount, "fresh results are left alone")

            clock += 31.seconds
            holder.refreshOnReturn()
            assertEquals(2, repository.loadCount, "a quiet refresh after a minute away")
            assertEquals(listOf("a", "b"), holder.state.value.cards.map { it.id }, "in place, no reorder")
            assertEquals(1, holder.state.value.newNearbyCount)

            clock += 15.minutes
            holder.refreshOnReturn()
            assertEquals(3, repository.loadCount)
            assertEquals(listOf("c", "b", "a"), holder.state.value.cards.map { it.id }, "an expired order is replaced")
            assertEquals(0, holder.state.value.newNearbyCount)
        }

    @Test
    fun appResumeRefreshesOnlyTheGridOnScreen() =
        runTest {
            val clock = TestTimeSource()
            val repository = SwitchableGridFeedRepository(feed(card("a")))
            val holder = GridStateHolder(repository, timeSource = clock)
            holder.load()
            clock += 2.minutes

            holder.refreshOnResume()
            assertEquals(1, repository.loadCount, "another tab is in front; the tab return covers it")

            holder.setOnScreen(true)
            holder.refreshOnResume()
            assertEquals(2, repository.loadCount)
        }

    @Test
    fun cachedCardsShowPresenceAsUnknownUntilTheFirstLiveAnswer() =
        runTest {
            val cached = card("a").copy(online = true, activeAgo = GridActiveAgo.ONLINE, fresh = true)
            val gate = CompletableDeferred<Unit>()
            val repository = GatedOnceGridFeedRepository(feed(card("a").copy(online = true)), gate)
            val holder = GridStateHolder(repository, FakeSnapshotStore(listOf(cached)))

            val priming = async { holder.primeFromCacheThenLoad() }
            runCurrent()
            val painted = holder.state.value.cards.single()
            assertFalse(painted.online, "no green dot from a days-old cache")
            assertNull(painted.activeAgo)
            assertFalse(painted.fresh)

            gate.complete(Unit)
            priming.await()
            assertTrue(
                holder.state.value.cards
                    .single()
                    .online,
                "the live answer knows",
            )
        }

    @Test
    fun aBlockedPersonNeverReturnsWithTheNewNearbyPage() =
        runTest {
            val repository = SwitchableGridFeedRepository(feed(card("a")))
            val holder = GridStateHolder(repository)
            holder.load()
            repository.feed = feed(card("d"), card("a"))
            holder.refreshLive()
            assertEquals(1, holder.state.value.newNearbyCount)

            holder.removeCardById("d")

            assertEquals(0, holder.state.value.newNearbyCount)
            assertFalse(holder.applyNewNearby())
            assertEquals(listOf("a"), holder.state.value.cards.map { it.id })
        }

    @Test
    fun aFilterChangeOrTravelChangeDropsTheHeldBackPage() =
        runTest {
            val repository = SwitchableGridFeedRepository(feed(card("a")))
            val holder = GridStateHolder(repository)
            holder.load()
            repository.feed = feed(card("d"), card("a"))
            holder.refreshLive()
            assertEquals(1, holder.state.value.newNearbyCount)

            holder.invalidate()
            assertEquals(0, holder.state.value.newNearbyCount, "travel moved: another place's page is void")
            assertFalse(holder.applyNewNearby())

            holder.refreshLive()
            assertEquals(1, holder.state.value.newNearbyCount)
            repository.feed = feed(card("a"))
            holder.toggleQuickFilter(QuickFilterId.Online)
            assertEquals(0, holder.state.value.newNearbyCount)
        }

    @Test
    fun aQuietRefreshNeverDropsAPageBeingAppended() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val repository = PagedGridFeedRepository(feed(card("a"), cursor = "p2"), feed(card("b")), gate)
            val holder = GridStateHolder(repository)
            holder.load()

            val paging = async { holder.loadMore() }
            runCurrent()
            // Skipped while page two is on the wire, and never supersedes it.
            holder.refreshLive()
            gate.complete(Unit)
            paging.await()

            assertEquals(listOf("a", "b"), holder.state.value.cards.map { it.id })
        }

    @Test
    fun wentOfflineShowsTheNewestActiveBucket() =
        runTest {
            val holder = GridStateHolder(SwitchableGridFeedRepository(feed(card("a").copy(online = true))))
            holder.load()

            holder.applyPresenceDelta(PresenceDeltaDto(userId = "a", online = false, lastActiveAt = "2026-11-24T12:00:00Z"))
            assertEquals(GridActiveAgo.MINUTES_15, holder.state.value.cards[0].activeAgo)
            assertEquals("Active 15m ago", GridActiveAgo.label(holder.state.value.cards[0].activeAgo))

            holder.applyPresenceDelta(PresenceDeltaDto(userId = "a", online = true, lastActiveAt = "2026-11-24T12:01:00Z"))
            assertEquals(GridActiveAgo.ONLINE, holder.state.value.cards[0].activeAgo)
            assertNull(GridActiveAgo.label(GridActiveAgo.ONLINE), "online shows the dot, not a caption")
        }
}

private fun card(id: String) = GridProfileCardUi(id = id, displayName = id, online = false, distanceLabel = "1 km", tone = GridCardTone.Slate)

private fun feed(
    vararg cards: GridProfileCardUi,
    cursor: String? = null,
) = GridFeedUi(cards = cards.toList(), nextCursor = cursor)

private class SwitchableGridFeedRepository(
    var feed: GridFeedUi,
) : GridFeedRepository {
    var failure: Throwable? = null
    var loadCount = 0
        private set

    override suspend fun loadGrid(
        filters: GridFilters,
        cursor: String?,
    ): GridFeedUi {
        loadCount += 1
        failure?.let { throw it }
        return feed
    }
}

/** The first load parks on [gate]; later loads answer at once. */
private class GatedOnceGridFeedRepository(
    private val feed: GridFeedUi,
    private val gate: CompletableDeferred<Unit>,
) : GridFeedRepository {
    private var calls = 0

    override suspend fun loadGrid(
        filters: GridFilters,
        cursor: String?,
    ): GridFeedUi {
        calls += 1
        if (calls == 1) gate.await()
        return feed
    }
}

/** Cursor-less loads answer [firstPage]; a cursor page parks on [gate] and answers [nextPage]. */
private class PagedGridFeedRepository(
    private val firstPage: GridFeedUi,
    private val nextPage: GridFeedUi,
    private val gate: CompletableDeferred<Unit>,
) : GridFeedRepository {
    override suspend fun loadGrid(
        filters: GridFilters,
        cursor: String?,
    ): GridFeedUi {
        if (cursor == null) return firstPage
        gate.await()
        return nextPage
    }
}

private class FakeSnapshotStore(
    private val initial: List<GridProfileCardUi>,
) : GridSnapshotStore {
    override suspend fun load(): List<GridProfileCardUi> = initial

    override suspend fun save(cards: List<GridProfileCardUi>) = Unit

    override suspend fun clear() = Unit
}
