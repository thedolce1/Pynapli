package com.pynapli.app.grid

import com.pynapli.shared.network.dto.PresenceDeltaDto
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
    fun togglingOnlineSetsFlagAndCallsRepoWithCombinedFilters() = runBlocking {
        val repository = FakeGridFeedRepository(GridFeedUi(cards = emptyList()))
        val stateHolder = GridStateHolder(repository)

        stateHolder.load()
        stateHolder.toggleQuickFilter(QuickFilterId.Online)

        assertEquals(2, repository.loadCount)
        val latest = repository.lastFilters
        assertTrue(latest != null && latest.online)
        assertEquals(true, latest.toGridQueryRequest(cursor = null).onlineOnly)
    }

    @Test
    fun togglingVerifiedSetsVerifiedOnlyOnRequest() = runBlocking {
        val repository = FakeGridFeedRepository(GridFeedUi(cards = emptyList()))
        val stateHolder = GridStateHolder(repository)

        stateHolder.load()
        stateHolder.toggleQuickFilter(QuickFilterId.Verified)

        val request = repository.lastFilters!!.toGridQueryRequest(cursor = null)
        assertTrue(stateHolder.state.value.filters.verified)
        assertEquals(true, request.verifiedOnly)
        assertEquals(false, request.facePhotoVerifiedOnly)
        assertEquals(false, request.ageVerifiedOnly)
    }

    @Test
    fun togglingMultipleQuickFiltersCombinesIntoSingleRequest() = runBlocking {
        val repository = FakeGridFeedRepository(GridFeedUi(cards = emptyList()))
        val stateHolder = GridStateHolder(repository)

        stateHolder.load()
        stateHolder.toggleQuickFilter(QuickFilterId.Online)
        stateHolder.toggleQuickFilter(QuickFilterId.Linked)
        stateHolder.toggleQuickFilter(QuickFilterId.Singles)

        val request = repository.lastFilters!!.toGridQueryRequest(cursor = null)
        assertEquals(true, request.onlineOnly)
        assertEquals(true, request.linkedOnly)
        assertEquals(listOf(GridFilters.SINGLES_CONTEXT), request.relationshipContexts)
    }

    @Test
    fun setRadiusMetersClampsAndReloads() = runBlocking {
        val repository = FakeGridFeedRepository(GridFeedUi(cards = emptyList()))
        val stateHolder = GridStateHolder(repository)

        stateHolder.load()
        stateHolder.setRadiusMeters(5_000)

        val request = repository.lastFilters!!.toGridQueryRequest(cursor = null)
        assertEquals(5_000, request.radiusMeters)

        stateHolder.setRadiusMeters(meters = 999_999)
        assertEquals(GridFilters.MAX_RADIUS_METERS, repository.lastFilters!!.radiusMeters)

        stateHolder.setRadiusMeters(meters = 0)
        assertEquals(GridFilters.MIN_RADIUS_METERS, repository.lastFilters!!.radiusMeters)
    }

    @Test
    fun setAgeRangeNormalizesReversedBoundsAndReloads() = runBlocking {
        val repository = FakeGridFeedRepository(GridFeedUi(cards = emptyList()))
        val stateHolder = GridStateHolder(repository)

        stateHolder.load()
        stateHolder.setAgeRange(minimum = 50, maximum = 30)

        val request = repository.lastFilters!!.toGridQueryRequest(cursor = null)
        assertEquals(30, request.minimumAge)
        assertEquals(50, request.maximumAge)
    }

    @Test
    fun clearAllFiltersResetsToDefaultsAndReloads() = runBlocking {
        val repository = FakeGridFeedRepository(GridFeedUi(cards = emptyList()))
        val stateHolder = GridStateHolder(repository)

        stateHolder.load()
        stateHolder.toggleQuickFilter(QuickFilterId.Online)
        stateHolder.toggleQuickFilter(QuickFilterId.Linked)
        stateHolder.setRadiusMeters(2_000)
        val callsBefore = repository.loadCount

        stateHolder.clearAllFilters()

        assertEquals(GridFilters.defaults, stateHolder.state.value.filters)
        assertEquals(callsBefore + 1, repository.loadCount)
    }

    @Test
    fun applyFiltersWithSameValueIsNoOp() = runBlocking {
        val repository = FakeGridFeedRepository(GridFeedUi(cards = emptyList()))
        val stateHolder = GridStateHolder(repository)

        stateHolder.load()
        val before = repository.loadCount
        stateHolder.applyFilters(stateHolder.state.value.filters)

        assertEquals(before, repository.loadCount)
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

            val selectJob = async(Dispatchers.Default) { stateHolder.toggleQuickFilter(QuickFilterId.Online) }
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
        assertTrue(finalState.filters.online)
        assertFalse(finalState.isRefreshing)
    }

    @Test
    fun presenceDeltaUpdatesOnlineFlagWithoutReorderingOrRefreshing() = runBlocking {
        val cards = listOf(
            GridProfileCardUi(id = "a", displayName = "A", online = true, distanceLabel = null, tone = GridCardTone.Warm),
            GridProfileCardUi(id = "b", displayName = "B", online = true, distanceLabel = null, tone = GridCardTone.Slate),
            GridProfileCardUi(id = "c", displayName = "C", online = false, distanceLabel = null, tone = GridCardTone.Rust)
        )
        val repository = FakeGridFeedRepository(GridFeedUi(cards = cards))
        val stateHolder = GridStateHolder(repository)

        stateHolder.load()
        assertEquals(1, repository.loadCount)

        stateHolder.applyPresenceDelta(
            PresenceDeltaDto(userId = "b", online = false, lastActiveAt = "2026-05-09T12:00:00Z")
        )
        stateHolder.applyPresenceDelta(
            PresenceDeltaDto(userId = "c", online = true, lastActiveAt = "2026-05-09T12:00:01Z")
        )

        val resulting = stateHolder.state.value.cards
        assertEquals(listOf("a", "b", "c"), resulting.map { it.id }, "Order must not change after presence deltas")
        assertEquals(true, resulting[0].online, "Card a online flag unchanged")
        assertEquals(false, resulting[1].online, "Card b flipped to offline")
        assertEquals(true, resulting[2].online, "Card c flipped to online")
        assertEquals(1, repository.loadCount, "Presence delta must not trigger a grid refresh")
    }

    @Test
    fun presenceDeltaForUnknownUserIsNoOp() = runBlocking {
        val cards = listOf(
            GridProfileCardUi(id = "a", displayName = "A", online = true, distanceLabel = null, tone = GridCardTone.Warm)
        )
        val repository = FakeGridFeedRepository(GridFeedUi(cards = cards))
        val stateHolder = GridStateHolder(repository)
        stateHolder.load()

        val before = stateHolder.state.value
        stateHolder.applyPresenceDelta(
            PresenceDeltaDto(userId = "not-in-grid", online = false, lastActiveAt = "2026-05-09T12:00:00Z")
        )
        val after = stateHolder.state.value
        assertEquals(before.cards, after.cards)
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
    var lastFilters: GridFilters? = null
        private set

    override suspend fun loadGrid(filters: GridFilters, cursor: String?): GridFeedUi {
        receivedCursors += cursor
        loadCount += 1
        lastFilters = filters
        return feeds.getOrElse(loadCount - 1) { feeds.last() }
    }
}

private class GatedGridFeedRepository(
    private val feeds: List<GridFeedUi>,
    private val gateBeforeIndex: Int,
    private val gate: CompletableDeferred<Unit>
) : GridFeedRepository {
    private var loadCount = 0

    override suspend fun loadGrid(filters: GridFilters, cursor: String?): GridFeedUi {
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

    override suspend fun loadGrid(filters: GridFilters, cursor: String?): GridFeedUi {
        val outcome = outcomes.getOrElse(loadCount) { outcomes.last() }
        loadCount += 1
        return when (outcome) {
            is GridFeedOutcome.Success -> outcome.feed
            is GridFeedOutcome.Failure -> throw outcome.error
        }
    }
}
