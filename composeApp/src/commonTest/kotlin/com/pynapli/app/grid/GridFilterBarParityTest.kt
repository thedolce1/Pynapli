package com.pynapli.app.grid

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Plan G2-4, Grindr filter-bar parity: the client side of each new Grid filter. */
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
}
