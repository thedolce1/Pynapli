package com.pynapli.app.grid

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * G2-2 ticket 3, on virtual time: chip taps are debounced (150 ms) and a newer tap or filter apply
 * cancels the request still waiting or on the wire. Each tap is its own coroutine, as on screen.
 */
class GridChipDebounceTest {
    @Test
    fun aBurstOfChipTapsSendsOneRequestWithTheFinalFilters() =
        runTest {
            val repository = RecordingGridFeedRepository()
            val holder = GridStateHolder(repository)

            launch { holder.toggleQuickFilter(QuickFilterId.Online) }
            advanceTimeBy(50)
            launch { holder.toggleQuickFilter(QuickFilterId.Verified) }
            advanceTimeBy(50)
            launch { holder.toggleQuickFilter(QuickFilterId.Fresh) }
            runCurrent()

            // The chips show the taps at once; nothing is on the wire inside the debounce.
            assertTrue(holder.state.value.filters.online && holder.state.value.filters.verified && holder.state.value.filters.fresh)
            assertEquals(0, repository.requests.size)

            advanceUntilIdle()

            assertEquals(1, repository.requests.size)
            val sent = repository.requests.single()
            assertTrue(sent.online && sent.verified && sent.fresh)
        }

    @Test
    fun nothingIsSentBeforeTheDebounceEnds() =
        runTest {
            val repository = RecordingGridFeedRepository()
            val holder = GridStateHolder(repository)

            launch { holder.toggleQuickFilter(QuickFilterId.Online) }
            advanceTimeBy(LatestFilterRequest.CHIP_DEBOUNCE.inWholeMilliseconds - 1)
            runCurrent()
            assertEquals(0, repository.requests.size)

            advanceTimeBy(2)
            runCurrent()
            assertEquals(1, repository.requests.size)
        }

    @Test
    fun aNewerTapCancelsTheRequestAlreadyOnTheWireAndOnlyItsAnswerLands() =
        runTest {
            val repository = RecordingGridFeedRepository(holdAnswers = true)
            val holder = GridStateHolder(repository)

            launch { holder.toggleQuickFilter(QuickFilterId.Online) }
            advanceUntilIdle()
            assertEquals(1, repository.requests.size, "the first tap is on the wire")

            launch { holder.toggleQuickFilter(QuickFilterId.Verified) }
            runCurrent()
            assertEquals(1, repository.cancelled, "the older request is cancelled as soon as a newer tap comes")

            advanceUntilIdle()
            assertEquals(2, repository.requests.size)
            repository.answerAll(GridFeedUi(cards = listOf(card("fresh-answer"))))
            advanceUntilIdle()

            assertEquals(listOf("fresh-answer"), holder.state.value.cards.map { it.id })
            assertTrue(holder.state.value.filters.online && holder.state.value.filters.verified)
        }

    @Test
    fun applyingFiltersCancelsAPendingChipTapAndIsNotDebounced() =
        runTest {
            val repository = RecordingGridFeedRepository()
            val holder = GridStateHolder(repository)

            launch { holder.toggleQuickFilter(QuickFilterId.Online) }
            runCurrent()
            launch { holder.applyFilters(GridFilters.defaults.copy(verified = true)) }
            runCurrent()

            assertEquals(1, repository.requests.size, "the apply goes out at once")
            assertEquals(GridFilters.defaults.copy(verified = true), repository.requests.single())
            advanceUntilIdle()
            assertEquals(1, repository.requests.size, "the cancelled chip tap never sends")
        }

    @Test
    fun sequentialCallsInOneCoroutineDoNotCancelEachOther() =
        runTest {
            val repository = RecordingGridFeedRepository()
            val holder = GridStateHolder(repository)

            holder.toggleQuickFilter(QuickFilterId.Online)
            holder.toggleQuickFilter(QuickFilterId.Verified)

            assertEquals(2, repository.requests.size)
        }

    private fun card(id: String): GridProfileCardUi = GridProfileCardUi(id = id, displayName = id, online = false, distanceLabel = null)
}

/** Records every request; with [holdAnswers] each one waits until [answerAll], so it can be cancelled on the wire. */
private class RecordingGridFeedRepository(
    private val holdAnswers: Boolean = false,
) : GridFeedRepository {
    val requests = mutableListOf<GridFilters>()
    var cancelled = 0
        private set
    private val pending = mutableListOf<CompletableDeferred<GridFeedUi>>()

    override suspend fun loadGrid(
        filters: GridFilters,
        cursor: String?,
    ): GridFeedUi {
        requests += filters
        if (!holdAnswers) return GridFeedUi(cards = emptyList())
        val answer = CompletableDeferred<GridFeedUi>()
        pending += answer
        try {
            return answer.await()
        } catch (error: kotlinx.coroutines.CancellationException) {
            cancelled += 1
            throw error
        }
    }

    fun answerAll(feed: GridFeedUi) {
        pending.forEach { it.complete(feed) }
        pending.clear()
    }
}
