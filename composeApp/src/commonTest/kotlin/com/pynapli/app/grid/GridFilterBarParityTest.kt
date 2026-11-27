package com.pynapli.app.grid

import com.pynapli.shared.network.dto.GridCountResponseDto
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Plan G2-4, Grindr filter-bar parity: the client side of each new Grid filter and of hide. */
class GridFilterBarParityTest {
    @Test
    fun haventChattedSerializesCountsAndRoundTripsThroughAPreset() {
        val filters = GridFilters(haventChatted = true)
        val request = filters.toGridQueryRequest(cursor = null)

        assertTrue(request.haventChattedOnly)
        assertFalse(GridFilters().toGridQueryRequest(cursor = null).haventChattedOnly)
        assertEquals(1, filters.activeCount)
        assertEquals(filters, GridFilters.fromGridQueryRequest(request))
        assertTrue(filters.toGridFeedRequest(cursor = null).haventChattedOnly, "the live query carries it too")
    }

    @Test
    fun hidingACardDropsItAtOnceKeepsTheOrderAndBackfillsFromTheSameCursor() =
        runTest {
            val repository =
                PagedGridFeedRepository(
                    mapOf(
                        null to GridFeedUi(cards = listOf(card("a"), card("b"), card("c")), nextCursor = "page-2"),
                        "page-2" to GridFeedUi(cards = listOf(card("d")), nextCursor = null),
                    ),
                )
            val holder = GridStateHolder(repository)
            holder.load()

            holder.removeCardById("b")

            // Everyone after the hidden card moved up one slot in place; the next page filled the tail.
            assertEquals(listOf("a", "c", "d"), holder.state.value.cards.map { it.id })
            assertEquals(listOf<String?>(null, "page-2"), repository.cursors, "no refresh, the same cursor continued")
        }

    @Test
    fun hidingWithNothingMoreToLoadNeverRefetches() =
        runTest {
            val repository =
                PagedGridFeedRepository(mapOf(null to GridFeedUi(cards = listOf(card("a"), card("b")), nextCursor = null)))
            val holder = GridStateHolder(repository)
            holder.load()

            holder.removeCardById("a")
            holder.removeCardById("not-on-screen")

            assertEquals(listOf("b"), holder.state.value.cards.map { it.id })
            assertEquals(listOf<String?>(null), repository.cursors)
        }

    @Test
    fun theApplyButtonSaysHowManyPeopleShowAndNeverInventsANumber() {
        assertEquals("Apply", gridApplyLabel(null))
        assertEquals("Show 0 people", gridApplyLabel(GridCountResponseDto(count = 0)))
        assertEquals("Show 1 person", gridApplyLabel(GridCountResponseDto(count = 1)))
        assertEquals("Show 12 people", gridApplyLabel(GridCountResponseDto(count = 12)))
        assertEquals("Show 500+ people", gridApplyLabel(GridCountResponseDto(count = 500, atLeast = true)))
    }

    @Test
    fun theCountAsksWithTheDraftAsTheGridWouldSendItAndAFailureIsNoNumber() =
        runTest {
            val repository = CountingFeedRepository(GridCountResponseDto(count = 7))
            val holder = GridStateHolder(repository)
            // Singles alone rules the partner filters out, exactly as the request leaves them out.
            val draft = GridFilters(singles = true, partnerVerified = true, haventChatted = true)

            assertEquals(GridCountResponseDto(count = 7), holder.countFor(draft))
            assertEquals(draft.withPartnerFiltersReconciled(), repository.asked.single())
            assertFalse(repository.asked.single().partnerVerified)

            repository.fail = true
            assertEquals(null, holder.countFor(draft))
        }

    private fun card(id: String): GridProfileCardUi = GridProfileCardUi(id = id, displayName = id.uppercase(), online = false, distanceLabel = null)
}

private class PagedGridFeedRepository(
    private val pages: Map<String?, GridFeedUi>,
) : GridFeedRepository {
    val cursors = mutableListOf<String?>()

    override suspend fun loadGrid(
        filters: GridFilters,
        cursor: String?,
    ): GridFeedUi {
        cursors += cursor
        return pages.getValue(cursor)
    }
}

private class CountingFeedRepository(
    private val answer: GridCountResponseDto,
) : GridFeedRepository {
    val asked = mutableListOf<GridFilters>()
    var fail = false

    override suspend fun loadGrid(
        filters: GridFilters,
        cursor: String?,
    ): GridFeedUi = GridFeedUi(cards = emptyList())

    override suspend fun countGrid(filters: GridFilters): GridCountResponseDto? {
        asked += filters
        if (fail) throw GridFeedException(code = "RATE_LIMITED", message = "Slow down")
        return answer
    }
}
